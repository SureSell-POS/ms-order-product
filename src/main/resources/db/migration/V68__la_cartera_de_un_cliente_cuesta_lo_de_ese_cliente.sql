-- =====================================================================
-- V68 -- La cartera de un cliente cuesta lo de ese cliente, no lo del negocio.
--
-- Redefine `v_cartera_por_documento` y `v_cartera_por_cliente` (V64) con el
-- MISMO resultado y otro plan. Nada más: ni columnas, ni reglas, ni permisos.
--
-- ── El defecto ────────────────────────────────────────────────────────
--
-- En V64, `v_cartera_por_documento` agregaba `cartera_aplicaciones` en un
-- subquery con GROUP BY, y `v_cartera_por_cliente` agrupaba sobre esa vista. Un
-- filtro por cliente o por cuenta no llegaba a ninguno de los dos: la ficha de UN
-- cliente leía todas las facturas y todas las aplicaciones del negocio.
--
-- Medido en un banco de pruebas LOCAL con datos SINTÉTICOS (CostoDeLaCarteraTest:
-- 2.000 clientes, 40.000 facturas, 10.000 recibos aplicados, y otro negocio igual
-- al lado; no es un negocio real, en staging hay una cuenta): el resumen de un
-- cliente tardaba 34 ms leyendo las 40.000 facturas, y crecía en línea con el
-- tamaño del negocio. En staging no se ve; en un mayorista grande, sí.
--
-- ── El arreglo ────────────────────────────────────────────────────────
--
-- `v_cartera_por_documento` hace UNA sola agregación (facturas + aplicaciones +
-- anulaciones en un GROUP BY por factura), sin subconsultas agregadas dentro: un
-- filtro por negocio, cuenta o cliente —columnas agrupadas— baja hasta las tablas.
-- `v_cartera_por_cliente` agrupa sobre ella y cruza también por
-- `cliente_documento` (redundante, pero es lo que deja bajar el filtro por cliente
-- a la vista de dentro). Así el planificador ELIGE: índices para un cliente
-- (`ux_ar_tenant_documento`, `ix_debt_transactions_cuenta`, `ix_aplicaciones_debito`)
-- y hash para todo el negocio.
--
-- Se probó antes con LATERAL por factura y por cuenta: la ficha de un cliente
-- quedaba igual de rápida, pero la lista del negocio pasaba de 67 a 268 ms, porque
-- LATERAL obliga a buscar factura por factura. Se descartó.
--
-- Antes → después, mismo banco LOCAL y SINTÉTICO (EXPLAIN ANALYZE):
--   documentos de un cliente (estado de cuenta)   7,4 ms → 0,5 ms
--   resumen de un cliente (ficha)                 34 ms  → 0,3 ms
--   facturas vivas de una cuenta (al cobrar)      7,2 ms → 0,2 ms
--   lista de clientes del negocio (2.000)         65 ms  → 90 ms (todo el negocio en
--     los dos casos). NO es el work_mem: medido con 3500kB (el de staging, SHOW
--     work_mem del 14/09) y con 64MB, V64 da ~65 ms en los dos y V68 ~90 ms en los
--     dos. Es estructural: V68 agrupa las 40.000 facturas con sus aplicaciones en un
--     solo GROUP BY; V64 agrupaba solo las 10.000 aplicaciones. Se acepta: la lista
--     se carga una vez por pantalla y la ficha de un cliente, muchas; y la lista sigue
--     costando lo del negocio, como antes.
--
-- ── El control: resultado idéntico, fila por fila ─────────────────────
--
-- Antes de tocar nada se siembra un negocio de prueba con los casos raros (cuenta
-- sin aplicaciones, cuenta sin facturas, recibo anulado, abono repartido en varias
-- facturas, factura sin plazo, factura pagada del todo) y se copia lo que
-- devuelven las vistas de V64 para TODOS los negocios (también los reales de la
-- base donde corre). Después de redefinirlas, las dos copias tienen que coincidir
-- en las dos direcciones (EXCEPT), o la migración falla y no queda nada. `now()`
-- es el mismo en toda la transacción, así que la edad no cambia entre las dos.
--
-- 🔴 QUIEN VUELVA A TOCAR ESTAS VISTAS: `CREATE OR REPLACE VIEW` borra en silencio
-- `security_invoker` y la vista pasa a correr como su dueño (RLS deja de aplicar).
-- DROP + CREATE ... WITH (security_invoker = true), y comprobar `reloptions`.
--
-- Dependencias medidas en staging el 2026-09-14 (pg_depend): solo
-- `v_cartera_por_cliente` depende de `v_cartera_por_documento`. El inventario las
-- lee por consulta, no depende de ellas. Si aparece otra, la migración se niega
-- en vez de arrastrarla con CASCADE.
--
-- IMPACTO: dos vistas redefinidas en la misma transacción; sin DDL de tablas.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Nada más depende de estas vistas ────────────────────────────
DO $dependencias$
DECLARE v_otras TEXT;
BEGIN
    SELECT string_agg(DISTINCT dep.relname, ', ') INTO v_otras
      FROM pg_depend d
      JOIN pg_rewrite r ON r.oid = d.objid
      JOIN pg_class dep ON dep.oid = r.ev_class
      JOIN pg_class v ON v.oid = d.refobjid
      JOIN pg_namespace n ON n.oid = v.relnamespace
     WHERE n.nspname = 'public'
       AND v.relname IN ('v_cartera_por_documento', 'v_cartera_por_cliente')
       AND dep.oid <> v.oid
       AND dep.relname <> 'v_cartera_por_cliente';
    IF v_otras IS NOT NULL THEN
        RAISE EXCEPTION 'V68: hay objetos que dependen de las vistas de cartera (%): no se arrastran con CASCADE, revisar a mano', v_otras;
    END IF;
END
$dependencias$;

-- ── 1 · Los casos raros, sembrados antes de tocar nada ──────────────
DO $siembra$
DECLARE
    a CONSTANT TEXT := '__prueba_v68_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_r1 UUID; v_r2 UUID; v_r3 UUID; v_anula UUID;
BEGIN
    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V68', 'basico');
    PERFORM set_config('app.tenant_id', a, true);
    INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES
        (a, 'v68-sin-aplicaciones', 'Sin aplicaciones', 'v68'),
        (a, 'v68-sin-facturas', 'Sin facturas', 'v68'),
        (a, 'v68-varias', 'Abono en varias', 'v68');
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                     status, total_debt, updated_at) VALUES
        ('v68-c1', a, now(), 50000, 'v68-sin-aplicaciones', 'Sin aplicaciones', 'ACTIVE', 0, now()),
        ('v68-c2', a, now(), 0,     'v68-sin-facturas',     'Sin facturas',     'ACTIVE', 0, now()),
        ('v68-c3', a, now(), 10000, 'v68-varias',           'Abono en varias',  'ACTIVE', 0, now()),
        ('v68-c4', a, now(), 0,     'v68-sin-ficha',        'Cuenta sin ficha', 'ACTIVE', 0, now());
    -- Sin aplicaciones: una vencida hace 100 días, una corriente y una sin plazo.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) VALUES
        ('v68-d1', a, 'v68-c1', 30000, now(), 'x', v_hoy - 130, 'DEBIT', v_hoy - 100),
        ('v68-d2', a, 'v68-c1', 20000, now(), 'x', v_hoy - 2,   'DEBIT', v_hoy + 20),
        ('v68-d3', a, 'v68-c1', 7000,  now(), 'x', v_hoy - 400, 'DEBIT', NULL),
        -- Abono de 45.000 repartido en tres facturas: una queda pagada del todo, otra parcial.
        ('v68-d4', a, 'v68-c3', 25000, now(), 'x', v_hoy - 60,  'DEBIT', v_hoy - 30),
        ('v68-d5', a, 'v68-c3', 15000, now(), 'x', v_hoy - 40,  'DEBIT', v_hoy - 10),
        ('v68-d6', a, 'v68-c3', 30000, now(), 'x', v_hoy - 5,   'DEBIT', v_hoy + 25),
        ('v68-d7', a, 'v68-c4', 9000,  now(), 'x', v_hoy - 1,   'DEBIT', v_hoy + 7);
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v68-varias', 45000, 'EFECTIVO', now(), 'v68-r1') RETURNING id INTO v_r1;
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v68-k1', a, 'v68-c3', 45000, now(), 'x', v_hoy, 'CREDIT', v_r1);
    INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla) VALUES
        (a, v_r1, 'v68-k1', 'v68-d4', 25000, 'MAS_ANTIGUA_PRIMERO'),
        (a, v_r1, 'v68-k1', 'v68-d5', 15000, 'MAS_ANTIGUA_PRIMERO'),
        (a, v_r1, 'v68-k1', 'v68-d6', 5000,  'MAS_ANTIGUA_PRIMERO');
    -- Un segundo recibo aplicado a la factura parcial y ANULADO: su aplicación no cuenta.
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v68-varias', 8000, 'CHEQUE', now(), 'v68-r2') RETURNING id INTO v_r2;
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v68-k2', a, 'v68-c3', 8000, now(), 'x', v_hoy, 'CREDIT', v_r2);
    INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r2, 'v68-k2', 'v68-d6', 8000, 'ELEGIDA_POR_USUARIO');
    INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key,
                                 anula_recibo_id, motivo_anulacion)
    VALUES (a, 0, 'v68-varias', 8000, 'CHEQUE', now(), 'v68-anula', v_r2, 'CHEQUE_DEVUELTO') RETURNING id INTO v_anula;
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v68-k3', a, 'v68-c3', 8000, now(), 'x', v_hoy, 'DEBIT', v_anula);
    PERFORM set_config('app.tenant_id', '', true);
END
$siembra$;

-- ── 2 · Lo que devuelven hoy (V64), para todos los negocios ─────────
CREATE TEMP TABLE v68_antes_documento ON COMMIT DROP AS SELECT * FROM public.v_cartera_por_documento;
CREATE TEMP TABLE v68_antes_cliente   ON COMMIT DROP AS SELECT * FROM public.v_cartera_por_cliente;

-- ── 3 · Las vistas nuevas ────────────────────────────────────────────
DROP VIEW public.v_cartera_por_cliente;
DROP VIEW public.v_cartera_por_documento;

CREATE VIEW public.v_cartera_por_documento WITH (security_invoker = true) AS
SELECT g.tenant_id,
       g.debito_tx_id,
       g.account_id,
       g.cliente_documento,
       g.order_uuid,
       g.fecha,
       g.monto,
       g.vence_el,
       g.aplicado,
       g.monto - g.aplicado                   AS saldo,
       CASE WHEN g.vence_el IS NOT NULL AND g.monto - g.aplicado > 0
            THEN GREATEST(0, g.hoy - g.vence_el) END AS dias_vencido,
       CASE
           WHEN g.monto - g.aplicado <= 0 THEN 'PAGADA'
           WHEN g.vence_el IS NULL THEN 'SIN_PLAZO_PACTADO'
           WHEN g.hoy <= g.vence_el THEN 'CORRIENTE'
           WHEN g.hoy - g.vence_el <= 30  THEN '1_30'
           WHEN g.hoy - g.vence_el <= 60  THEN '31_60'
           WHEN g.hoy - g.vence_el <= 90  THEN '61_90'
           WHEN g.hoy - g.vence_el <= 180 THEN '91_180'
           WHEN g.hoy - g.vence_el <= 360 THEN '181_360'
           ELSE 'MAS_360'
       END                                    AS edad
  FROM (
        -- Una sola agregación, sin subconsultas agregadas dentro: los filtros por negocio,
        -- cuenta o cliente (columnas agrupadas) bajan hasta las tablas, y el planificador
        -- elige índice para un cliente o hash para todo el negocio.
        SELECT d.tenant_id,
               d.id                                             AS debito_tx_id,
               d.account_id,
               ar.customer_document                             AS cliente_documento,
               d.order_uuid,
               d.transaction_date                               AS fecha,
               d.amount                                         AS monto,
               d.vence_el,
               h.hoy,
               -- Lo de un recibo anulado no cuenta (una anulación por recibo: ux_recibos_una_anulacion).
               COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0) AS aplicado
          FROM public.debt_transactions d
          JOIN public.accounts_receivable ar ON ar.id = d.account_id AND ar.tenant_id = d.tenant_id
          CROSS JOIN (SELECT (now() AT TIME ZONE 'America/Bogota')::date AS hoy) h
          LEFT JOIN public.cartera_aplicaciones a ON a.tenant_id = d.tenant_id AND a.debito_tx_id = d.id
          LEFT JOIN public.recibos_de_caja x ON x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id
         WHERE d.type = 'DEBIT'
           AND d.recibo_id IS NULL      -- el DEBIT de una anulación de recibo no es una factura
         GROUP BY d.tenant_id, d.id, d.account_id, ar.customer_document, d.order_uuid, d.transaction_date,
                  d.amount, d.vence_el, h.hoy
       ) g;

COMMENT ON VIEW public.v_cartera_por_documento IS
    'Cada factura a credito con lo aplicado, el saldo y la edad calculados al leer (sin guardarlos). '
    'V64; V68: una sola agregacion, para que el filtro por cliente o cuenta llegue a las tablas.';

CREATE VIEW public.v_cartera_por_cliente WITH (security_invoker = true) AS
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
  -- `cliente_documento` en el cruce, aunque sobre: es lo que deja bajar el filtro por cliente a la vista.
  LEFT JOIN public.v_cartera_por_documento v
         ON v.account_id = ar.id AND v.tenant_id = ar.tenant_id AND v.cliente_documento = ar.customer_document
  LEFT JOIN public.clientes c ON c.tenant_id = ar.tenant_id AND c.documento = ar.customer_document
 GROUP BY ar.tenant_id, ar.id, ar.customer_document, ar.customer_name, ar.credit_limit, ar.total_debt, ar.status,
          c.en_insolvencia_desde, c.vendedor_id;

COMMENT ON VIEW public.v_cartera_por_cliente IS
    'Resumen por cliente: saldo, vencido, factura mas vieja, cupo, insolvencia y vendedor. Al leer. '
    'V64; V68: agrupa sobre la vista por documento ya aplanable, para que la ficha de un cliente no lea todo el negocio.';

DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.v_cartera_por_documento TO app_user;
        GRANT SELECT ON public.v_cartera_por_cliente   TO app_user;
    END IF;
END
$permisos$;

-- =====================================================================
-- La comprobación: mismas filas, en las dos direcciones, para todos los negocios.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v68_a__';
    n_doc_antes BIGINT; n_doc_despues BIGINT; n_cli_antes BIGINT; n_cli_despues BIGINT;
    faltan BIGINT; sobran BIGINT;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
                WHERE ns.nspname = 'public' AND c.relname IN ('v_cartera_por_documento', 'v_cartera_por_cliente')
                  AND NOT COALESCE(c.reloptions @> ARRAY['security_invoker=true'], false)) THEN
        RAISE EXCEPTION 'V68: una vista de cartera quedo sin security_invoker';
    END IF;

    -- Control positivo: la copia de antes tiene los casos raros sembrados.
    SELECT count(*) INTO n_doc_antes FROM v68_antes_documento WHERE tenant_id = a;
    SELECT count(*) INTO n_cli_antes FROM v68_antes_cliente WHERE tenant_id = a;
    IF n_doc_antes <> 7 OR n_cli_antes <> 4 THEN
        RAISE EXCEPTION 'V68: la copia de V64 no tiene los casos sembrados (% documentos, % clientes; debian ser 7 y 4)',
            n_doc_antes, n_cli_antes;
    END IF;
    -- Y dicen lo que tienen que decir (si no, comparar dos copias iguales de algo mal no probaría nada).
    IF (SELECT saldo FROM v68_antes_documento WHERE debito_tx_id = 'v68-d4') <> 0
       OR (SELECT saldo FROM v68_antes_documento WHERE debito_tx_id = 'v68-d6') <> 25000
       OR (SELECT edad FROM v68_antes_documento WHERE debito_tx_id = 'v68-d3') <> 'SIN_PLAZO_PACTADO'
       OR (SELECT saldo FROM v68_antes_cliente WHERE tenant_id = a AND cliente_documento = 'v68-sin-facturas') <> 0
       OR (SELECT excede_cupo FROM v68_antes_cliente WHERE tenant_id = a AND cliente_documento = 'v68-varias') IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'V68: los casos sembrados no dan lo esperado en V64; la comparacion no probaria nada';
    END IF;

    SELECT count(*) INTO faltan FROM (SELECT * FROM v68_antes_documento EXCEPT ALL SELECT * FROM public.v_cartera_por_documento) x;
    SELECT count(*) INTO sobran FROM (SELECT * FROM public.v_cartera_por_documento EXCEPT ALL SELECT * FROM v68_antes_documento) x;
    SELECT count(*) INTO n_doc_antes FROM v68_antes_documento;
    SELECT count(*) INTO n_doc_despues FROM public.v_cartera_por_documento;
    IF faltan <> 0 OR sobran <> 0 THEN
        RAISE EXCEPTION 'V68: v_cartera_por_documento cambio de resultado: % filas de antes no estan, % nuevas (de % y %)',
            faltan, sobran, n_doc_antes, n_doc_despues;
    END IF;
    SELECT count(*) INTO faltan FROM (SELECT * FROM v68_antes_cliente EXCEPT ALL SELECT * FROM public.v_cartera_por_cliente) x;
    SELECT count(*) INTO sobran FROM (SELECT * FROM public.v_cartera_por_cliente EXCEPT ALL SELECT * FROM v68_antes_cliente) x;
    SELECT count(*) INTO n_cli_antes FROM v68_antes_cliente;
    SELECT count(*) INTO n_cli_despues FROM public.v_cartera_por_cliente;
    IF faltan <> 0 OR sobran <> 0 THEN
        RAISE EXCEPTION 'V68: v_cartera_por_cliente cambio de resultado: % filas de antes no estan, % nuevas (de % y %)',
            faltan, sobran, n_cli_antes, n_cli_despues;
    END IF;

    -- Limpieza del negocio de prueba y barrido de todas las tablas con tenant_id.
    DELETE FROM cartera_aplicaciones  WHERE tenant_id = a;
    DELETE FROM debt_transactions     WHERE tenant_id = a;
    DELETE FROM recibos_de_caja       WHERE tenant_id = a AND anula_recibo_id IS NOT NULL;
    DELETE FROM recibos_de_caja       WHERE tenant_id = a;
    DELETE FROM contadores_de_recibos WHERE tenant_id = a;
    DELETE FROM clientes_eventos      WHERE tenant_id = a;
    DELETE FROM accounts_receivable   WHERE tenant_id = a;
    DELETE FROM clientes              WHERE tenant_id = a;
    DELETE FROM tenants               WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text = $1', t.s, t.tn) INTO n USING a;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V68: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V68: mismas filas antes y despues (% documentos, % clientes, todos los negocios de esta base); security_invoker en las dos.',
        n_doc_despues - 7, n_cli_despues - 4;
END
$cierre$;

-- =====================================================================
-- DOWN: volver a las definiciones de V64 (mismo resultado, plan que lee todo el negocio).
-- =====================================================================
