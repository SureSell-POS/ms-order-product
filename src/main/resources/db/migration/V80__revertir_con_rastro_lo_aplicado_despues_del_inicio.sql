-- =====================================================================
-- V80 -- Revertir con rastro lo aplicado después del inicio (plan de mayoristas F4.13b).
--
-- Diseño: docs/planes/DISENO-F4-13B-REVERSION-CON-RASTRO.md, APROBADO por ECM el 2026-09-15 (D1–D5); textos §B13b.
--
-- ── La regla ────────────────────────────────────────────────────────
--   · El admin revierte una aplicación SALDO_A_FAVOR_AUTOMATICO ocurrida en o después del CORTE del proceso en curso
--     (INICIO, ACUERDO_CONFIRMADO o LIQUIDACION) y con su recibo sin anular: compensar en un proceso es ineficaz.
--   · Solo anexa: `cartera_aplicaciones_revertidas`, una por aplicación, con motivo, referencia y autor. La escribe solo
--     `fn_revertir_aplicacion` (SECURITY DEFINER). No se deshace una reversión en este corte.
--   · Lo revertido deja de contar: la factura vuelve a deber y el saldo a favor vuelve (congelado por V74 y V76).
--
-- ── Lo que se toca, y cómo ──────────────────────────────────────────
--   · `v_cartera_por_documento` y `v_cartera_por_cliente` (V68) y `v_saldo_a_favor_por_recibo` (V74): DROP + CREATE
--     WITH (security_invoker = true), nunca CREATE OR REPLACE VIEW (borra security_invoker en silencio). Mismo texto que
--     V68/V74 más el LEFT JOIN a revertidas por clave primaria. Se copia lo que devuelven ANTES y, recreadas, tienen que
--     devolver lo MISMO en las dos direcciones (sin reversiones, nada cambia), como hizo V68.
--   · `fn_deuda_a_fecha` (V75): tampoco cuenta una reversión registrada antes del corte. Guarda por md5 de V75
--     (90e849a47095d2818d6d794a9f625ec5, calculado sobre el fichero con el método que da 0107b1c3… para V77, medido en
--     staging por C). Las fotos existentes no cambian: sin reversiones, la función devuelve lo mismo.
--   · No se tocan fn_venta_a_credito (0107b1c3…), fn_aplicar_saldo_a_favor (V79), total_debt ni el cierre de caja.
--
-- IMPACTO: una tabla nueva vacía, tres vistas recreadas en la misma transacción, una función redefinida y otra nueva.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_deuda_a_fecha(text,text,timestamptz)'))
       IS DISTINCT FROM '90e849a47095d2818d6d794a9f625ec5' THEN
        RAISE EXCEPTION 'V80: fn_deuda_a_fecha no es la de V75 (md5 de prosrc %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_deuda_a_fecha(text,text,timestamptz)'));
    END IF;
    IF EXISTS (SELECT 1 FROM pg_depend dp JOIN pg_rewrite rw ON rw.oid = dp.objid JOIN pg_class dep ON dep.oid = rw.ev_class
                WHERE dp.refobjid IN ('public.v_cartera_por_documento'::regclass, 'public.v_cartera_por_cliente'::regclass,
                                      'public.v_saldo_a_favor_por_recibo'::regclass)
                  AND dep.oid NOT IN ('public.v_cartera_por_documento'::regclass, 'public.v_cartera_por_cliente'::regclass,
                                      'public.v_saldo_a_favor_por_recibo'::regclass)) THEN
        RAISE EXCEPTION 'V80: algo nuevo depende de las vistas de cartera; no se arrastra con CASCADE, hay que mirarlo antes';
    END IF;
END $guarda$;

-- ── 1 · El rastro ────────────────────────────────────────────────────
CREATE TABLE public.cartera_aplicaciones_revertidas (
    aplicacion_id UUID        NOT NULL REFERENCES public.cartera_aplicaciones(id),
    tenant_id     TEXT        NOT NULL,
    proceso_id    UUID        NOT NULL,
    motivo        TEXT        NOT NULL,
    referencia    TEXT        NULL,
    revertida_por BIGINT      NOT NULL REFERENCES public.users(id),
    revertida_en  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_cartera_aplicaciones_revertidas PRIMARY KEY (aplicacion_id),
    CONSTRAINT fk_revertidas_proceso FOREIGN KEY (tenant_id, proceso_id) REFERENCES public.insolvencia_procesos (tenant_id, id),
    CONSTRAINT ck_revertidas_motivo CHECK (length(btrim(motivo)) > 0)
);
CREATE INDEX ix_revertidas_proceso ON public.cartera_aplicaciones_revertidas (tenant_id, proceso_id);
COMMENT ON TABLE public.cartera_aplicaciones_revertidas IS
    'F4.13b: aplicaciones automaticas de saldo a favor revertidas con rastro (ineficaces en un proceso). Solo anexa; la '
    'escribe fn_revertir_aplicacion. Las vistas de cartera y de saldo a favor dejan de contarlas. V80.';

-- ── 2 · Lo que devuelven hoy, para todos los negocios ───────────────
CREATE TEMP TABLE v80_antes_documento ON COMMIT DROP AS SELECT * FROM public.v_cartera_por_documento;
CREATE TEMP TABLE v80_antes_cliente   ON COMMIT DROP AS SELECT * FROM public.v_cartera_por_cliente;
CREATE TEMP TABLE v80_antes_favor     ON COMMIT DROP AS SELECT * FROM public.v_saldo_a_favor_por_recibo;

-- ── 3 · Las vistas, sin lo revertido ────────────────────────────────
DROP VIEW public.v_cartera_por_cliente;
DROP VIEW public.v_cartera_por_documento;
DROP VIEW public.v_saldo_a_favor_por_recibo;

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
               -- V80 (F4.13b): lo revertido con rastro tampoco cuenta.
               COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL AND rv.aplicacion_id IS NULL), 0) AS aplicado
          FROM public.debt_transactions d
          JOIN public.accounts_receivable ar ON ar.id = d.account_id AND ar.tenant_id = d.tenant_id
          CROSS JOIN (SELECT (now() AT TIME ZONE 'America/Bogota')::date AS hoy) h
          LEFT JOIN public.cartera_aplicaciones a ON a.tenant_id = d.tenant_id AND a.debito_tx_id = d.id
          LEFT JOIN public.recibos_de_caja x ON x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id
          LEFT JOIN public.cartera_aplicaciones_revertidas rv ON rv.aplicacion_id = a.id
         WHERE d.type = 'DEBIT'
           AND d.recibo_id IS NULL      -- el DEBIT de una anulación de recibo no es una factura
         GROUP BY d.tenant_id, d.id, d.account_id, ar.customer_document, d.order_uuid, d.transaction_date,
                  d.amount, d.vence_el, h.hoy
       ) g;

COMMENT ON VIEW public.v_cartera_por_documento IS
    'Cada factura a credito con lo aplicado, el saldo y la edad calculados al leer (sin guardarlos). '
    'V64; V68: una sola agregacion, para que el filtro por cliente o cuenta llegue a las tablas. V80: sin lo revertido.';

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

CREATE VIEW public.v_saldo_a_favor_por_recibo WITH (security_invoker = true) AS
SELECT r.tenant_id,
       r.id                                  AS recibo_id,
       k.id                                  AS credito_tx_id,
       r.cliente_documento,
       r.numero,
       r.ocurrido_en,
       r.monto,
       COALESCE(sum(a.monto) FILTER (WHERE rv.aplicacion_id IS NULL), 0)             AS aplicado,
       r.monto - COALESCE(sum(a.monto) FILTER (WHERE rv.aplicacion_id IS NULL), 0)   AS saldo_a_favor
  FROM public.recibos_de_caja r
  JOIN public.debt_transactions k ON k.tenant_id = r.tenant_id AND k.recibo_id = r.id AND k.type = 'CREDIT'
  LEFT JOIN public.cartera_aplicaciones a ON a.tenant_id = r.tenant_id AND a.recibo_id = r.id
  -- V80 (F4.13b): lo revertido con rastro vuelve a ser saldo a favor.
  LEFT JOIN public.cartera_aplicaciones_revertidas rv ON rv.aplicacion_id = a.id
 WHERE r.anula_recibo_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM public.recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id)
 GROUP BY r.tenant_id, r.id, k.id, r.cliente_documento, r.numero, r.ocurrido_en, r.monto
HAVING r.monto > COALESCE(sum(a.monto) FILTER (WHERE rv.aplicacion_id IS NULL), 0);
COMMENT ON VIEW public.v_saldo_a_favor_por_recibo IS
    'Recibos no anulados con monto sin aplicar: el saldo a favor del cliente, por recibo, calculado al leer. V74, F4.12. V80: sin lo revertido.';

DO $permisos_vistas$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.v_cartera_por_documento, public.v_cartera_por_cliente, public.v_saldo_a_favor_por_recibo TO app_user;
    END IF;
END
$permisos_vistas$;

-- ── 4 · La foto tampoco cuenta lo revertido antes del corte ─────────
CREATE OR REPLACE FUNCTION public.fn_deuda_a_fecha(p_tenant TEXT, p_documento TEXT, p_corte TIMESTAMPTZ)
RETURNS TABLE (debito_tx_id VARCHAR(36), order_uuid UUID, fecha DATE, vence_el DATE, monto NUMERIC, aplicado_al_corte NUMERIC, saldo_al_corte NUMERIC)
LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount,
           COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL AND rv.aplicacion_id IS NULL), 0),
           d.amount - COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL AND rv.aplicacion_id IS NULL), 0)
      FROM public.debt_transactions d
      JOIN public.accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
      LEFT JOIN public.orders o ON o.uuid_id = d.order_uuid AND o.tenant_id = d.tenant_id
      LEFT JOIN public.cartera_aplicaciones a
             ON a.tenant_id = d.tenant_id AND a.debito_tx_id = d.id AND a.ocurrido_en < p_corte
      LEFT JOIN public.recibos_de_caja x
             ON x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id AND x.ocurrido_en < p_corte
      -- V80 (F4.13b): una reversión registrada antes del corte tampoco cuenta (solo hechos anteriores a él).
      LEFT JOIN public.cartera_aplicaciones_revertidas rv ON rv.aplicacion_id = a.id AND rv.revertida_en < p_corte
     WHERE d.tenant_id = p_tenant AND ar.customer_document = p_documento
       AND d.type = 'DEBIT' AND d.recibo_id IS NULL
       AND (d.transaction_date < (p_corte AT TIME ZONE 'America/Bogota')::date
            OR (d.transaction_date = (p_corte AT TIME ZONE 'America/Bogota')::date
                AND COALESCE(o.ocurrido_en, d.created_at) < p_corte))
       AND NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e WHERE e.tenant_id = d.tenant_id AND e.debito_tx_id = d.id)
     GROUP BY d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount
    HAVING d.amount - COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL AND rv.aplicacion_id IS NULL), 0) > 0
     ORDER BY d.transaction_date, d.id
$$;
COMMENT ON FUNCTION public.fn_deuda_a_fecha(TEXT, TEXT, TIMESTAMPTZ) IS
    'F4.13: facturas con saldo del cliente en un instante, contando solo DEBIT, aplicaciones y anulaciones anteriores a el. '
    'Lo nacido el mismo dia cuenta por su hora. Reproducible. INVOKER. V75; V80: sin reversiones registradas antes del corte.';

-- ── 5 · La única forma de revertir ──────────────────────────────────
CREATE FUNCTION public.fn_revertir_aplicacion(p_aplicacion UUID, p_motivo TEXT, p_referencia TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_negocio TEXT := NULLIF(current_setting('app.tenant_id', true), '');
    v_usuario_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_usuario BIGINT;
    v_ap RECORD;
    v_proceso UUID;
    v_corte TIMESTAMPTZ;
BEGIN
    IF v_negocio IS NULL THEN
        RAISE EXCEPTION 'Reversion sin negocio en la sesion.' USING ERRCODE = 'P0001';
    END IF;
    IF v_usuario_txt IS NULL OR v_usuario_txt !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'Reversion sin usuario en la sesion: lleva autor.' USING ERRCODE = 'P0001';
    END IF;
    v_usuario := v_usuario_txt::BIGINT;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = v_usuario AND u.tenant_id = v_negocio) THEN
        RAISE EXCEPTION 'El usuario % no es de este negocio.', v_usuario USING ERRCODE = 'P0001';
    END IF;
    IF p_motivo IS NULL OR btrim(p_motivo) = '' THEN
        RAISE EXCEPTION 'Falta el motivo: escribe por que se revierte. No se revirtio nada.' USING ERRCODE = 'P0001';
    END IF;
    SELECT a.id, a.regla, a.ocurrido_en, a.recibo_id, r.cliente_documento, r.numero,
           EXISTS (SELECT 1 FROM public.recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id) AS anulado
      INTO v_ap
      FROM public.cartera_aplicaciones a
      JOIN public.recibos_de_caja r ON r.tenant_id = a.tenant_id AND r.id = a.recibo_id
     WHERE a.tenant_id = v_negocio AND a.id = p_aplicacion
       FOR UPDATE OF a;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Esa aplicacion no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM public.cartera_aplicaciones_revertidas rv WHERE rv.aplicacion_id = p_aplicacion) THEN
        RETURN false;                                   -- el reintento: ya revertida, nada más
    END IF;
    IF v_ap.regla <> 'SALDO_A_FAVOR_AUTOMATICO' THEN
        RAISE EXCEPTION 'Aqui solo se revierten los pagos que el sistema aplico solo desde el saldo a favor. No se revirtio nada.' USING ERRCODE = 'P0001';
    END IF;
    SELECT vv.proceso_id, vv.corte INTO v_proceso, v_corte FROM public.v_insolvencia_vigente vv
     WHERE vv.tenant_id = v_negocio AND vv.cliente_documento = v_ap.cliente_documento AND vv.en_proceso;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El cliente no tiene un proceso de insolvencia en curso. No se revirtio nada.' USING ERRCODE = 'P0001';
    END IF;
    IF v_ap.anulado THEN
        RAISE EXCEPTION 'El recibo % de ese saldo a favor esta anulado. No se revirtio nada.', v_ap.numero USING ERRCODE = 'P0001';
    END IF;
    IF v_corte IS NULL OR v_ap.ocurrido_en < v_corte THEN
        RAISE EXCEPTION 'Ese pago se aplico antes del inicio del proceso. No se revirtio nada.' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.cartera_aplicaciones_revertidas (aplicacion_id, tenant_id, proceso_id, motivo, referencia, revertida_por)
    VALUES (p_aplicacion, v_negocio, v_proceso, btrim(p_motivo), NULLIF(btrim(p_referencia), ''), v_usuario);
    RETURN true;
END $$;
COMMENT ON FUNCTION public.fn_revertir_aplicacion(UUID, TEXT, TEXT) IS
    'F4.13b: revierte con rastro una aplicacion SALDO_A_FAVOR_AUTOMATICO del negocio de la sesion, ocurrida en o despues '
    'del corte del proceso en curso y con su recibo sin anular. true = revertida; false = ya lo estaba. V80.';

DO $rls$
BEGIN
    ALTER TABLE public.cartera_aplicaciones_revertidas ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.cartera_aplicaciones_revertidas FORCE ROW LEVEL SECURITY;
    CREATE POLICY tenant_isolation_cartera_aplicaciones_revertidas ON public.cartera_aplicaciones_revertidas
        USING (tenant_id = current_setting('app.tenant_id', true))
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
    REVOKE ALL ON FUNCTION public.fn_revertir_aplicacion(UUID, TEXT, TEXT) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.cartera_aplicaciones_revertidas TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_revertir_aplicacion(UUID, TEXT, TEXT) TO app_user;
    END IF;
END
$rls$;

-- ── 6 · Cierre: mismas filas, y el comportamiento ───────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v80_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_r UUID; v_r_viejo UUID;
    v_ap UUID; v_ap_vieja UUID; v_ap_abono UUID;
    v_huella TEXT; v_corte TIMESTAMPTZ;
    v_faltan BIGINT; v_sobran BIGINT;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
                WHERE ns.nspname = 'public' AND c.relname IN ('v_cartera_por_documento', 'v_cartera_por_cliente', 'v_saldo_a_favor_por_recibo')
                  AND NOT COALESCE(c.reloptions @> ARRAY['security_invoker=true'], false)) THEN
        RAISE EXCEPTION 'V80: una vista de cartera quedo sin security_invoker';
    END IF;
    -- Sin reversiones, las vistas recreadas devuelven lo mismo que antes, en las dos direcciones y para todos los negocios.
    SELECT count(*) INTO v_faltan FROM (SELECT * FROM v80_antes_documento EXCEPT SELECT * FROM public.v_cartera_por_documento) q;
    SELECT count(*) INTO v_sobran FROM (SELECT * FROM public.v_cartera_por_documento EXCEPT SELECT * FROM v80_antes_documento) q;
    IF v_faltan + v_sobran > 0 THEN RAISE EXCEPTION 'V80: v_cartera_por_documento cambio (% faltan, % sobran)', v_faltan, v_sobran; END IF;
    SELECT count(*) INTO v_faltan FROM (SELECT * FROM v80_antes_cliente EXCEPT SELECT * FROM public.v_cartera_por_cliente) q;
    SELECT count(*) INTO v_sobran FROM (SELECT * FROM public.v_cartera_por_cliente EXCEPT SELECT * FROM v80_antes_cliente) q;
    IF v_faltan + v_sobran > 0 THEN RAISE EXCEPTION 'V80: v_cartera_por_cliente cambio (% faltan, % sobran)', v_faltan, v_sobran; END IF;
    SELECT count(*) INTO v_faltan FROM (SELECT * FROM v80_antes_favor EXCEPT SELECT * FROM public.v_saldo_a_favor_por_recibo) q;
    SELECT count(*) INTO v_sobran FROM (SELECT * FROM public.v_saldo_a_favor_por_recibo EXCEPT SELECT * FROM v80_antes_favor) q;
    IF v_faltan + v_sobran > 0 THEN RAISE EXCEPTION 'V80: v_saldo_a_favor_por_recibo cambio (% faltan, % sobran)', v_faltan, v_sobran; END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V80 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v80@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    -- El autor antes de sembrar: el disparador de clientes_eventos no avisa «sin autor» en el log de arranque.
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v80-c', 'Cliente', 8, 'v80');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
    VALUES ('v80-cuenta', a, now(), 1000000, 'v80-c', 'Cliente', 'ACTIVE', 100000, now());
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES ('v80-factura', a, 'v80-cuenta', 100000, now() - interval '20 days', 'x', v_hoy - 20, 'DEBIT', v_hoy - 12);

    -- Un saldo a favor viejo (hace 10 días) y uno de hoy, aplicados solos a la factura antes de saberse la insolvencia.
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v80-c', 15000, 'EFECTIVO', now() - interval '10 days', 'v80-r-viejo') RETURNING id INTO v_r_viejo;
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v80-c', 25000, 'EFECTIVO', now(), 'v80-r') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id) VALUES
        ('v80-k-viejo', a, 'v80-cuenta', 15000, now(), 'x', v_hoy - 10, 'CREDIT', v_r_viejo),
        ('v80-k', a, 'v80-cuenta', 25000, now(), 'x', v_hoy, 'CREDIT', v_r);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, ocurrido_en)
    VALUES (a, v_r_viejo, 'v80-k-viejo', 'v80-factura', 15000, 'SALDO_A_FAVOR_AUTOMATICO', now() - interval '10 days') RETURNING id INTO v_ap_vieja;
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r, 'v80-k', 'v80-factura', 20000, 'SALDO_A_FAVOR_AUTOMATICO') RETURNING id INTO v_ap;
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r, 'v80-k', 'v80-factura', 5000, 'MAS_ANTIGUA_PRIMERO') RETURNING id INTO v_ap_abono;

    -- Sin proceso en curso no se revierte.
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_revertir_aplicacion(v_ap, 'Prueba', NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V80: se revirtio sin proceso en curso'; END IF;

    -- INICIO retroactivo hace 5 días: la de hoy es posterior al corte; la de hace 10, anterior.
    PERFORM public.fn_insolvencia_informar_etapa('v80-c', 'INICIO', v_hoy - 5, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    SELECT corte, foto_huella INTO v_corte, v_huella FROM public.v_insolvencia_vigente WHERE tenant_id = a AND cliente_documento = 'v80-c';
    IF (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v80-factura') <> 60000 THEN
        RAISE EXCEPTION 'V80: la factura no arranca en 60.000';
    END IF;

    IF NOT public.fn_revertir_aplicacion(v_ap, 'Concepto del abogado', 'Acta 12') THEN
        RAISE EXCEPTION 'V80: la reversion no respondio true';
    END IF;
    IF (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v80-factura') <> 80000
       OR (SELECT saldo_a_favor FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND recibo_id = v_r) <> 20000
       OR (SELECT saldo FROM public.v_cartera_por_cliente WHERE tenant_id = a AND cliente_documento = 'v80-c') <> 80000
       OR (SELECT total_debt FROM public.accounts_receivable WHERE id = 'v80-cuenta') <> 100000 THEN
        RAISE EXCEPTION 'V80: revertir no reabrio la factura por 20.000, no devolvio el saldo a favor o movio total_debt';
    END IF;
    IF public.fn_revertir_aplicacion(v_ap, 'Otra vez', NULL) THEN
        RAISE EXCEPTION 'V80: el reintento no respondio false';
    END IF;
    -- La foto no cambia (lo revertido era posterior al corte).
    IF public.fn_huella_de_deuda(a, 'v80-c', v_corte) <> v_huella THEN
        RAISE EXCEPTION 'V80: revertir cambio la huella de la foto';
    END IF;
    -- Un abono no; lo anterior al corte no; sin motivo no.
    FOR t IN SELECT * FROM (VALUES (v_ap_abono, 'Motivo'), (v_ap_vieja, 'Motivo'), (v_ap, '  ')) x(ap, motivo) LOOP
        v_rechazado := false;
        BEGIN
            PERFORM public.fn_revertir_aplicacion(t.ap, t.motivo, NULL);
        EXCEPTION WHEN raise_exception THEN v_rechazado := true;
        END;
        IF NOT v_rechazado AND t.ap <> v_ap THEN RAISE EXCEPTION 'V80: se revirtio lo que no se revierte (%)', t.ap; END IF;
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.cartera_aplicaciones_revertidas', 'INSERT')
            OR has_table_privilege('app_user', 'public.cartera_aplicaciones_revertidas', 'UPDATE')
            OR has_table_privilege('app_user', 'public.cartera_aplicaciones_revertidas', 'DELETE')) THEN
        RAISE EXCEPTION 'V80: app_user escribe reversiones sin la funcion';
    END IF;
    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_revertir_aplicacion(uuid,text,text)'))
       OR has_function_privilege('public', 'public.fn_revertir_aplicacion(uuid,text,text)', 'EXECUTE')
       OR (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_deuda_a_fecha(text,text,timestamptz)'))
       OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()')) <> '0107b1c3bcd8711455abba4b82d4e985' THEN
        RAISE EXCEPTION 'V80: permisos de las funciones mal puestos, o se toco fn_venta_a_credito';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.cartera_aplicaciones_revertidas WHERE tenant_id = a;
    DELETE FROM public.cartera_aplicaciones          WHERE tenant_id = a;
    DELETE FROM public.debt_transactions             WHERE tenant_id = a;
    DELETE FROM public.recibos_de_caja               WHERE tenant_id = a;
    DELETE FROM public.contadores_de_recibos         WHERE tenant_id = a;
    DELETE FROM public.insolvencia_credito_posterior WHERE tenant_id = a;
    DELETE FROM public.insolvencia_foto_facturas     WHERE tenant_id = a;
    DELETE FROM public.insolvencia_fotos             WHERE tenant_id = a;
    DELETE FROM public.insolvencia_etapas            WHERE tenant_id = a;
    DELETE FROM public.insolvencia_procesos          WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos              WHERE tenant_id = a;
    DELETE FROM public.accounts_receivable           WHERE tenant_id = a;
    DELETE FROM public.clientes                      WHERE tenant_id = a;
    DELETE FROM public.users                         WHERE tenant_id = a;
    DELETE FROM public.tenants                       WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v80%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v80%') THEN
        RAISE EXCEPTION 'V80: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V80: vistas recreadas con security_invoker y las mismas filas; revertir reabre 20.000 y devuelve el saldo a favor sin mover total_debt; reintento false; foto intacta; abono, anterior al corte y sin motivo rechazados; sin proceso rechazado; DEFINER sin PUBLIC; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- (antes, decidir qué pasa con las reversiones: son pagos que se dejaron de contar)
-- DROP FUNCTION public.fn_revertir_aplicacion(UUID, TEXT, TEXT);
-- Vistas: las de V68 y V74 con DROP + CREATE WITH (security_invoker = true); fn_deuda_a_fecha: la de V75 (md5 90e849a4…);
-- DROP TABLE public.cartera_aplicaciones_revertidas;
