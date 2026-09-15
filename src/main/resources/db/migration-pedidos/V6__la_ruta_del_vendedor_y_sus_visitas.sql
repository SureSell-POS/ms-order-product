-- =====================================================================
-- V6 pedidos -- La ruta del vendedor y sus visitas (plan de mayoristas F6.1, M-D2).
--
-- Diseño: docs/planes/DISENO-F6-RUTA.md §1 y decisiones de ECM (§8): D1 el plan en `pedidos`, solo anexa por vigencia;
-- D2 SEMANAL, QUINCENAL y MENSUAL con semana ancla; D4 visita a un cliente de otro vendedor se acepta y se marca; D5
-- CLIENTE_NUEVO con el documento tal cual, sin ficha; D7 una lectura de GPS opcional; D10 CON_PEDIDO acepta pedido o venta
-- (order_uuid); D11 la visita que nombra un elemento rechazado del mismo lote se acepta con referencia_rechazada y, si era
-- CON_PEDIDO, queda SIN_PEDIDO con motivo PEDIDO_RECHAZADO.
--
-- ── Lo que entra ────────────────────────────────────────────────────
--   · pedidos.planes_de_visita: días (máscara lun=1 … dom=64), frecuencia, semana ancla y ventana; una vigencia por cliente.
--   · pedidos.orden_en_ruta: el orden que el vendedor fija para un día de la semana; una vigencia por (vendedor, día, cliente).
--   · pedidos.visitas: M-D2 más procedencia del teléfono, referencias por clave, venta de autoventa y las marcas de D4 y D11.
--   · Tres funciones DEFINER (fijar plan, fijar orden, registrar visita): app_user solo lee las tablas.
--   · pedidos.fn_toca_visita (SQL, IMMUTABLE, se inlina) y la ruta de un día: v_ruta_de_hoy y fn_ruta_del_dia(vendedor, fecha).
--   · v_efectividad (visitas con pedido / visitas, por vendedor y día; sin visitas no hay fila: sin dato ≠ 0) y
--     v_activos_sobre_codificados (clientes con compra en 30 días / clientes activos asignados, por vendedor).
--
-- IMPACTO: tablas nuevas vacías; ninguna tabla existente cambia. El costo de las vistas se mide en F6.2 antes de exponerlas.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · Plan de visitas ───────────────────────────────────────────────
CREATE TABLE pedidos.planes_de_visita (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         TEXT        NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    cliente_documento TEXT        NOT NULL,
    dias              SMALLINT    NOT NULL,
    frecuencia        TEXT        NOT NULL,
    semana_ancla      DATE        NULL,
    ventana_desde     TIME        NULL,
    ventana_hasta     TIME        NULL,
    vigente_desde     TIMESTAMPTZ NOT NULL DEFAULT now(),
    vigente_hasta     TIMESTAMPTZ NULL,
    registrado_por    BIGINT      NOT NULL REFERENCES public.users(id),
    CONSTRAINT pk_planes_de_visita PRIMARY KEY (id),
    CONSTRAINT fk_planes_de_visita_cliente FOREIGN KEY (tenant_id, cliente_documento) REFERENCES public.clientes (tenant_id, documento),
    CONSTRAINT ck_planes_de_visita_dias CHECK (dias BETWEEN 1 AND 127),
    CONSTRAINT ck_planes_de_visita_frecuencia CHECK (frecuencia IN ('SEMANAL', 'QUINCENAL', 'MENSUAL')),
    CONSTRAINT ck_planes_de_visita_ancla CHECK ((frecuencia = 'SEMANAL') = (semana_ancla IS NULL)),
    CONSTRAINT ck_planes_de_visita_ventana CHECK ((ventana_desde IS NULL) = (ventana_hasta IS NULL)
                                                 AND (ventana_desde IS NULL OR ventana_desde < ventana_hasta)),
    CONSTRAINT ck_planes_de_visita_vigencia CHECK (vigente_hasta IS NULL OR vigente_hasta >= vigente_desde)
);
CREATE UNIQUE INDEX ux_planes_de_visita_vigente ON pedidos.planes_de_visita (tenant_id, cliente_documento) WHERE vigente_hasta IS NULL;
COMMENT ON TABLE pedidos.planes_de_visita IS
    'F6.1: qué días y cada cuánto se visita a un cliente. Solo anexa por vigencia; la escribe fn_plan_de_visita_fijar. V6 pedidos.';

-- ── 2 · Orden en ruta ─────────────────────────────────────────────────
CREATE TABLE pedidos.orden_en_ruta (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         TEXT        NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    vendedor_id       BIGINT      NOT NULL REFERENCES public.users(id),
    dia               TEXT        NOT NULL,
    cliente_documento TEXT        NOT NULL,
    posicion          SMALLINT    NOT NULL,
    vigente_desde     TIMESTAMPTZ NOT NULL DEFAULT now(),
    vigente_hasta     TIMESTAMPTZ NULL,
    CONSTRAINT pk_orden_en_ruta PRIMARY KEY (id),
    CONSTRAINT ck_orden_en_ruta_dia CHECK (dia IN ('LUNES', 'MARTES', 'MIERCOLES', 'JUEVES', 'VIERNES', 'SABADO', 'DOMINGO')),
    CONSTRAINT ck_orden_en_ruta_posicion CHECK (posicion >= 1)
);
CREATE UNIQUE INDEX ux_orden_en_ruta_vigente ON pedidos.orden_en_ruta (tenant_id, vendedor_id, dia, cliente_documento) WHERE vigente_hasta IS NULL;
COMMENT ON TABLE pedidos.orden_en_ruta IS
    'F6.1 (M-D2): el orden que el vendedor fija para un día. Solo anexa por vigencia; la escribe fn_orden_en_ruta_fijar. V6 pedidos.';

-- ── 3 · Visitas ───────────────────────────────────────────────────────
CREATE TABLE pedidos.visitas (
    id                     UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id              TEXT          NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    vendedor_id            BIGINT        NOT NULL REFERENCES public.users(id),
    cliente_documento      TEXT          NOT NULL,
    resultado              TEXT          NOT NULL,
    motivo_sin_pedido      TEXT          NULL,
    pedido_id              UUID          NULL REFERENCES pedidos.pedidos(id),
    recibo_id              UUID          NULL,
    order_uuid             UUID          NULL,
    pedido_idempotency_key TEXT          NULL,
    recibo_idempotency_key TEXT          NULL,
    referencia_rechazada   BOOLEAN       NOT NULL DEFAULT false,
    de_otro_vendedor       BOOLEAN       NOT NULL DEFAULT false,
    latitud                NUMERIC(9,6)  NULL,
    longitud               NUMERIC(9,6)  NULL,
    terminal_id            UUID          NULL,
    seq                    BIGINT        NULL,
    ocurrido_en            TIMESTAMPTZ   NOT NULL,
    registrado_en          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    idempotency_key        TEXT          NOT NULL,
    CONSTRAINT pk_visitas PRIMARY KEY (id),
    CONSTRAINT ux_visitas_idempotencia UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_visitas_resultado CHECK (resultado IN ('CON_PEDIDO', 'SIN_PEDIDO', 'SOLO_COBRO', 'CERRADO', 'NO_ATENDIO', 'CLIENTE_NUEVO')),
    CONSTRAINT ck_visitas_motivo CHECK (motivo_sin_pedido IS NULL OR motivo_sin_pedido IN
        ('TIENE_SURTIDO', 'SIN_DINERO', 'PRECIO', 'COMPRO_A_OTRO', 'PRODUCTO_AGOTADO', 'PIDE_OTRO_DIA', 'CREDITO_BLOQUEADO', 'PEDIDO_RECHAZADO')),
    -- F6.1 «hecho cuando»: SIN_PEDIDO sin motivo se rechaza; y ningún otro resultado lleva motivo.
    CONSTRAINT ck_visitas_sin_pedido_con_motivo CHECK ((resultado = 'SIN_PEDIDO') = (motivo_sin_pedido IS NOT NULL)),
    -- D11: PEDIDO_RECHAZADO solo cuando la referencia fue rechazada en el lote.
    CONSTRAINT ck_visitas_pedido_rechazado CHECK ((motivo_sin_pedido = 'PEDIDO_RECHAZADO') <= referencia_rechazada),
    -- D10: CON_PEDIDO es un pedido o una venta de autoventa, resuelto.
    CONSTRAINT ck_visitas_con_pedido CHECK ((resultado = 'CON_PEDIDO') <= (pedido_id IS NOT NULL OR order_uuid IS NOT NULL)),
    CONSTRAINT ck_visitas_solo_cobro CHECK ((resultado = 'SOLO_COBRO') <= (recibo_id IS NOT NULL OR referencia_rechazada)),
    CONSTRAINT ck_visitas_gps CHECK ((latitud IS NULL) = (longitud IS NULL)
                                     AND (latitud IS NULL OR (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180))),
    CONSTRAINT ck_visitas_reloj CHECK (ocurrido_en <= registrado_en + interval '5 minutes')
);
CREATE INDEX ix_visitas_vendedor_dia ON pedidos.visitas (tenant_id, vendedor_id, ocurrido_en);
CREATE INDEX ix_visitas_cliente ON pedidos.visitas (tenant_id, cliente_documento, ocurrido_en);
COMMENT ON TABLE pedidos.visitas IS
    'F6.1 (M-D2): la visita de un vendedor a un cliente. Solo anexa; la escribe fn_visita_registrar. V6 pedidos.';

-- ── 4 · Aislamiento ───────────────────────────────────────────────────
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['planes_de_visita', 'orden_en_ruta', 'visitas'] LOOP
        EXECUTE format('ALTER TABLE pedidos.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE pedidos.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON pedidos.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
END
$rls$;

-- ── 5 · ¿Toca visitar este día? ───────────────────────────────────────
-- SQL e IMMUTABLE: el planificador la inlina en la consulta (no es una llamada por fila).
--   SEMANAL: el día de la semana está en la máscara.
--   QUINCENAL: además, la semana (lunes) de la fecha dista de la del ancla un número par de semanas.
--   MENSUAL: además, es la misma «n-ésima semana del mes» que el ancla (1.ª = días 1-7, 2.ª = 8-14…): «el segundo martes».
CREATE FUNCTION pedidos.fn_toca_visita(p_dias SMALLINT, p_frecuencia TEXT, p_ancla DATE, p_fecha DATE)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT (p_dias & (1 << (extract(isodow FROM p_fecha)::int - 1))::smallint) <> 0
       AND CASE p_frecuencia
             WHEN 'SEMANAL' THEN true
             WHEN 'QUINCENAL' THEN ((date_trunc('week', p_fecha)::date - date_trunc('week', p_ancla)::date) / 7) % 2 = 0
             WHEN 'MENSUAL' THEN (extract(day FROM p_fecha)::int - 1) / 7 = (extract(day FROM p_ancla)::int - 1) / 7
             ELSE false
           END
$$;
COMMENT ON FUNCTION pedidos.fn_toca_visita(SMALLINT, TEXT, DATE, DATE) IS
    'F6.1: si un plan de visita toca en una fecha (máscara de días y frecuencia con ancla). SQL IMMUTABLE, inlinable. V6 pedidos.';

CREATE FUNCTION pedidos.fn_nombre_del_dia(p_fecha DATE)
RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT (ARRAY['LUNES', 'MARTES', 'MIERCOLES', 'JUEVES', 'VIERNES', 'SABADO', 'DOMINGO'])[extract(isodow FROM p_fecha)::int]
$$;

-- ── 6 · La ruta de un día ─────────────────────────────────────────────
CREATE FUNCTION pedidos.fn_ruta_del_dia(p_vendedor_id BIGINT, p_fecha DATE)
RETURNS TABLE (tenant_id TEXT, vendedor_id BIGINT, cliente_documento TEXT, posicion SMALLINT,
               ventana_desde TIME, ventana_hasta TIME, frecuencia TEXT)
LANGUAGE sql STABLE AS $$
    SELECT c.tenant_id, c.vendedor_id, c.documento, o.posicion, p.ventana_desde, p.ventana_hasta, p.frecuencia
      FROM public.clientes c
      JOIN pedidos.planes_de_visita p
        ON p.tenant_id = c.tenant_id AND p.cliente_documento = c.documento AND p.vigente_hasta IS NULL
      LEFT JOIN pedidos.orden_en_ruta o
        ON o.tenant_id = c.tenant_id AND o.vendedor_id = c.vendedor_id AND o.cliente_documento = c.documento
       AND o.dia = pedidos.fn_nombre_del_dia(p_fecha) AND o.vigente_hasta IS NULL
     WHERE c.vendedor_id = p_vendedor_id AND c.activo
       AND pedidos.fn_toca_visita(p.dias, p.frecuencia, p.semana_ancla, p_fecha)
$$;
COMMENT ON FUNCTION pedidos.fn_ruta_del_dia(BIGINT, DATE) IS
    'F6.1: los clientes activos de un vendedor que tocan en una fecha, con su posición y ventana. INVOKER (RLS aplica). V6 pedidos.';

CREATE VIEW pedidos.v_ruta_de_hoy WITH (security_invoker = true) AS
SELECT c.tenant_id, c.vendedor_id, c.documento AS cliente_documento, o.posicion, p.ventana_desde, p.ventana_hasta, p.frecuencia,
       vh.resultado AS resultado_de_hoy, vh.ocurrido_en AS visitado_en
  FROM public.clientes c
  JOIN pedidos.planes_de_visita p
    ON p.tenant_id = c.tenant_id AND p.cliente_documento = c.documento AND p.vigente_hasta IS NULL
  LEFT JOIN pedidos.orden_en_ruta o
    ON o.tenant_id = c.tenant_id AND o.vendedor_id = c.vendedor_id AND o.cliente_documento = c.documento
   AND o.dia = pedidos.fn_nombre_del_dia((now() AT TIME ZONE 'America/Bogota')::date) AND o.vigente_hasta IS NULL
  LEFT JOIN LATERAL (SELECT v.resultado, v.ocurrido_en FROM pedidos.visitas v
                      WHERE v.tenant_id = c.tenant_id AND v.cliente_documento = c.documento
                        AND v.ocurrido_en >= ((now() AT TIME ZONE 'America/Bogota')::date)::timestamp AT TIME ZONE 'America/Bogota'
                      ORDER BY v.ocurrido_en DESC LIMIT 1) vh ON true
 WHERE c.vendedor_id IS NOT NULL AND c.activo
   AND pedidos.fn_toca_visita(p.dias, p.frecuencia, p.semana_ancla, (now() AT TIME ZONE 'America/Bogota')::date);
COMMENT ON VIEW pedidos.v_ruta_de_hoy IS
    'F6.1: la ruta de hoy (Bogotá) de cada vendedor: clientes activos que tocan, con posición, ventana y la visita de hoy. V6 pedidos.';

-- ── 7 · Efectividad y activos sobre codificados ───────────────────────
CREATE VIEW pedidos.v_efectividad WITH (security_invoker = true) AS
SELECT v.tenant_id, v.vendedor_id, (v.ocurrido_en AT TIME ZONE 'America/Bogota')::date AS dia,
       count(*) AS visitas,
       count(*) FILTER (WHERE v.resultado = 'CON_PEDIDO') AS con_pedido,
       round(count(*) FILTER (WHERE v.resultado = 'CON_PEDIDO')::numeric / count(*), 4) AS efectividad
  FROM pedidos.visitas v
 GROUP BY v.tenant_id, v.vendedor_id, (v.ocurrido_en AT TIME ZONE 'America/Bogota')::date;
COMMENT ON VIEW pedidos.v_efectividad IS
    'F6.1: por vendedor y día (Bogotá), visitas con pedido / visitas. Sin visitas no hay fila (sin dato no es 0 %). V6 pedidos.';

CREATE VIEW pedidos.v_activos_sobre_codificados WITH (security_invoker = true) AS
SELECT c.tenant_id, c.vendedor_id,
       count(*) AS codificados,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM public.orders o
                                       WHERE o.tenant_id = c.tenant_id AND o.cliente_documento = c.documento
                                         AND o.created_at >= now() - interval '30 days')) AS activos
  FROM public.clientes c
 WHERE c.vendedor_id IS NOT NULL AND c.activo
 GROUP BY c.tenant_id, c.vendedor_id;
COMMENT ON VIEW pedidos.v_activos_sobre_codificados IS
    'F6.1: por vendedor, clientes activos asignados (codificados) y los que compraron en 30 días (activos). V6 pedidos.';

-- ── 8 · Escribir: tres funciones DEFINER ──────────────────────────────
CREATE FUNCTION pedidos.fn_plan_de_visita_fijar(p_cliente_documento TEXT, p_dias SMALLINT, p_frecuencia TEXT, p_semana_ancla DATE,
                                                p_ventana_desde TIME, p_ventana_hasta TIME)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_id UUID;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF NOT EXISTS (SELECT 1 FROM public.clientes c WHERE c.tenant_id = s.negocio AND c.documento = p_cliente_documento) THEN
        RAISE EXCEPTION 'Ese cliente no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    -- Cierra la vigente (si la hay) y, con días, anexa la nueva. Sin días = quitar el plan.
    UPDATE pedidos.planes_de_visita SET vigente_hasta = now()
     WHERE tenant_id = s.negocio AND cliente_documento = p_cliente_documento AND vigente_hasta IS NULL;
    IF p_dias IS NULL THEN
        RETURN NULL;
    END IF;
    INSERT INTO pedidos.planes_de_visita (tenant_id, cliente_documento, dias, frecuencia, semana_ancla, ventana_desde, ventana_hasta, registrado_por)
    VALUES (s.negocio, p_cliente_documento, p_dias, p_frecuencia, p_semana_ancla, p_ventana_desde, p_ventana_hasta, s.usuario)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

CREATE FUNCTION pedidos.fn_orden_en_ruta_fijar(p_dia TEXT, p_clientes TEXT[])
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_n INTEGER;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_clientes IS NULL OR cardinality(p_clientes) <> (SELECT count(DISTINCT x) FROM unnest(p_clientes) x) THEN
        RAISE EXCEPTION 'El orden de la ruta no repite clientes.' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(p_clientes) x
                WHERE NOT EXISTS (SELECT 1 FROM public.clientes c WHERE c.tenant_id = s.negocio AND c.documento = x)) THEN
        RAISE EXCEPTION 'Un cliente del orden no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    -- El orden es del vendedor de la sesión: cierra el vigente de ese día y anexa el nuevo, entero.
    UPDATE pedidos.orden_en_ruta SET vigente_hasta = now()
     WHERE tenant_id = s.negocio AND vendedor_id = s.usuario AND dia = p_dia AND vigente_hasta IS NULL;
    INSERT INTO pedidos.orden_en_ruta (tenant_id, vendedor_id, dia, cliente_documento, posicion)
    SELECT s.negocio, s.usuario, p_dia, x.documento, x.n::smallint
      FROM unnest(p_clientes) WITH ORDINALITY AS x(documento, n);
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RETURN v_n;
END $$;

-- Devuelve la visita y si ya existía (reintento con la misma clave y el mismo contenido).
CREATE FUNCTION pedidos.fn_visita_registrar(p_cliente_documento TEXT, p_resultado TEXT, p_motivo_sin_pedido TEXT,
                                            p_pedido_idempotency_key TEXT, p_recibo_idempotency_key TEXT, p_order_uuid UUID,
                                            p_referencia_rechazada BOOLEAN, p_latitud NUMERIC, p_longitud NUMERIC,
                                            p_terminal_id UUID, p_seq BIGINT, p_ocurrido_en TIMESTAMPTZ, p_idempotency_key TEXT,
                                            OUT visita_id UUID, OUT repetida BOOLEAN)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_existente pedidos.visitas;
    v_resultado TEXT := p_resultado;
    v_motivo TEXT := p_motivo_sin_pedido;
    v_pedido UUID;
    v_recibo UUID;
    v_vendedor_del_cliente BIGINT;
    v_cliente_existe BOOLEAN;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RAISE EXCEPTION 'Falta la clave de idempotencia de la visita.' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v_existente FROM pedidos.visitas WHERE tenant_id = s.negocio AND idempotency_key = p_idempotency_key;
    IF FOUND THEN
        IF v_existente.cliente_documento IS DISTINCT FROM p_cliente_documento OR v_existente.vendedor_id IS DISTINCT FROM s.usuario
           OR (v_existente.resultado IS DISTINCT FROM p_resultado AND NOT v_existente.referencia_rechazada) THEN
            RAISE EXCEPTION 'IDEMPOTENCIA_REUTILIZADA: esa clave ya se usó para otra visita.' USING ERRCODE = 'P0001';
        END IF;
        visita_id := v_existente.id;
        repetida := true;
        RETURN;
    END IF;

    SELECT true, c.vendedor_id INTO v_cliente_existe, v_vendedor_del_cliente
      FROM public.clientes c WHERE c.tenant_id = s.negocio AND c.documento = p_cliente_documento;
    -- D5: CLIENTE_NUEVO todavía no tiene ficha; los demás resultados sí.
    IF NOT COALESCE(v_cliente_existe, false) AND p_resultado IS DISTINCT FROM 'CLIENTE_NUEVO' THEN
        RAISE EXCEPTION 'Ese cliente no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;

    -- Referencias por clave, del mismo lote o de uno anterior.
    IF p_pedido_idempotency_key IS NOT NULL AND NOT COALESCE(p_referencia_rechazada, false) THEN
        SELECT id INTO v_pedido FROM pedidos.pedidos WHERE tenant_id = s.negocio AND idempotency_key = p_pedido_idempotency_key;
        IF v_pedido IS NULL THEN
            RAISE EXCEPTION 'REFERENCIA_NO_ENCONTRADA: el pedido de esa visita todavía no llegó.' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF p_recibo_idempotency_key IS NOT NULL AND NOT COALESCE(p_referencia_rechazada, false) THEN
        SELECT id INTO v_recibo FROM public.recibos_de_caja WHERE tenant_id = s.negocio AND idempotency_key = p_recibo_idempotency_key;
        IF v_recibo IS NULL THEN
            RAISE EXCEPTION 'REFERENCIA_NO_ENCONTRADA: el recibo de esa visita todavía no llegó.' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    -- D11: con la referencia rechazada en el lote, la visita se acepta; CON_PEDIDO pasa a SIN_PEDIDO con PEDIDO_RECHAZADO.
    IF COALESCE(p_referencia_rechazada, false) AND p_resultado = 'CON_PEDIDO' AND p_order_uuid IS NULL THEN
        v_resultado := 'SIN_PEDIDO';
        v_motivo := 'PEDIDO_RECHAZADO';
    END IF;

    INSERT INTO pedidos.visitas (tenant_id, vendedor_id, cliente_documento, resultado, motivo_sin_pedido, pedido_id, recibo_id, order_uuid,
                                 pedido_idempotency_key, recibo_idempotency_key, referencia_rechazada, de_otro_vendedor,
                                 latitud, longitud, terminal_id, seq, ocurrido_en, idempotency_key)
    VALUES (s.negocio, s.usuario, p_cliente_documento, v_resultado, v_motivo, v_pedido, v_recibo, p_order_uuid,
            p_pedido_idempotency_key, p_recibo_idempotency_key, COALESCE(p_referencia_rechazada, false),
            COALESCE(v_cliente_existe, false) AND v_vendedor_del_cliente IS DISTINCT FROM s.usuario,
            p_latitud, p_longitud, p_terminal_id, p_seq, COALESCE(p_ocurrido_en, now()), p_idempotency_key)
    RETURNING id INTO visita_id;
    repetida := false;
END $$;

COMMENT ON FUNCTION pedidos.fn_plan_de_visita_fijar(TEXT, SMALLINT, TEXT, DATE, TIME, TIME) IS
    'F6.1: cierra el plan vigente del cliente y anexa el nuevo (sin días: solo lo quita). DEFINER. V6 pedidos.';
COMMENT ON FUNCTION pedidos.fn_orden_en_ruta_fijar(TEXT, TEXT[]) IS
    'F6.1: el vendedor de la sesión fija el orden de un día: cierra el vigente y anexa el nuevo. DEFINER. V6 pedidos.';
COMMENT ON FUNCTION pedidos.fn_visita_registrar(TEXT, TEXT, TEXT, TEXT, TEXT, UUID, BOOLEAN, NUMERIC, NUMERIC, UUID, BIGINT, TIMESTAMPTZ, TEXT) IS
    'F6.1: registra una visita del vendedor de la sesión, idempotente por clave; resuelve pedido y recibo por clave (D10, D11), '
    'marca la de un cliente de otro vendedor (D4) y admite CLIENTE_NUEVO sin ficha (D5). DEFINER. V6 pedidos.';

-- ── 9 · Permisos ──────────────────────────────────────────────────────
REVOKE ALL ON FUNCTION pedidos.fn_plan_de_visita_fijar(TEXT, SMALLINT, TEXT, DATE, TIME, TIME) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_orden_en_ruta_fijar(TEXT, TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_visita_registrar(TEXT, TEXT, TEXT, TEXT, TEXT, UUID, BOOLEAN, NUMERIC, NUMERIC, UUID, BIGINT, TIMESTAMPTZ, TEXT) FROM PUBLIC;
DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON pedidos.planes_de_visita, pedidos.orden_en_ruta, pedidos.visitas,
                        pedidos.v_ruta_de_hoy, pedidos.v_efectividad, pedidos.v_activos_sobre_codificados TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_toca_visita(SMALLINT, TEXT, DATE, DATE), pedidos.fn_nombre_del_dia(DATE),
                                  pedidos.fn_ruta_del_dia(BIGINT, DATE) TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_plan_de_visita_fijar(TEXT, SMALLINT, TEXT, DATE, TIME, TIME),
                                  pedidos.fn_orden_en_ruta_fijar(TEXT, TEXT[]),
                                  pedidos.fn_visita_registrar(TEXT, TEXT, TEXT, TEXT, TEXT, UUID, BOOLEAN, NUMERIC, NUMERIC, UUID, BIGINT, TIMESTAMPTZ, TEXT)
                                  TO app_user;
    END IF;
END
$permisos$;

-- ── 10 · Cierre ───────────────────────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v6p_a__';
    b CONSTANT TEXT := '__prueba_v6p_b__';
    v_ana BIGINT; v_luis BIGINT;
    v_martes CONSTANT DATE := DATE '2026-09-15';   -- martes
    v_r RECORD;
    v_rechazado BOOLEAN;
    v_n BIGINT;
    t RECORD; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    -- Guardas de catálogo: definer con search_path fijo y sin PUBLIC; app_user sin escritura directa.
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
                WHERE ns.nspname = 'pedidos' AND p.prosecdef
                  AND (NOT COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pedidos, public, pg_temp'], false)
                       OR has_function_privilege('public', p.oid, 'EXECUTE'))) THEN
        RAISE EXCEPTION 'V6 pedidos: una SECURITY DEFINER de pedidos sin search_path fijo o con EXECUTE para PUBLIC';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'pedidos.visitas', 'INSERT') OR has_table_privilege('app_user', 'pedidos.visitas', 'UPDATE')
            OR has_table_privilege('app_user', 'pedidos.planes_de_visita', 'INSERT') OR has_table_privilege('app_user', 'pedidos.planes_de_visita', 'UPDATE')
            OR has_table_privilege('app_user', 'pedidos.orden_en_ruta', 'INSERT') OR has_table_privilege('app_user', 'pedidos.orden_en_ruta', 'UPDATE')) THEN
        RAISE EXCEPTION 'V6 pedidos: app_user escribe la ruta sin pasar por las funciones';
    END IF;

    -- fn_toca_visita: el martes de la semana ancla toca; el siguiente, en quincenal no; el segundo martes del mes, en mensual.
    IF NOT pedidos.fn_toca_visita(2::smallint, 'SEMANAL', NULL, v_martes)
       OR pedidos.fn_toca_visita(1::smallint, 'SEMANAL', NULL, v_martes)
       OR NOT pedidos.fn_toca_visita(2::smallint, 'QUINCENAL', v_martes, v_martes)
       OR pedidos.fn_toca_visita(2::smallint, 'QUINCENAL', v_martes, v_martes + 7)
       OR NOT pedidos.fn_toca_visita(2::smallint, 'QUINCENAL', v_martes, v_martes + 14)
       OR NOT pedidos.fn_toca_visita(2::smallint, 'MENSUAL', DATE '2026-09-08', DATE '2026-10-13')
       OR pedidos.fn_toca_visita(2::smallint, 'MENSUAL', DATE '2026-09-08', DATE '2026-10-20') THEN
        RAISE EXCEPTION 'V6 pedidos: fn_toca_visita no respeta dias, quincena o semana del mes';
    END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V6 pedidos A', 'basico'), (b, 'Prueba V6 pedidos B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status) VALUES ('ana@v6p.invalid', '!', a, 'vendedor', 'disabled') RETURNING id INTO v_ana;
    INSERT INTO public.users (email, password_hash, tenant_id, role, status) VALUES ('luis@v6p.invalid', '!', a, 'vendedor', 'disabled') RETURNING id INTO v_luis;
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_ana::text, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, vendedor_id, creado_por) VALUES
        (a, 'v6-martes', 'Martes', v_ana, 'v6'), (a, 'v6-jueves', 'Jueves', v_ana, 'v6'), (a, 'v6-luis', 'De Luis', v_luis, 'v6');
    PERFORM pedidos.fn_plan_de_visita_fijar('v6-martes', 2::smallint, 'SEMANAL', NULL, NULL, NULL);
    PERFORM pedidos.fn_plan_de_visita_fijar('v6-jueves', 8::smallint, 'SEMANAL', NULL, NULL, NULL);
    PERFORM pedidos.fn_plan_de_visita_fijar('v6-luis', 2::smallint, 'SEMANAL', NULL, NULL, NULL);
    -- Re-fijar cierra la vigencia anterior: una sola vigente por cliente.
    PERFORM pedidos.fn_plan_de_visita_fijar('v6-martes', 2::smallint, 'SEMANAL', NULL, TIME '07:00', TIME '10:00');
    SELECT count(*) INTO v_n FROM pedidos.planes_de_visita WHERE tenant_id = a AND cliente_documento = 'v6-martes';
    IF v_n <> 2 OR (SELECT count(*) FROM pedidos.planes_de_visita WHERE tenant_id = a AND cliente_documento = 'v6-martes' AND vigente_hasta IS NULL) <> 1 THEN
        RAISE EXCEPTION 'V6 pedidos: re-fijar el plan no dejo una sola vigencia (% filas)', v_n;
    END IF;

    -- «Hecho cuando» (F6.1): la ruta del martes de Ana son sus clientes de los martes.
    SELECT array_agg(cliente_documento ORDER BY cliente_documento) AS docs INTO v_r FROM pedidos.fn_ruta_del_dia(v_ana, v_martes);
    IF v_r.docs IS DISTINCT FROM ARRAY['v6-martes'] THEN
        RAISE EXCEPTION 'V6 pedidos: la ruta del martes de Ana no son sus clientes de los martes (%)', v_r.docs;
    END IF;
    PERFORM pedidos.fn_orden_en_ruta_fijar('MARTES', ARRAY['v6-martes']);
    IF (SELECT posicion FROM pedidos.fn_ruta_del_dia(v_ana, v_martes)) <> 1 THEN
        RAISE EXCEPTION 'V6 pedidos: la ruta no trae la posicion fijada';
    END IF;

    -- «Hecho cuando» (F6.1): SIN_PEDIDO sin motivo se rechaza.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_visita_registrar('v6-martes', 'SIN_PEDIDO', NULL, NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'v6-sin-motivo');
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V6 pedidos: una visita SIN_PEDIDO sin motivo entro'; END IF;

    -- Visita normal, reintento idempotente, cliente de otro vendedor (D4), cliente nuevo (D5), referencia rechazada (D11).
    SELECT * INTO v_r FROM pedidos.fn_visita_registrar('v6-martes', 'SIN_PEDIDO', 'TIENE_SURTIDO', NULL, NULL, NULL, false, 4.6, -74.1, NULL, 7, now(), 'v6-1');
    IF v_r.repetida THEN RAISE EXCEPTION 'V6 pedidos: la primera visita salio repetida'; END IF;
    IF NOT (SELECT repetida FROM pedidos.fn_visita_registrar('v6-martes', 'SIN_PEDIDO', 'TIENE_SURTIDO', NULL, NULL, NULL, false, 4.6, -74.1, NULL, 7, now(), 'v6-1')) THEN
        RAISE EXCEPTION 'V6 pedidos: el reintento de la misma visita no salio repetido';
    END IF;
    PERFORM pedidos.fn_visita_registrar('v6-luis', 'CERRADO', NULL, NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'v6-2');
    PERFORM pedidos.fn_visita_registrar('900-nuevo', 'CLIENTE_NUEVO', NULL, NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'v6-3');
    PERFORM pedidos.fn_visita_registrar('v6-jueves', 'CON_PEDIDO', NULL, 'lote-pedido-rechazado', NULL, NULL, true, NULL, NULL, NULL, NULL, now(), 'v6-4');
    IF NOT EXISTS (SELECT 1 FROM pedidos.visitas WHERE tenant_id = a AND idempotency_key = 'v6-2' AND de_otro_vendedor)
       OR EXISTS (SELECT 1 FROM pedidos.visitas WHERE tenant_id = a AND idempotency_key IN ('v6-1', 'v6-3') AND de_otro_vendedor)
       OR NOT EXISTS (SELECT 1 FROM pedidos.visitas WHERE tenant_id = a AND idempotency_key = 'v6-4'
                        AND resultado = 'SIN_PEDIDO' AND motivo_sin_pedido = 'PEDIDO_RECHAZADO' AND referencia_rechazada) THEN
        RAISE EXCEPTION 'V6 pedidos: las marcas de otro vendedor, cliente nuevo o referencia rechazada no quedaron';
    END IF;
    -- Una referencia que no existe (y no fue rechazada) no entra; la misma clave con otro cliente, tampoco.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_visita_registrar('v6-jueves', 'CON_PEDIDO', NULL, 'no-existe', NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'v6-5');
    EXCEPTION WHEN raise_exception THEN v_rechazado := SQLERRM LIKE 'REFERENCIA_NO_ENCONTRADA%';
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V6 pedidos: una visita con una referencia que no existe entro'; END IF;
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_visita_registrar('v6-jueves', 'SIN_PEDIDO', 'TIENE_SURTIDO', NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'v6-1');
    EXCEPTION WHEN raise_exception THEN v_rechazado := SQLERRM LIKE 'IDEMPOTENCIA_REUTILIZADA%';
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V6 pedidos: la misma clave con otro cliente entro'; END IF;

    -- Efectividad de hoy de Ana: sus 4 visitas (v6-1, v6-2 al cliente de Luis, v6-3 nuevo, v6-4 rechazada) y ninguna con pedido.
    IF (SELECT visitas FROM pedidos.v_efectividad WHERE tenant_id = a AND vendedor_id = v_ana) <> 4
       OR (SELECT con_pedido FROM pedidos.v_efectividad WHERE tenant_id = a AND vendedor_id = v_ana) <> 0 THEN
        RAISE EXCEPTION 'V6 pedidos: v_efectividad no cuenta las visitas de Ana';
    END IF;
    -- Rastro cero.
    DELETE FROM pedidos.visitas WHERE tenant_id = a;
    DELETE FROM pedidos.orden_en_ruta WHERE tenant_id = a;
    DELETE FROM pedidos.planes_de_visita WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos WHERE tenant_id = a;
    DELETE FROM public.clientes WHERE tenant_id = a;
    DELETE FROM public.users WHERE tenant_id = a;
    DELETE FROM public.tenants WHERE id IN (a, b);
    FOR t IN SELECT c.table_schema, c.table_name FROM information_schema.columns c
              JOIN information_schema.tables x ON x.table_schema = c.table_schema AND x.table_name = c.table_name
             WHERE c.column_name = 'tenant_id' AND c.table_schema IN ('public', 'inventario', 'pedidos') AND x.table_type = 'BASE TABLE' LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id IN ($1, $2)', t.table_schema, t.table_name) INTO v_n USING a, b;
        IF v_n > 0 THEN
            v_quedan := v_quedan + v_n;
            v_donde := v_donde || ' ' || t.table_schema || '.' || t.table_name || '=' || v_n;
        END IF;
    END LOOP;
    IF v_quedan > 0 THEN
        RAISE EXCEPTION 'V6 pedidos: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;
    RAISE NOTICE 'V6 pedidos: plan por vigencia, ruta del martes, orden, SIN_PEDIDO sin motivo rechazada, idempotencia, D4, D5, D10/D11, efectividad; definer y permisos; 0 restos';
END $cierre$;
