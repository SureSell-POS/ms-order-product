-- =====================================================================
-- V64 -- Cuentas por cobrar por documento: la factura sabe cuándo vence,
--        el abono se aplica a facturas y deja recibo con número.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F4.2 (M-P3 y
-- M-P4). La función de la venta a crédito (F4.3) va en V65.
--
-- ── Lo que había ──────────────────────────────────────────────────────
--
-- `accounts_receivable` guarda un saldo (`total_debt`) y `debt_transactions` el
-- libro, solo-anexar desde V44. Pero un abono iba a LA CUENTA, no a facturas:
-- no se sabía qué factura quedaba viva ni desde cuándo, cualquier abono reiniciaba
-- la mora (`last_transaction_date`), no había recibo, y `plazo_dias` no lo leía
-- nadie (anexo C-4, C-5, C-6 del plan).
--
-- ── Lo que se añade ───────────────────────────────────────────────────
--
-- 1. `UNIQUE (tenant_id, customer_document)`: hoy solo lo cuidaba la aplicación.
--    Medido antes, 2026-09-13: 0 duplicados en staging y 0 filas en producción.
--    `customer_document` pasa de VARCHAR(20) a TEXT, como `clientes.documento`.
-- 2. `debt_transactions.vence_el` (el débito sabe cuándo vence, congelado al
--    nacer) y `recibo_id` (el abono sabe de qué recibo sale).
-- 3. `recibos_de_caja`: el abono como documento, con número por negocio SIN
--    HUECOS NI REUTILIZACIÓN. Un contador por negocio que se incrementa con
--    `INSERT … ON CONFLICT DO UPDATE … RETURNING` dentro de la misma transacción
--    que el recibo: si el recibo no entra, el contador tampoco se movió. Una
--    SEQUENCE no sirve: deja huecos en cada rollback.
--    Anular un recibo es OTRO recibo (`anula_recibo_id`, `motivo_anulacion`),
--    nunca un UPDATE ni un DELETE. Un recibo se anula una sola vez.
-- 4. `cartera_aplicaciones`: qué parte de qué abono pagó qué factura.
-- 5. Vistas `v_cartera_por_documento` y `v_cartera_por_cliente`, con
--    `security_invoker`: la edad y la mora se calculan AL LEER, nada las guarda
--    (R4) y ningún planificador las recalcula (R14). La mora es por factura: un
--    abono a otra factura no la reinicia. El saldo sale SIEMPRE del libro, nunca de
--    `total_debt`. Una factura sin `vence_el` (cliente sin plazo pactado) va en su
--    tramo, SIN_PLAZO_PACTADO: ni vencida ni corriente, y no se inventa un plazo.
-- 6. (La política de crédito por sede, D9, sale de aquí: retener pedidos es F5.)
-- 7. `clientes.en_insolvencia_desde` (Ley 2445 de 2025): suspende cobros y nuevo
--    crédito. Entra en `clientes_eventos`: la función se redefinió partiendo del
--    cuerpo que devolvió `pg_get_functiondef` en staging (V63, md5
--    e102e0424a3bf0a12ce1430c4f7b2e0f), con una fila más.
--
-- ── 🔴 Lo que NO se cambia, a propósito (R16) ─────────────────────────
--
-- `ms-core-app` lee `debt_transactions.type` como enum DEBIT|CREDIT y
-- `payment_method` como CASH|CARD|TRANSFER|CHECK|OTHER
-- (`DebtTransactionEntity.java:35,48`). Un valor nuevo en esas columnas tumbaría
-- su pantalla de cartera. Por eso un abono es CREDIT con `payment_method` NULL y
-- el medio real vive en `recibos_de_caja.medio`; la anulación de un abono es un
-- DEBIT con el `recibo_id` del recibo de anulación. Y para que nadie lo rompa sin
-- enterarse: CHECK en las dos columnas con exactamente los valores que el core
-- entiende (tablas vacías en los dos entornos, medido).
--
-- `total_debt` (V28/V44) se mantiene porque lo lee el panel viejo de core; es un
-- índice verificado: el bloque de cierre comprueba que cuadra con el libro tras un
-- abono y tras su anulación.
--
-- IMPACTO: una unicidad y un cambio de tipo sobre tablas vacías en los dos
-- entornos (medido), dos columnas nulas y dos CHECK validados sobre un libro
-- vacío, tres tablas nuevas con RLS FORCE, dos vistas y una columna en `clientes`.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · Unicidad del documento por negocio ──────────────────────────
ALTER TABLE accounts_receivable ALTER COLUMN customer_document TYPE TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS ux_ar_tenant_documento ON accounts_receivable (tenant_id, customer_document);

-- ── 3 · Recibos de caja (antes que el 2, por la FK) ─────────────────
CREATE TABLE IF NOT EXISTS recibos_de_caja (
    id                 UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          TEXT          NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    numero             BIGINT        NOT NULL,
    cliente_documento  TEXT          NOT NULL,
    monto              NUMERIC(15,2) NOT NULL,
    medio              TEXT          NOT NULL,
    referencia_medio   TEXT          NULL,
    numero_talonario   TEXT          NULL,
    cobrado_por        BIGINT        NULL REFERENCES users(id),
    site_id            BIGINT        NULL,
    liquidacion_id     UUID          NULL,
    ocurrido_en        TIMESTAMPTZ   NOT NULL,
    registrado_en      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    idempotency_key    TEXT          NOT NULL,
    anula_recibo_id    UUID          NULL,
    motivo_anulacion   TEXT          NULL,
    CONSTRAINT pk_recibos_de_caja PRIMARY KEY (id),
    CONSTRAINT ux_recibos_idempotencia UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ux_recibos_numero UNIQUE (tenant_id, numero),
    CONSTRAINT fk_recibos_anula FOREIGN KEY (anula_recibo_id) REFERENCES recibos_de_caja (id),
    CONSTRAINT ck_recibos_monto CHECK (monto > 0),
    CONSTRAINT ck_recibos_medio CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'BRE_B', 'QR', 'TARJETA', 'CHEQUE')),
    CONSTRAINT ck_recibos_motivo CHECK (motivo_anulacion IS NULL OR motivo_anulacion IN
        ('ERROR_DE_MONTO', 'CHEQUE_DEVUELTO', 'DUPLICADO', 'APLICADO_A_OTRO_CLIENTE')),
    CONSTRAINT ck_recibos_anulacion_coherente CHECK ((anula_recibo_id IS NULL) = (motivo_anulacion IS NULL))
);
-- Un recibo se anula una sola vez.
CREATE UNIQUE INDEX IF NOT EXISTS ux_recibos_una_anulacion ON recibos_de_caja (anula_recibo_id) WHERE anula_recibo_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_recibos_cliente ON recibos_de_caja (tenant_id, cliente_documento, ocurrido_en);
-- El recaudo del cierre (F4.5): efectivo por sede y ventana.
CREATE INDEX IF NOT EXISTS ix_recibos_cierre ON recibos_de_caja (tenant_id, medio, registrado_en);

COMMENT ON TABLE recibos_de_caja IS
    'El abono como documento (F4.2). Numero por negocio sin huecos ni reutilizacion (contadores_de_recibos). '
    'Anular = otro recibo con anula_recibo_id y motivo; nunca UPDATE ni DELETE. V64.';

CREATE TABLE IF NOT EXISTS contadores_de_recibos (
    tenant_id TEXT   NOT NULL,
    ultimo    BIGINT NOT NULL,
    CONSTRAINT pk_contadores_de_recibos PRIMARY KEY (tenant_id),
    CONSTRAINT ck_contadores_de_recibos CHECK (ultimo >= 1)
);

CREATE OR REPLACE FUNCTION fn_numero_de_recibo()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- En la misma transacción que el recibo: si el recibo no entra, el contador
    -- no se movió. El UPSERT serializa a dos cajas del mismo negocio.
    INSERT INTO public.contadores_de_recibos AS c (tenant_id, ultimo) VALUES (NEW.tenant_id, 1)
    ON CONFLICT (tenant_id) DO UPDATE SET ultimo = c.ultimo + 1
    RETURNING c.ultimo INTO NEW.numero;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_recibos_numero ON recibos_de_caja;
CREATE TRIGGER trg_recibos_numero BEFORE INSERT ON recibos_de_caja
    FOR EACH ROW EXECUTE FUNCTION fn_numero_de_recibo();

-- ── 2 · El débito sabe cuándo vence; el abono, de qué recibo sale ───
ALTER TABLE debt_transactions ADD COLUMN IF NOT EXISTS vence_el  DATE NULL;
ALTER TABLE debt_transactions ADD COLUMN IF NOT EXISTS recibo_id UUID NULL REFERENCES recibos_de_caja (id);
CREATE INDEX IF NOT EXISTS ix_debt_transactions_cuenta ON debt_transactions (tenant_id, account_id, transaction_date);

COMMENT ON COLUMN debt_transactions.vence_el IS
    'DEBIT de una venta: fecha de la venta + plazo_dias del cliente, congelada al nacer. NULL en abonos y en lo anterior a V64.';
COMMENT ON COLUMN debt_transactions.recibo_id IS
    'CREDIT (abono) o DEBIT (anulacion de un abono): el recibo del que sale. NULL en debitos de venta.';

-- ── 4 · Aplicaciones ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cartera_aplicaciones (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     TEXT          NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    recibo_id     UUID          NOT NULL REFERENCES recibos_de_caja (id),
    credito_tx_id VARCHAR(36)   NOT NULL REFERENCES debt_transactions (id),
    debito_tx_id  VARCHAR(36)   NOT NULL REFERENCES debt_transactions (id),
    monto         NUMERIC(15,2) NOT NULL,
    regla         TEXT          NOT NULL,
    ocurrido_en   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cartera_aplicaciones PRIMARY KEY (id),
    CONSTRAINT ck_aplicaciones_monto CHECK (monto > 0),
    CONSTRAINT ck_aplicaciones_regla CHECK (regla IN ('MAS_ANTIGUA_PRIMERO', 'ELEGIDA_POR_USUARIO'))
);
CREATE INDEX IF NOT EXISTS ix_aplicaciones_debito ON cartera_aplicaciones (tenant_id, debito_tx_id);
CREATE INDEX IF NOT EXISTS ix_aplicaciones_recibo ON cartera_aplicaciones (tenant_id, recibo_id);

-- ── R16 · Lo que el core entiende, cerrado en la base ───────────────
ALTER TABLE debt_transactions DROP CONSTRAINT IF EXISTS ck_debt_transactions_type;
ALTER TABLE debt_transactions ADD CONSTRAINT ck_debt_transactions_type
    CHECK (type IN ('DEBIT', 'CREDIT')) NOT VALID;
ALTER TABLE debt_transactions VALIDATE CONSTRAINT ck_debt_transactions_type;
ALTER TABLE debt_transactions DROP CONSTRAINT IF EXISTS ck_debt_transactions_payment_method;
ALTER TABLE debt_transactions ADD CONSTRAINT ck_debt_transactions_payment_method
    CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'CARD', 'TRANSFER', 'CHECK', 'OTHER')) NOT VALID;
ALTER TABLE debt_transactions VALIDATE CONSTRAINT ck_debt_transactions_payment_method;
COMMENT ON COLUMN debt_transactions.type IS
    'DEBIT | CREDIT y nada mas: ms-core-app lo lee como enum (DebtTransactionEntity). Un valor nuevo tumba su pantalla. V64.';

-- ── 7 · Insolvencia ─────────────────────────────────────────────────
ALTER TABLE clientes ADD COLUMN IF NOT EXISTS en_insolvencia_desde DATE NULL;
COMMENT ON COLUMN clientes.en_insolvencia_desde IS
    'Proceso de insolvencia desde esta fecha (Ley 2445 de 2025 / Ley 1116 de 2006): no se cobra ni se da nuevo credito. V64.';

ALTER TABLE clientes_eventos DROP CONSTRAINT IF EXISTS ck_clientes_eventos_campo;
ALTER TABLE clientes_eventos ADD CONSTRAINT ck_clientes_eventos_campo CHECK (campo IN (
    'nombre', 'lista_precio_id', 'plazo_dias', 'activo', 'vendedor_id', 'tipo_cliente',
    'exige_factura', 'tipo_documento', 'razon_social', 'direccion_entrega', 'municipio_dane',
    'correo', 'whatsapp', 'telefono', 'en_insolvencia_desde'));

-- El cuerpo de V62, con una fila más en la lista de campos.
CREATE OR REPLACE FUNCTION fn_clientes_eventos()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_autor_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_autor     BIGINT := CASE WHEN v_autor_txt ~ '^[0-9]+$' THEN v_autor_txt::BIGINT END;
    v_campo     TEXT;
    v_antes     TEXT;
    v_despues   TEXT;
    v_escritos  INT := 0;
BEGIN
    FOR v_campo, v_antes, v_despues IN
        SELECT c.campo, c.antes, c.despues FROM (VALUES
            ('nombre',               OLD.nombre,                       NEW.nombre),
            ('lista_precio_id',      OLD.lista_precio_id::text,        NEW.lista_precio_id::text),
            ('plazo_dias',           OLD.plazo_dias::text,             NEW.plazo_dias::text),
            ('activo',               OLD.activo::text,                 NEW.activo::text),
            ('vendedor_id',          OLD.vendedor_id::text,            NEW.vendedor_id::text),
            ('tipo_cliente',         OLD.tipo_cliente,                 NEW.tipo_cliente),
            ('exige_factura',        OLD.exige_factura::text,          NEW.exige_factura::text),
            ('tipo_documento',       OLD.tipo_documento,               NEW.tipo_documento),
            ('razon_social',         OLD.razon_social,                 NEW.razon_social),
            ('direccion_entrega',    OLD.direccion_entrega,            NEW.direccion_entrega),
            ('municipio_dane',       OLD.municipio_dane,               NEW.municipio_dane),
            ('correo',               OLD.correo,                       NEW.correo),
            ('whatsapp',             OLD.whatsapp,                     NEW.whatsapp),
            ('telefono',             OLD.telefono,                     NEW.telefono),
            ('en_insolvencia_desde', OLD.en_insolvencia_desde::text,   NEW.en_insolvencia_desde::text)
        ) AS c(campo, antes, despues)
        WHERE c.antes IS DISTINCT FROM c.despues
    LOOP
        INSERT INTO public.clientes_eventos (tenant_id, cliente_id, campo, valor_anterior, valor_nuevo, usuario_id)
        VALUES (NEW.tenant_id, NEW.id, v_campo, v_antes, v_despues, v_autor);
        v_escritos := v_escritos + 1;
    END LOOP;
    IF v_escritos > 0 AND v_autor IS NULL THEN
        RAISE WARNING 'clientes_eventos sin autor: cliente % del negocio % cambio % campos sin app.user_id',
            NEW.id, NEW.tenant_id, v_escritos;
    END IF;
    RETURN NULL;
END $$;

-- ── 5 · Vistas: la edad y la mora, al leer ──────────────────────────
DROP VIEW IF EXISTS v_cartera_por_cliente;
DROP VIEW IF EXISTS v_cartera_por_documento;

CREATE VIEW v_cartera_por_documento WITH (security_invoker = true) AS
WITH recibos_anulados AS (
    SELECT r.anula_recibo_id AS id, r.tenant_id FROM public.recibos_de_caja r WHERE r.anula_recibo_id IS NOT NULL
),
aplicado AS (
    SELECT a.tenant_id, a.debito_tx_id, sum(a.monto) AS monto
      FROM public.cartera_aplicaciones a
     WHERE NOT EXISTS (SELECT 1 FROM recibos_anulados x WHERE x.id = a.recibo_id AND x.tenant_id = a.tenant_id)
     GROUP BY a.tenant_id, a.debito_tx_id
),
hoy AS (SELECT (now() AT TIME ZONE 'America/Bogota')::date AS d)
SELECT d.tenant_id,
       d.id                                   AS debito_tx_id,
       d.account_id,
       ar.customer_document                   AS cliente_documento,
       d.order_uuid,
       d.transaction_date                     AS fecha,
       d.amount                               AS monto,
       d.vence_el,
       COALESCE(ap.monto, 0)                  AS aplicado,
       d.amount - COALESCE(ap.monto, 0)       AS saldo,
       CASE WHEN d.vence_el IS NOT NULL AND d.amount - COALESCE(ap.monto, 0) > 0
            THEN GREATEST(0, (SELECT d FROM hoy) - d.vence_el) END AS dias_vencido,
       CASE
           WHEN d.amount - COALESCE(ap.monto, 0) <= 0 THEN 'PAGADA'
           WHEN d.vence_el IS NULL THEN 'SIN_PLAZO_PACTADO'
           WHEN (SELECT d FROM hoy) <= d.vence_el THEN 'CORRIENTE'
           WHEN (SELECT d FROM hoy) - d.vence_el <= 30  THEN '1_30'
           WHEN (SELECT d FROM hoy) - d.vence_el <= 60  THEN '31_60'
           WHEN (SELECT d FROM hoy) - d.vence_el <= 90  THEN '61_90'
           WHEN (SELECT d FROM hoy) - d.vence_el <= 180 THEN '91_180'
           WHEN (SELECT d FROM hoy) - d.vence_el <= 360 THEN '181_360'
           ELSE 'MAS_360'
       END                                    AS edad
  FROM public.debt_transactions d
  JOIN public.accounts_receivable ar ON ar.id = d.account_id AND ar.tenant_id = d.tenant_id
  LEFT JOIN aplicado ap ON ap.debito_tx_id = d.id AND ap.tenant_id = d.tenant_id
 WHERE d.type = 'DEBIT'
   AND d.recibo_id IS NULL;   -- el DEBIT de una anulación de recibo no es una factura

COMMENT ON VIEW v_cartera_por_documento IS
    'Cada factura a credito con lo aplicado, el saldo y la edad calculados al leer (sin guardarlos). V64.';

CREATE VIEW v_cartera_por_cliente WITH (security_invoker = true) AS
SELECT ar.tenant_id,
       ar.customer_document                                          AS cliente_documento,
       ar.customer_name                                              AS nombre,
       ar.credit_limit                                               AS cupo,
       ar.total_debt,
       ar.status,
       COALESCE(sum(v.saldo) FILTER (WHERE v.saldo > 0), 0)          AS saldo,
       COALESCE(sum(v.saldo) FILTER (WHERE v.saldo > 0 AND v.dias_vencido > 0), 0) AS vencido,
       min(v.vence_el) FILTER (WHERE v.saldo > 0)                    AS factura_mas_vieja_vence_el,
       max(v.dias_vencido)                                           AS dias_vencido_max,
       COALESCE(sum(v.saldo) FILTER (WHERE v.saldo > 0), 0) > ar.credit_limit AS excede_cupo,
       c.en_insolvencia_desde,
       c.vendedor_id
  FROM public.accounts_receivable ar
  LEFT JOIN v_cartera_por_documento v ON v.account_id = ar.id AND v.tenant_id = ar.tenant_id
  LEFT JOIN public.clientes c ON c.tenant_id = ar.tenant_id AND c.documento = ar.customer_document
 GROUP BY ar.tenant_id, ar.customer_document, ar.customer_name, ar.credit_limit, ar.total_debt, ar.status,
          c.en_insolvencia_desde, c.vendedor_id;

COMMENT ON VIEW v_cartera_por_cliente IS
    'Resumen por cliente: saldo, vencido, factura mas vieja, cupo, insolvencia y vendedor. Al leer. V64.';

-- ── Aislamiento y permisos ──────────────────────────────────────────
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['recibos_de_caja', 'contadores_de_recibos', 'cartera_aplicaciones'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON public.%I', t, t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON public.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT ON public.recibos_de_caja        TO app_user;   -- anular es insertar
        GRANT SELECT, INSERT ON public.cartera_aplicaciones   TO app_user;
        GRANT SELECT, INSERT, UPDATE ON public.contadores_de_recibos TO app_user;  -- el UPSERT del número
        GRANT SELECT ON public.v_cartera_por_documento TO app_user;
        GRANT SELECT ON public.v_cartera_por_cliente   TO app_user;
    END IF;
END
$rls$;

-- =====================================================================
-- La comprobación, por comportamiento (el caso del plan: abono de 20.000 sobre
-- una deuda de 110.000).
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v64_a__';
    v_cuenta TEXT := gen_random_uuid()::text;
    v_deb1 TEXT := gen_random_uuid()::text;
    v_deb2 TEXT := gen_random_uuid()::text;
    v_cred TEXT := gen_random_uuid()::text;
    v_recibo UUID; v_recibo2 UUID; v_anula UUID;
    v_n1 BIGINT; v_n2 BIGINT; v_n3 BIGINT;
    v_saldo NUMERIC; v_filas INT; v_vencido NUMERIC; v_rechazado BOOLEAN;
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- Las vistas nacen con security_invoker (CREATE OR REPLACE VIEW lo borraría).
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname IN ('v_cartera_por_documento', 'v_cartera_por_cliente')
                 AND NOT COALESCE(reloptions @> ARRAY['security_invoker=true'], false)) THEN
        RAISE EXCEPTION 'V64: una vista de cartera no tiene security_invoker';
    END IF;

    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V64', 'basico');
    PERFORM set_config('app.tenant_id', a, true);
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                     status, total_debt, updated_at)
    VALUES (v_cuenta, a, now(), 500000, '__doc64__', 'Tienda V64', 'ACTIVE', 110000, now());
    -- Dos facturas: 60.000 vencida hace 40 días y 50.000 que vence en 10.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES (v_deb1, a, v_cuenta, 60000, now(), 'Venta a credito', v_hoy - 48, 'DEBIT', v_hoy - 40),
           (v_deb2, a, v_cuenta, 50000, now(), 'Venta a credito', v_hoy - 5, 'DEBIT', v_hoy + 10);

    -- 0. R16: un tipo o un medio que el core no entiende se rechazan.
    v_rechazado := false;
    BEGIN
        INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, transaction_date, type)
        VALUES (gen_random_uuid()::text, a, v_cuenta, 1, now(), v_hoy, 'ADJUSTMENT');
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V64: debt_transactions.type admitio un valor que el core no entiende';
    END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, transaction_date, type, payment_method)
        VALUES (gen_random_uuid()::text, a, v_cuenta, 1, now(), v_hoy, 'CREDIT', 'BRE_B');
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V64: debt_transactions.payment_method admitio un valor que el core no entiende';
    END IF;

    -- 1. Unicidad: una segunda cuenta con el mismo documento se rechaza.
    v_rechazado := false;
    BEGIN
        INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                         status, total_debt, updated_at)
        VALUES (gen_random_uuid()::text, a, now(), 0, '__doc64__', 'Duplicada', 'ACTIVE', 0, now());
    EXCEPTION WHEN unique_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V64: se admitio una segunda cuenta con el mismo documento';
    END IF;

    -- 2. Abono de 20.000 a la más antigua: recibo número 1, CREDIT y aplicación.
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, '__doc64__', 20000, 'EFECTIVO', now(), 'v64-1') RETURNING id, numero INTO v_recibo, v_n1;
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES (v_cred, a, v_cuenta, 20000, now(), 'Abono', v_hoy, 'CREDIT', v_recibo);
    INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_recibo, v_cred, v_deb1, 20000, 'MAS_ANTIGUA_PRIMERO');
    UPDATE accounts_receivable SET total_debt = total_debt - 20000 WHERE id = v_cuenta;

    SELECT saldo, vencido INTO v_saldo, v_vencido FROM v_cartera_por_cliente WHERE cliente_documento = '__doc64__';
    SELECT count(*) INTO v_filas FROM debt_transactions WHERE account_id = v_cuenta;
    IF (SELECT total_debt FROM accounts_receivable WHERE id = v_cuenta) IS DISTINCT FROM v_saldo THEN
        RAISE EXCEPTION 'V64: total_debt no cuadra con el saldo del libro tras el abono';
    END IF;
    IF v_saldo IS DISTINCT FROM 90000 OR v_filas <> 3 THEN
        RAISE EXCEPTION 'V64: tras abonar 20.000 sobre 110.000 el saldo fue % con % filas; debia ser 90.000 con 3', v_saldo, v_filas;
    END IF;
    -- 3. La mora es por factura: la vieja sigue vencida (40.000 de saldo) tras el abono.
    IF v_vencido IS DISTINCT FROM 40000
       OR (SELECT edad FROM v_cartera_por_documento WHERE debito_tx_id = v_deb1) <> '31_60'
       OR (SELECT edad FROM v_cartera_por_documento WHERE debito_tx_id = v_deb2) <> 'CORRIENTE' THEN
        RAISE EXCEPTION 'V64: la mora no es por factura (vencido %)', v_vencido;
    END IF;

    -- 4. Números sin huecos: el siguiente es 2; uno que falla no consume número.
    BEGIN
        INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
        VALUES (a, 0, '__doc64__', -1, 'EFECTIVO', now(), 'v64-malo');
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, '__doc64__', 5000, 'TRANSFERENCIA', now(), 'v64-2') RETURNING id, numero INTO v_recibo2, v_n2;
    IF v_n1 <> 1 OR v_n2 <> 2 THEN
        RAISE EXCEPTION 'V64: los numeros de recibo fueron % y %, debian ser 1 y 2', v_n1, v_n2;
    END IF;

    -- 5. Anular es otro recibo, una sola vez; la aplicación anulada deja de contar.
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key,
                                 anula_recibo_id, motivo_anulacion)
    VALUES (a, 0, '__doc64__', 20000, 'EFECTIVO', now(), 'v64-anula', v_recibo, 'ERROR_DE_MONTO')
    RETURNING id, numero INTO v_anula, v_n3;
    -- La anulación devuelve la deuda en el libro con un DEBIT del recibo de anulación.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES (gen_random_uuid()::text, a, v_cuenta, 20000, now(), 'Anulacion de abono', v_hoy, 'DEBIT', v_anula);
    UPDATE accounts_receivable SET total_debt = total_debt + 20000 WHERE id = v_cuenta;
    SELECT saldo INTO v_saldo FROM v_cartera_por_cliente WHERE cliente_documento = '__doc64__';
    IF v_saldo IS DISTINCT FROM 110000 OR v_n3 <> 3
       OR (SELECT total_debt FROM accounts_receivable WHERE id = v_cuenta) IS DISTINCT FROM v_saldo
       -- y el libro, sumado a mano, dice lo mismo que la vista
       OR (SELECT sum(CASE WHEN type = 'DEBIT' THEN amount ELSE -amount END) FROM debt_transactions
            WHERE account_id = v_cuenta) IS DISTINCT FROM v_saldo THEN
        RAISE EXCEPTION 'V64: tras anular el recibo el saldo, total_debt y el libro no cuadran en 110.000 (vista %)', v_saldo;
    END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key,
                                     anula_recibo_id, motivo_anulacion)
        VALUES (a, 0, '__doc64__', 20000, 'EFECTIVO', now(), 'v64-anula-2', v_recibo, 'DUPLICADO');
    EXCEPTION WHEN unique_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V64: un recibo se anulo dos veces';
    END IF;

    -- 6. Una factura sin plazo pactado va en su tramo: ni vencida ni corriente.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type)
    VALUES (gen_random_uuid()::text, a, v_cuenta, 7000, now(), 'Venta a credito sin plazo', v_hoy - 400, 'DEBIT');
    IF NOT EXISTS (SELECT 1 FROM v_cartera_por_documento
                    WHERE cliente_documento = '__doc64__' AND monto = 7000 AND edad = 'SIN_PLAZO_PACTADO'
                      AND dias_vencido IS NULL) THEN
        RAISE EXCEPTION 'V64: una factura sin plazo no quedo en SIN_PLAZO_PACTADO';
    END IF;

    -- Limpieza, hijos antes que padres, y barrido de todas las tablas con tenant_id.
    PERFORM set_config('app.tenant_id', '', true);
    DELETE FROM cartera_aplicaciones  WHERE tenant_id = a;
    DELETE FROM debt_transactions     WHERE tenant_id = a;
    UPDATE recibos_de_caja SET anula_recibo_id = NULL, motivo_anulacion = NULL WHERE tenant_id = a;
    DELETE FROM recibos_de_caja       WHERE tenant_id = a;
    DELETE FROM contadores_de_recibos WHERE tenant_id = a;
    DELETE FROM accounts_receivable   WHERE tenant_id = a;
    DELETE FROM tenants               WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario', 'pedidos', 'red')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id = $1', t.s, t.tn) INTO n USING a;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V64: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V64: abono de 20.000 sobre 110.000 deja 90.000 y su anulacion 110.000, con total_debt = libro en los dos pasos; mora por factura, SIN_PLAZO_PACTADO aparte, recibos 1-2-3 sin huecos, anulacion unica y type/payment_method cerrados a lo que lee el core.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP VIEW IF EXISTS v_cartera_por_cliente; DROP VIEW IF EXISTS v_cartera_por_documento;
-- DROP TABLE IF EXISTS cartera_aplicaciones;
-- ALTER TABLE debt_transactions DROP COLUMN IF EXISTS recibo_id, DROP COLUMN IF EXISTS vence_el;
-- DROP TRIGGER IF EXISTS trg_recibos_numero ON recibos_de_caja; DROP FUNCTION IF EXISTS fn_numero_de_recibo();
-- DROP TABLE IF EXISTS contadores_de_recibos; DROP TABLE IF EXISTS recibos_de_caja;
-- DROP INDEX IF EXISTS ux_ar_tenant_documento;
-- ALTER TABLE debt_transactions DROP CONSTRAINT IF EXISTS ck_debt_transactions_type, DROP CONSTRAINT IF EXISTS ck_debt_transactions_payment_method;
-- ALTER TABLE clientes DROP COLUMN IF EXISTS en_insolvencia_desde;  (+ CHECK y fn_clientes_eventos de V62)
