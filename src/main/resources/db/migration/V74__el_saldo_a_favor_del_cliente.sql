-- =====================================================================
-- V74 -- El saldo a favor del cliente: lo que pagó de más queda a su favor,
--        se aplica solo a sus facturas nuevas y se le puede devolver.
--
-- Plan de mayoristas F4.12 (diseño de Agente-mt aprobado por ECM el 2026-09-15;
-- «que se aplique solo», decisión de Santiago del 2026-09-14).
--
-- ── Principios ────────────────────────────────────────────────────────
--
--   · Es un PASIVO del negocio, no una venta: no pasa por `orders`.
--   · NO SE GUARDA: se deriva. Saldo a favor de un recibo = su monto − lo aplicado
--     desde él (a facturas o a devoluciones), si el recibo no está anulado
--     (`v_saldo_a_favor_por_recibo`).
--   · El libro que lee core sigue en DEBIT/CREDIT (R16). El abono de 120.000 sobre
--     100.000 es UN CREDIT de 120.000; el libro queda en −20.000 = el saldo a favor.
--     Aplicarlo NO escribe en el libro: es una fila de `cartera_aplicaciones` del
--     CREDIT del recibo a la factura, con regla SALDO_A_FAVOR_AUTOMATICO y su fecha.
--   · Devolverlo es un EGRESO (`egresos_de_cartera`, número propio sin huecos): un
--     DEBIT en el libro, cubierto entero por aplicaciones DEVOLUCION_DE_SALDO_A_FAVOR,
--     así que no aparece como factura con saldo. Solo el admin, en cualquier medio.
--   · Las vistas de V68 NO se tocan: el DEBIT del egreso tiene saldo 0 y edad PAGADA;
--     los lectores que listan documentos lo excluyen por `egresos_de_cartera`.
--
-- ── Cuándo se aplica solo (por eventos, nunca por planificador) ───────
--
--   · Al nacer una venta a crédito: `fn_venta_a_credito` llama a
--     `fn_aplicar_saldo_a_favor` después del DEBIT. Costo medido sin JIT
--     (CostoDeLaCarteraTest): 0,04 ms sin recibos, 0,09 ms con 5, ~5 ms con 5.000.
--   · Al anular un recibo que reabre facturas (desde la aplicación, en la misma transacción).
--   Orden: el recibo más antiguo con saldo, sobre la factura viva más antigua.
--
-- ── Insolvencia: NADA SE CRUZA (concepto jurídico, ECM 2026-09-15) ────
--
-- Aplicar el saldo a favor de un cliente en proceso a sus facturas es compensar, y en
-- insolvencia compensar es INEFICAZ DE PLENO DERECHO: el sistema lo IMPIDE, no lo advierte
-- ni se lo deja al admin con una referencia. La línea es la FECHA DE INICIO del proceso,
-- que es lo que significa `clientes.en_insolvencia_desde`.
--   (a) desde `en_insolvencia_desde` esta función no aplica nada (devuelve 0): ni la
--       venta a crédito, ni la anulación de un recibo. No hay regla manual de compensación.
--   (b) levantar la insolvencia tampoco aplica nada en ese momento: el saldo queda visible.
--       Después de levantarla vuelve la regla normal (ECM, opción 1): la siguiente venta a
--       crédito sí aplica el saldo, porque terminado el proceso las obligaciones anteriores se
--       extinguieron y compensar contra una nueva es legal.
--       🔴 LÍMITE CONOCIDO, lo cierra F4.13: nada impide levantar la marca sin que el proceso
--       haya terminado (o en liquidación, donde el saldo es del liquidador). F4.13 exigirá etapa
--       CUMPLIDO/TERMINADO documentada o CORRECCION_DE_ERROR con motivo, y en liquidación no se
--       levanta. Mientras tanto F4.12 vive solo en staging y sin mayorista real.
--   (c) lo aplicado automáticamente entre la fecha de inicio y el día en que se marcó se
--       lista para revisar (Cartera.estadoDeCuenta); revertirlo con rastro es F4.13.
--   · Devolver el saldo (egreso) sí procede con la cuenta en proceso: solo el admin.
--
-- 🔴 `fn_venta_a_credito` es el disparador de TODA venta a crédito:
--   · Guarda: el cuerpo vigente tiene que ser el de V72 (md5 de prosrc
--     aa7a27b0912fedfd7994ef856bf1b879, calculado sobre el fichero de V72 con el método
--     que da 0913c97c… para V71, medido por C en staging con md5(prosrc)).
--   · Cuerpo = V72 + la llamada a fn_aplicar_saldo_a_favor cuando la venta no se marcó.
--   · LaVentaDeSiempreTest: las 8 formas quedan idénticas; la 9 es la venta a crédito
--     de un cliente con saldo a favor.
--
-- IMPACTO: dos tablas nuevas vacías; dos columnas nulas y dos CHECK en
-- `cartera_aplicaciones`; un índice parcial sobre `debt_transactions (recibo_id)`
-- (tabla pequeña; bloquea escrituras mientras se construye); una vista; dos funciones.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       IS DISTINCT FROM 'aa7a27b0912fedfd7994ef856bf1b879' THEN
        RAISE EXCEPTION 'V74: fn_venta_a_credito no es la de V72 (md5 de prosrc %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'));
    END IF;
END $guarda$;

-- ── 1 · Las aplicaciones saben de dónde sale el saldo a favor ─────────
ALTER TABLE public.cartera_aplicaciones ADD COLUMN IF NOT EXISTS usuario_id BIGINT NULL REFERENCES public.users(id);
ALTER TABLE public.cartera_aplicaciones DROP CONSTRAINT IF EXISTS ck_aplicaciones_regla;
ALTER TABLE public.cartera_aplicaciones ADD CONSTRAINT ck_aplicaciones_regla CHECK (regla IN (
    'MAS_ANTIGUA_PRIMERO', 'ELEGIDA_POR_USUARIO',
    'SALDO_A_FAVOR_AUTOMATICO', 'DEVOLUCION_DE_SALDO_A_FAVOR'));
ALTER TABLE public.cartera_aplicaciones DROP CONSTRAINT IF EXISTS ck_aplicaciones_con_autor;
ALTER TABLE public.cartera_aplicaciones ADD CONSTRAINT ck_aplicaciones_con_autor CHECK (
    regla <> 'DEVOLUCION_DE_SALDO_A_FAVOR' OR usuario_id IS NOT NULL);
COMMENT ON COLUMN public.cartera_aplicaciones.usuario_id IS
    'DEVOLUCION_DE_SALDO_A_FAVOR: el admin que devolvio el saldo. V74, F4.12.';

-- El CREDIT de un recibo, por recibo: lo busca el saldo a favor en cada venta a crédito.
CREATE INDEX IF NOT EXISTS ix_debt_transactions_recibo
    ON public.debt_transactions (tenant_id, recibo_id) WHERE recibo_id IS NOT NULL;

-- ── 2 · El egreso: devolver el saldo a favor ──────────────────────────
CREATE TABLE IF NOT EXISTS public.contadores_de_egresos (
    tenant_id TEXT   NOT NULL,
    ultimo    BIGINT NOT NULL,
    CONSTRAINT pk_contadores_de_egresos PRIMARY KEY (tenant_id),
    CONSTRAINT ck_contadores_de_egresos CHECK (ultimo >= 1)
);

CREATE TABLE IF NOT EXISTS public.egresos_de_cartera (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         TEXT          NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    numero            BIGINT        NOT NULL,
    cliente_documento TEXT          NOT NULL,
    monto             NUMERIC(15,2) NOT NULL,
    medio             TEXT          NOT NULL,
    referencia_medio  TEXT          NULL,
    motivo            TEXT          NOT NULL,
    referencia        TEXT          NULL,
    debito_tx_id      VARCHAR(36)   NOT NULL REFERENCES public.debt_transactions (id),
    pagado_por        BIGINT        NOT NULL REFERENCES public.users (id),
    site_id           BIGINT        NULL,
    ocurrido_en       TIMESTAMPTZ   NOT NULL,
    registrado_en     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    idempotency_key   TEXT          NOT NULL,
    CONSTRAINT pk_egresos_de_cartera PRIMARY KEY (id),
    CONSTRAINT ux_egresos_numero UNIQUE (tenant_id, numero),
    CONSTRAINT ux_egresos_idempotencia UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ux_egresos_debito UNIQUE (debito_tx_id),
    CONSTRAINT ck_egresos_monto CHECK (monto > 0),
    CONSTRAINT ck_egresos_medio CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'BRE_B', 'QR', 'TARJETA', 'CHEQUE')),
    CONSTRAINT ck_egresos_motivo CHECK (motivo IN ('CLIENTE_LO_PIDIO', 'CIERRE_DE_CUENTA', 'DEVUELTO_AL_PROCESO')),
    CONSTRAINT ck_egresos_referencia_del_proceso CHECK (motivo <> 'DEVUELTO_AL_PROCESO'
        OR (referencia IS NOT NULL AND length(btrim(referencia)) > 0))
);
CREATE INDEX IF NOT EXISTS ix_egresos_cliente ON public.egresos_de_cartera (tenant_id, cliente_documento, ocurrido_en);
CREATE INDEX IF NOT EXISTS ix_egresos_cierre  ON public.egresos_de_cartera (tenant_id, medio, registrado_en);
COMMENT ON TABLE public.egresos_de_cartera IS
    'Devolucion de saldo a favor (F4.12): numero por negocio sin huecos, motivo cerrado, autor obligatorio. '
    'Su DEBIT del libro queda cubierto por aplicaciones DEVOLUCION_DE_SALDO_A_FAVOR. Solo anexa. V74.';

CREATE OR REPLACE FUNCTION public.fn_numero_de_egreso()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.contadores_de_egresos AS c (tenant_id, ultimo) VALUES (NEW.tenant_id, 1)
    ON CONFLICT (tenant_id) DO UPDATE SET ultimo = c.ultimo + 1
    RETURNING c.ultimo INTO NEW.numero;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_egresos_numero ON public.egresos_de_cartera;
CREATE TRIGGER trg_egresos_numero BEFORE INSERT ON public.egresos_de_cartera
    FOR EACH ROW EXECUTE FUNCTION public.fn_numero_de_egreso();

-- ── 3 · El saldo a favor, derivado ────────────────────────────────────
CREATE VIEW public.v_saldo_a_favor_por_recibo WITH (security_invoker = true) AS
SELECT r.tenant_id,
       r.id                                  AS recibo_id,
       k.id                                  AS credito_tx_id,
       r.cliente_documento,
       r.numero,
       r.ocurrido_en,
       r.monto,
       COALESCE(sum(a.monto), 0)             AS aplicado,
       r.monto - COALESCE(sum(a.monto), 0)   AS saldo_a_favor
  FROM public.recibos_de_caja r
  JOIN public.debt_transactions k ON k.tenant_id = r.tenant_id AND k.recibo_id = r.id AND k.type = 'CREDIT'
  LEFT JOIN public.cartera_aplicaciones a ON a.tenant_id = r.tenant_id AND a.recibo_id = r.id
 WHERE r.anula_recibo_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM public.recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id)
 GROUP BY r.tenant_id, r.id, k.id, r.cliente_documento, r.numero, r.ocurrido_en, r.monto
HAVING r.monto > COALESCE(sum(a.monto), 0);
COMMENT ON VIEW public.v_saldo_a_favor_por_recibo IS
    'Recibos no anulados con monto sin aplicar: el saldo a favor del cliente, por recibo, calculado al leer. V74, F4.12.';

-- ── 4 · Aplicar el saldo a favor ──────────────────────────────────────
CREATE OR REPLACE FUNCTION public.fn_aplicar_saldo_a_favor(p_tenant TEXT, p_documento TEXT)
RETURNS NUMERIC LANGUAGE plpgsql AS $$
DECLARE
    v_hoy        DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_total      NUMERIC := 0;
    v_queda_favor NUMERIC := 0;
    v_queda_fact NUMERIC;
    v_parte      NUMERIC;
    v_sin_favor  BOOLEAN := false;
    f RECORD;
    d RECORD;
    c_favor CURSOR FOR
        SELECT s.recibo_id, s.credito_tx_id, s.saldo_a_favor
          FROM public.v_saldo_a_favor_por_recibo s
         WHERE s.tenant_id = p_tenant AND s.cliente_documento = p_documento
         ORDER BY s.ocurrido_en, s.numero;
    c_facturas CURSOR FOR
        SELECT v.debito_tx_id, v.saldo
          FROM public.v_cartera_por_documento v
          JOIN public.debt_transactions t ON t.tenant_id = v.tenant_id AND t.id = v.debito_tx_id
         WHERE v.tenant_id = p_tenant AND v.cliente_documento = p_documento AND v.saldo > 0
         ORDER BY v.fecha, t.created_at, v.debito_tx_id;
BEGIN
    -- En insolvencia compensar es ineficaz de pleno derecho: desde la fecha de inicio no se cruza nada.
    IF EXISTS (
           SELECT 1 FROM public.clientes c
            WHERE c.tenant_id = p_tenant AND c.documento = p_documento AND c.en_insolvencia_desde <= v_hoy) THEN
        RETURN 0;
    END IF;

    OPEN c_favor;
    FETCH c_favor INTO f;
    IF NOT FOUND THEN
        CLOSE c_favor;
        RETURN 0;          -- lo normal: sin saldo a favor, una consulta indexada y fuera
    END IF;
    v_queda_favor := f.saldo_a_favor;

    -- La cuenta bloqueada: dos ventas o cobros del mismo cliente no aplican el mismo saldo dos veces.
    PERFORM 1 FROM public.accounts_receivable ar
      WHERE ar.tenant_id = p_tenant AND ar.customer_document = p_documento FOR UPDATE;

    OPEN c_facturas;
    LOOP
        FETCH c_facturas INTO d;
        EXIT WHEN NOT FOUND;
        v_queda_fact := d.saldo;
        WHILE v_queda_fact > 0 LOOP
            IF v_queda_favor <= 0 THEN
                FETCH c_favor INTO f;
                IF NOT FOUND THEN
                    v_sin_favor := true;
                    EXIT;
                END IF;
                v_queda_favor := f.saldo_a_favor;
            END IF;
            v_parte := LEAST(v_queda_favor, v_queda_fact);
            INSERT INTO public.cartera_aplicaciones
                (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
            VALUES (p_tenant, f.recibo_id, f.credito_tx_id, d.debito_tx_id, v_parte, 'SALDO_A_FAVOR_AUTOMATICO');
            v_total := v_total + v_parte;
            v_queda_favor := v_queda_favor - v_parte;
            v_queda_fact := v_queda_fact - v_parte;
        END LOOP;
        EXIT WHEN v_sin_favor;
    END LOOP;
    CLOSE c_facturas;
    CLOSE c_favor;
    RETURN v_total;
END $$;
COMMENT ON FUNCTION public.fn_aplicar_saldo_a_favor(TEXT, TEXT) IS
    'Aplica el saldo a favor del cliente a sus facturas vivas (recibo mas viejo sobre factura mas vieja), regla '
    'SALDO_A_FAVOR_AUTOMATICO. INVOKER: RLS aplica. Desde en_insolvencia_desde no cruza nada (compensar es ineficaz). V74.';

-- ── 5 · El disparador de la venta a crédito aplica el saldo ───────────
CREATE OR REPLACE FUNCTION public.fn_venta_a_credito()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_cuenta     public.accounts_receivable%ROWTYPE;
    v_hoy        DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_plazo      INTEGER;
    v_insolvente DATE;
    v_saldo      NUMERIC;
    v_mide_cupo  BOOLEAN;
    v_excede     BOOLEAN;
    v_marcar     BOOLEAN := false;
BEGIN
    IF NEW.cliente_documento IS NULL OR btrim(NEW.cliente_documento) = '' THEN
        RAISE EXCEPTION 'Una venta a credito necesita el documento del cliente.';
    END IF;

    SELECT c.plazo_dias, c.en_insolvencia_desde INTO v_plazo, v_insolvente
      FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id
       AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL AND v_insolvente <= v_hoy THEN
        -- V72 (F4.11): la venta que ya hizo una caja entra y se marca; la que no salió
        -- de una caja (API directa, panel, despacho de un pedido) se sigue rechazando.
        IF NEW.terminal_id IS NULL OR NEW.origen IS NOT DISTINCT FROM 'pedido' THEN
            RAISE EXCEPTION 'El cliente % esta en proceso de insolvencia desde el %: no se le vende a credito.',
                NEW.cliente_documento, to_char(v_insolvente, 'DD/MM/YYYY');
        END IF;
        v_marcar := true;
    END IF;

    -- V71 (F5.5): la venta que nace de despachar un pedido vence con el plazo PACTADO en
    -- el pedido, congelado en la captura; el del cliente pudo cambiar después (decisión
    -- de ECM). NULL = sin plazo pactado, 0 = contraentrega. Toda otra venta, como siempre.
    IF NEW.origen = 'pedido' THEN
        v_plazo := NEW.plazo_dias;
    END IF;

    SELECT * INTO v_cuenta FROM public.accounts_receivable
     WHERE customer_document = NEW.cliente_documento
       AND tenant_id = NEW.tenant_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El cliente % no tiene cuenta de cartera: abrela antes de venderle a credito.', NEW.cliente_documento;
    END IF;
    IF v_cuenta.status IN ('SUSPENDED', 'CLOSED') THEN
        RAISE EXCEPTION 'La cuenta de cartera del cliente % esta %: no admite mas credito.', NEW.cliente_documento,
            CASE v_cuenta.status WHEN 'SUSPENDED' THEN 'suspendida' ELSE 'cerrada' END;
    END IF;

    -- Plazo 0 explícito: se paga al entregar y no ocupa cupo. NULL (sin pactar) sí se mide.
    -- F5: la contraentrega entrará aquí.
    v_mide_cupo := v_plazo IS DISTINCT FROM 0;
    IF v_mide_cupo THEN
        -- El saldo, del libro (la cuenta está bloqueada: nadie escribe en medio).
        SELECT COALESCE(sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END), 0) INTO v_saldo
          FROM public.debt_transactions d
         WHERE d.tenant_id = NEW.tenant_id
           AND d.account_id = v_cuenta.id;
        -- AVISA, NO BLOQUEA: la venta entra y queda marcada.
        v_excede := v_saldo + COALESCE(NEW.total, 0) > v_cuenta.credit_limit;
    ELSE
        v_excede := false;
    END IF;

    INSERT INTO public.debt_transactions
        (id, tenant_id, account_id, amount, created_at, description, payment_method,
         reference, transaction_date, type, order_uuid, excede_cupo, registrado_por, vence_el)
    VALUES (gen_random_uuid()::text, NEW.tenant_id, v_cuenta.id, COALESCE(NEW.total, 0), now(),
            'Venta a credito', NULL, NEW.uuid_id::text, v_hoy, 'DEBIT',
            NEW.uuid_id, v_excede, 'sistema:venta', v_hoy + v_plazo);

    UPDATE public.accounts_receivable
       SET total_debt = v_cuenta.total_debt + COALESCE(NEW.total, 0),
           last_transaction_date = v_hoy,
           status = 'ACTIVE',
           updated_at = now()
     WHERE id = v_cuenta.id;

    IF v_marcar THEN
        INSERT INTO public.ventas_a_insolvente
            (tenant_id, order_uuid, cliente_documento, en_insolvencia_desde, total, terminal_id, operado_por, vendedor_id, ocurrido_en)
        VALUES (NEW.tenant_id, NEW.uuid_id, NEW.cliente_documento, v_insolvente, COALESCE(NEW.total, 0),
                NEW.terminal_id, NEW.created_by, NEW.vendedor_id, NEW.ocurrido_en);
    ELSE
        -- V74 (F4.12): si el cliente tiene saldo a favor, se aplica solo a esta factura (y a las
        -- vivas más viejas). A un cliente en insolvencia no: la función no cruza nada. Vuelve
        -- enseguida si no hay saldo a favor.
        PERFORM public.fn_aplicar_saldo_a_favor(NEW.tenant_id, NEW.cliente_documento);
    END IF;

    NEW.excede_cupo := v_excede;
    RETURN NEW;
END $function$;
-- ── 6 · Aislamiento y permisos ────────────────────────────────────────
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['egresos_de_cartera', 'contadores_de_egresos'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON public.%I', t, t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON public.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
    REVOKE EXECUTE ON FUNCTION public.fn_aplicar_saldo_a_favor(TEXT, TEXT) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT ON public.egresos_de_cartera TO app_user;               -- solo anexa
        GRANT SELECT, INSERT, UPDATE ON public.contadores_de_egresos TO app_user;    -- el UPSERT del número
        GRANT SELECT ON public.v_saldo_a_favor_por_recibo TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_aplicar_saldo_a_favor(TEXT, TEXT) TO app_user;
    END IF;
END
$rls$;

-- ── 7 · Cierre, por comportamiento (los dos escenarios aprobados por ECM) ──
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v74_a__';
    b CONSTANT TEXT := '__prueba_v74_b__';
    v_hoy   DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user  BIGINT;
    v_term  UUID := gen_random_uuid();
    v_r     UUID;
    v_r2    UUID;
    v_venta UUID := gen_random_uuid();
    v_ins   UUID := gen_random_uuid();
    v_egreso_tx TEXT := gen_random_uuid()::text;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V74 A', 'basico'), (b, 'Prueba V74 B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v74@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.terminals (id, tenant_id) VALUES (v_term, a);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v74-i', 'Escenario i', 8, 'v74'), (a, 'v74-ii', 'Escenario ii', 8, 'v74'), (a, 'v74-ins', 'Insolvente', 8, 'v74');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v74-c-i',   a, now(), 1000000, 'v74-i',   'Escenario i',  'ACTIVE', 0, now()),
        ('v74-c-ii',  a, now(), 1000000, 'v74-ii',  'Escenario ii', 'ACTIVE', 0, now()),
        ('v74-c-ins', a, now(), 1000000, 'v74-ins', 'Insolvente',   'ACTIVE', 0, now());

    -- (i) Debe 100.000, abona 120.000 (factura pagada y 20.000 a favor); una venta de 50.000 queda debiendo 30.000.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES ('v74-d-i', a, 'v74-c-i', 100000, now(), 'x', v_hoy - 20, 'DEBIT', v_hoy - 12);
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v74-i', 120000, 'EFECTIVO', now(), 'v74-r-i') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v74-k-i', a, 'v74-c-i', 120000, now(), 'x', v_hoy, 'CREDIT', v_r);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r, 'v74-k-i', 'v74-d-i', 100000, 'MAS_ANTIGUA_PRIMERO');
    IF (SELECT sum(saldo_a_favor) FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND cliente_documento = 'v74-i') <> 20000 THEN
        RAISE EXCEPTION 'V74 (i): el abono de 120.000 sobre 100.000 no dejo 20.000 a favor';
    END IF;
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
    VALUES (v_venta, a, 'pagado', 'CREDITO', 'v74-i', 50000, 'caja');
    IF (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND order_uuid = v_venta) <> 30000
       OR EXISTS (SELECT 1 FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND cliente_documento = 'v74-i')
       OR (SELECT count(*) FROM public.cartera_aplicaciones WHERE tenant_id = a AND regla = 'SALDO_A_FAVOR_AUTOMATICO' AND monto = 20000) <> 1 THEN
        RAISE EXCEPTION 'V74 (i): la venta de 50.000 no quedo debiendo 30.000 con 20.000 aplicados solos';
    END IF;

    -- (ii) Abona 120.000 sobre 100.000 y se le devuelven 20.000 en efectivo: saldo a favor 0, libro en 0.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES ('v74-d-ii', a, 'v74-c-ii', 100000, now(), 'x', v_hoy - 20, 'DEBIT', v_hoy - 12);
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v74-ii', 120000, 'EFECTIVO', now(), 'v74-r-ii') RETURNING id INTO v_r2;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v74-k-ii', a, 'v74-c-ii', 120000, now(), 'x', v_hoy, 'CREDIT', v_r2);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r2, 'v74-k-ii', 'v74-d-ii', 100000, 'MAS_ANTIGUA_PRIMERO');
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type)
    VALUES (v_egreso_tx, a, 'v74-c-ii', 20000, now(), 'Devolucion de saldo a favor', v_hoy, 'DEBIT');
    INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v74-ii', 20000, 'EFECTIVO', 'CLIENTE_LO_PIDIO', v_egreso_tx, v_user, now(), 'v74-e-ii');
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, usuario_id)
    VALUES (a, v_r2, 'v74-k-ii', v_egreso_tx, 20000, 'DEVOLUCION_DE_SALDO_A_FAVOR', v_user);
    IF EXISTS (SELECT 1 FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND cliente_documento = 'v74-ii')
       OR (SELECT sum(CASE WHEN type = 'DEBIT' THEN amount ELSE -amount END) FROM public.debt_transactions WHERE account_id = 'v74-c-ii') <> 0
       OR EXISTS (SELECT 1 FROM public.v_cartera_por_documento WHERE tenant_id = a AND cliente_documento = 'v74-ii' AND saldo > 0)
       OR (SELECT numero FROM public.egresos_de_cartera WHERE tenant_id = a) <> 1 THEN
        RAISE EXCEPTION 'V74 (ii): devolver 20.000 no dejo saldo a favor 0, libro 0, sin facturas vivas y egreso numero 1';
    END IF;

    -- Insolvencia: el cliente con saldo a favor compra desde la caja (queda marcada) y NADA se cruza.
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v74-ins', 15000, 'EFECTIVO', now(), 'v74-r-ins') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v74-k-ins', a, 'v74-c-ins', 15000, now(), 'x', v_hoy, 'CREDIT', v_r);
    UPDATE public.clientes SET en_insolvencia_desde = v_hoy WHERE tenant_id = a AND documento = 'v74-ins';
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, terminal_id)
    VALUES (v_ins, a, 'pagado', 'CREDITO', 'v74-ins', 40000, 'caja', v_term);
    IF (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND order_uuid = v_ins) <> 40000
       OR (SELECT saldo_a_favor FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND recibo_id = v_r) <> 15000
       OR public.fn_aplicar_saldo_a_favor(a, 'v74-ins') <> 0
       OR EXISTS (SELECT 1 FROM public.cartera_aplicaciones WHERE tenant_id = a AND recibo_id = v_r) THEN
        RAISE EXCEPTION 'V74: el saldo a favor de un cliente en insolvencia se cruzo con su cartera';
    END IF;
    -- Una regla de compensación no existe: el CHECK la rechaza.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, usuario_id)
        SELECT a, v_r, 'v74-k-ins', debito_tx_id, 1, 'COMPENSACION_AUTORIZADA', v_user
          FROM public.v_cartera_por_documento WHERE tenant_id = a AND order_uuid = v_ins;
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V74: una aplicacion COMPENSACION_AUTORIZADA entro'; END IF;

    -- Catálogo y autor.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por, ocurrido_en, idempotency_key)
        VALUES (a, 0, 'v74-ii', 1, 'EFECTIVO', 'DEVUELTO_AL_PROCESO', 'v74-d-i', v_user, now(), 'v74-e-sin-ref');
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V74: un egreso al proceso sin referencia entro'; END IF;

    -- Permisos: el egreso solo anexa; la función no la ejecuta PUBLIC; el disparador sigue INVOKER.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.egresos_de_cartera', 'UPDATE')
            OR has_table_privilege('app_user', 'public.egresos_de_cartera', 'DELETE')) THEN
        RAISE EXCEPTION 'V74: app_user puede modificar o borrar egresos';
    END IF;
    IF has_function_privilege('public', 'public.fn_aplicar_saldo_a_favor(text, text)', 'EXECUTE') THEN
        RAISE EXCEPTION 'V74: PUBLIC puede ejecutar fn_aplicar_saldo_a_favor';
    END IF;
    IF (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       OR (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_aplicar_saldo_a_favor(text, text)')) THEN
        RAISE EXCEPTION 'V74: una funcion de cartera quedo SECURITY DEFINER';
    END IF;

    -- Limpieza: lo insertado y lo que provocaron los disparadores.
    DELETE FROM public.cartera_aplicaciones  WHERE tenant_id IN (a, b);
    DELETE FROM public.egresos_de_cartera    WHERE tenant_id IN (a, b);
    DELETE FROM public.contadores_de_egresos WHERE tenant_id IN (a, b);
    DELETE FROM public.ventas_a_insolvente   WHERE tenant_id IN (a, b);
    DELETE FROM public.debt_transactions     WHERE tenant_id IN (a, b);
    DELETE FROM public.recibos_de_caja       WHERE tenant_id IN (a, b);
    DELETE FROM public.contadores_de_recibos WHERE tenant_id IN (a, b);
    DELETE FROM public.orders                WHERE tenant_id IN (a, b);
    DELETE FROM public.tenant_order_counters WHERE tenant_id IN (a, b);
    DELETE FROM public.terminals             WHERE tenant_id IN (a, b);
    DELETE FROM public.sites                 WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes_eventos      WHERE tenant_id IN (a, b);
    DELETE FROM public.accounts_receivable   WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes              WHERE tenant_id IN (a, b);
    DELETE FROM public.users                 WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants               WHERE id IN (a, b);

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v74%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v74%') THEN
        RAISE EXCEPTION 'V74: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V74: (i) 120.000 sobre 100.000 deja 20.000 a favor y la venta de 50.000 debe 30.000; (ii) devolver 20.000 deja favor 0 y libro 0; insolvente sin cruce; sin regla de compensacion; egreso solo anexa; INVOKER; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- Restaurar fn_venta_a_credito con el cuerpo de V72 (md5 de prosrc aa7a27b0912fedfd7994ef856bf1b879);
-- DROP FUNCTION public.fn_aplicar_saldo_a_favor(TEXT, TEXT); DROP VIEW public.v_saldo_a_favor_por_recibo;
-- (antes, decidir qué pasa con los egresos y las aplicaciones de saldo a favor: son dinero que se movió)
-- DROP TABLE public.egresos_de_cartera; DROP TABLE public.contadores_de_egresos; DROP FUNCTION public.fn_numero_de_egreso();
-- CHECK de cartera_aplicaciones: el de V64; DROP COLUMN usuario_id; DROP INDEX ix_debt_transactions_recibo.
