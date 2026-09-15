-- =====================================================================
-- V4 pedidos -- La entrega y su prueba (plan de mayoristas F5.7, diseño
--               aprobado por ECM el 2026-09-15).
--
-- 1. `pedidos.entregas`: quién recibió (nombre y documento), dónde (lat/long
--    opcional) y cuándo, por cada evento ENTREGADO, ENTREGADO_CON_NOVEDAD o
--    ENTREGA_FALLIDA. `foto_asset_id` y `firma_asset_id` existen para no migrar
--    después, pero la API no los acepta hasta F5.7b (almacenamiento que decide C):
--    una referencia que no apunta a nada es un dato falso con forma de prueba.
--
-- 2. `pedidos.fn_pedido_entregar`: la ÚNICA forma de entregar. Mueve el pedido con
--    `fn_pedido_transicionar` (catálogo, bloqueo, idempotencia, cantidades) y
--    escribe la prueba en la misma sentencia. Rechaza entregar en una línea más de
--    lo despachado: la venta se hizo por lo despachado (F5.5).
--    SECURITY DEFINER con las reglas de V2: search_path fijo con pg_temp al final,
--    sin EXECUTE para PUBLIC, y el filtro de negocio escrito (dentro, RLS NO es suelo).
--
-- 3. `trg_evento_de_entrega_con_prueba`: disparador de restricción DIFERIDO. Todo
--    evento de entrega tiene su fila en `entregas` con el mismo resultado al
--    confirmar la transacción. Llamar a `fn_pedido_transicionar` con ENTREGADO a
--    pelo ya no deja una entrega sin prueba.
--
-- 4. `v_pedidos_pendiente_de_reversa`: DERIVADA, no columna (ECM). Lo que salió
--    con la venta y no llegó: Σ (despachada − entregada) × precio congelado, en los
--    pedidos ya entregados o con entrega fallida (ahí la entregada es 0: todo). Sin
--    F7/D7 no se devuelve inventario ni se toca la cartera; esto lo deja a la vista.
--    Cuando exista la reversa, la vista restará lo revertido: no hay marca que borrar.
-- =====================================================================

CREATE TABLE pedidos.entregas (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        TEXT          NOT NULL,
    pedido_id        UUID          NOT NULL,
    evento_id        UUID          NOT NULL,
    resultado        TEXT          NOT NULL,
    recibe_nombre    TEXT          NULL,
    recibe_documento TEXT          NULL,
    foto_asset_id    TEXT          NULL,
    firma_asset_id   TEXT          NULL,
    latitud          NUMERIC(9,6)  NULL,
    longitud         NUMERIC(9,6)  NULL,
    registrado_por   BIGINT        NOT NULL REFERENCES public.users (id),
    ocurrido_en      TIMESTAMPTZ   NOT NULL,
    registrado_en    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_entregas PRIMARY KEY (id),
    CONSTRAINT ux_entregas_evento UNIQUE (tenant_id, evento_id),
    -- El evento es de ESE pedido y de ese negocio.
    CONSTRAINT fk_entregas_evento FOREIGN KEY (tenant_id, pedido_id, evento_id)
        REFERENCES pedidos.pedidos_eventos (tenant_id, pedido_id, id),
    CONSTRAINT ck_entregas_resultado CHECK (resultado IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA')),
    -- Una entrega hecha dice quién recibió; una fallida no tiene a nadie.
    CONSTRAINT ck_entregas_quien_recibe CHECK (
        (resultado = 'ENTREGA_FALLIDA' AND recibe_nombre IS NULL AND recibe_documento IS NULL)
        OR (resultado <> 'ENTREGA_FALLIDA' AND length(btrim(COALESCE(recibe_nombre, ''))) > 0
            AND length(btrim(COALESCE(recibe_documento, ''))) > 0)),
    CONSTRAINT ck_entregas_lugar CHECK ((latitud IS NULL) = (longitud IS NULL)
        AND (latitud IS NULL OR (latitud BETWEEN -90 AND 90 AND longitud BETWEEN -180 AND 180))),
    CONSTRAINT ck_entregas_reloj CHECK (ocurrido_en <= registrado_en + interval '5 minutes')
);
CREATE INDEX ix_entregas_pedido ON pedidos.entregas (tenant_id, pedido_id);
-- Solo las entregas con diferencias pueden dejar algo pendiente de reversa: la vista parte de aquí.
CREATE INDEX ix_entregas_con_diferencia ON pedidos.entregas (tenant_id, pedido_id) WHERE resultado <> 'ENTREGADO';
COMMENT ON TABLE pedidos.entregas IS
    'Prueba de entrega por evento (INV-P §1.11). La escribe solo fn_pedido_entregar. Foto y firma, desde F5.7b. V4 pedidos.';

ALTER TABLE pedidos.entregas ENABLE ROW LEVEL SECURITY;
ALTER TABLE pedidos.entregas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_entregas ON pedidos.entregas
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ── La única forma de entregar ──────────────────────────────────────
CREATE FUNCTION pedidos.fn_pedido_entregar(p_pedido_id UUID, p_resultado TEXT, p_motivo TEXT, p_nota TEXT, p_lineas JSONB,
                                           p_recibe_nombre TEXT, p_recibe_documento TEXT, p_latitud NUMERIC, p_longitud NUMERIC,
                                           p_ocurrido_en TIMESTAMPTZ, p_idempotency_key TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_evento UUID;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_resultado IS NULL OR p_resultado NOT IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA') THEN
        RAISE EXCEPTION 'Una entrega es ENTREGADO, ENTREGADO_CON_NOVEDAD o ENTREGA_FALLIDA, no %.', p_resultado USING ERRCODE = 'P0001';
    END IF;

    -- Catálogo, bloqueo del pedido (de ESTE negocio), idempotencia, motivo y cantidades.
    v_evento := pedidos.fn_pedido_transicionar(p_pedido_id, p_resultado, p_motivo, p_nota, p_lineas, p_ocurrido_en, p_idempotency_key);

    -- El reintento: la prueba ya está escrita.
    IF EXISTS (SELECT 1 FROM pedidos.entregas en WHERE en.tenant_id = s.negocio AND en.evento_id = v_evento) THEN
        RETURN v_evento;
    END IF;

    IF EXISTS (SELECT 1 FROM pedidos.v_pedidos_lineas v
                WHERE v.tenant_id = s.negocio AND v.pedido_id = p_pedido_id
                  AND COALESCE(v.entregada, 0) > COALESCE(v.despachada, 0)) THEN
        RAISE EXCEPTION 'Una linea no puede entregar mas de lo que se despacho.' USING ERRCODE = 'P0001';
    END IF;
    -- ENTREGADO es entregar TODO lo despachado; una diferencia se dice como ENTREGADO_CON_NOVEDAD, con
    -- su motivo. Así solo las entregas con diferencias pueden dejar algo pendiente de reversa.
    IF p_resultado = 'ENTREGADO' AND EXISTS (SELECT 1 FROM pedidos.v_pedidos_lineas v
                WHERE v.tenant_id = s.negocio AND v.pedido_id = p_pedido_id
                  AND COALESCE(v.entregada, 0) <> COALESCE(v.despachada, 0)) THEN
        RAISE EXCEPTION 'Una entrega con diferencias es ENTREGADO_CON_NOVEDAD, con su motivo.' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO pedidos.entregas (tenant_id, pedido_id, evento_id, resultado, recibe_nombre, recibe_documento,
                                  latitud, longitud, registrado_por, ocurrido_en)
    VALUES (s.negocio, p_pedido_id, v_evento, p_resultado,
            CASE WHEN p_resultado = 'ENTREGA_FALLIDA' THEN NULL ELSE btrim(p_recibe_nombre) END,
            CASE WHEN p_resultado = 'ENTREGA_FALLIDA' THEN NULL ELSE btrim(p_recibe_documento) END,
            p_latitud, p_longitud, s.usuario, p_ocurrido_en);
    RETURN v_evento;
END $$;

-- ── Todo evento de entrega tiene su prueba ──────────────────────────
CREATE FUNCTION pedidos.fn_evento_de_entrega_con_prueba()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pedidos, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pedidos.entregas en
                    WHERE en.tenant_id = NEW.tenant_id AND en.evento_id = NEW.id AND en.resultado = NEW.tipo) THEN
        RAISE EXCEPTION 'El evento % del pedido % no tiene su prueba de entrega: se entrega con fn_pedido_entregar.',
            NEW.tipo, NEW.pedido_id USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_evento_de_entrega_con_prueba
    AFTER INSERT ON pedidos.pedidos_eventos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA'))
    EXECUTE FUNCTION pedidos.fn_evento_de_entrega_con_prueba();

-- ── Lo que salió con la venta y no llegó ─────────────────────────────
CREATE VIEW pedidos.v_pedidos_pendiente_de_reversa WITH (security_invoker = true) AS
SELECT p.tenant_id, p.id AS pedido_id, p.estado, t.lineas_pendientes, t.valor
  -- Parte de las entregas con diferencias (novedad o fallida), que son pocas, y no de todo lo
  -- entregado, que crece con el historial. CostoDelPedidoTest (2 negocios × 7.000 entregados y 100
  -- con novedad, sin JIT como staging): el conteo 11,7 ms; partiendo de los pedidos entregados, el
  -- plan recorría los 7.000 (421 ms con JIT encendido; sin JIT no se midió esa forma).
  FROM (SELECT DISTINCT en.tenant_id, en.pedido_id FROM pedidos.entregas en WHERE en.resultado <> 'ENTREGADO') d
  JOIN pedidos.pedidos p ON p.tenant_id = d.tenant_id AND p.id = d.pedido_id
  CROSS JOIN LATERAL (
        SELECT count(*) FILTER (WHERE COALESCE(v.despachada, 0) > COALESCE(v.entregada, 0)) AS lineas_pendientes,
               COALESCE(sum(GREATEST(COALESCE(v.despachada, 0) - COALESCE(v.entregada, 0), 0)
                            * COALESCE(v.precio_confirmado, v.precio_visto)), 0) AS valor
          FROM pedidos.v_pedidos_lineas v
         WHERE v.tenant_id = p.tenant_id AND v.pedido_id = p.id) t
 WHERE p.estado IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA', 'RECIBIDO', 'RECIBIDO_CON_NOVEDAD')
   AND t.valor > 0;
COMMENT ON VIEW pedidos.v_pedidos_pendiente_de_reversa IS
    'Lo despachado (y vendido, F5.5) que no se entrego, por el precio congelado. Solo pedidos con valor pendiente. Sin F7/D7 no se revierte nada. V4 pedidos.';

REVOKE ALL ON FUNCTION pedidos.fn_pedido_entregar(UUID, TEXT, TEXT, TEXT, JSONB, TEXT, TEXT, NUMERIC, NUMERIC, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_evento_de_entrega_con_prueba() FROM PUBLIC;

DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON pedidos.entregas, pedidos.v_pedidos_pendiente_de_reversa TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_pedido_entregar(UUID, TEXT, TEXT, TEXT, JSONB, TEXT, TEXT, NUMERIC, NUMERIC, TIMESTAMPTZ, TEXT) TO app_user;
    END IF;
END
$permisos$;

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v4p_a__';
    v_admin BIGINT;
    v_novedad UUID; v_mas UUID; v_fallida UUID; v_pelo UUID;
    v_linea UUID;
    v_e1 UUID; v_e2 UUID;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- DEFINER: tres en pedidos, todas con search_path fijo y sin EXECUTE para PUBLIC.
    IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
         WHERE ns.nspname = 'pedidos' AND p.prosecdef) <> 3
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
                   WHERE ns.nspname = 'pedidos' AND p.prosecdef
                     AND (NOT COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pedidos, public, pg_temp'], false)
                          OR has_function_privilege('public', p.oid, 'EXECUTE'))) THEN
        RAISE EXCEPTION 'V4 pedidos: las SECURITY DEFINER de pedidos no son tres con search_path fijo y sin EXECUTE para PUBLIC';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
                WHERE ns.nspname = 'pedidos' AND c.relkind = 'v'
                  AND NOT COALESCE(c.reloptions @> ARRAY['security_invoker=true'], false)) THEN
        RAISE EXCEPTION 'V4 pedidos: una vista del pedido no tiene security_invoker';
    END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V4 pedidos', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v4p-a@prueba.invalid', '!', a, 'admin') RETURNING id INTO v_admin;
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active) VALUES ('v4p-arroz', a, 'Arroz', 5000, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v4p-tienda', 'Tienda', 8, 'v4p');
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_admin::text, true);

    -- 1. Entregar 7 de 10 con novedad: prueba escrita, 3 × 5000 pendientes de reversa; el reintento no duplica.
    v_novedad := pedidos.fn_pedido_crear('ENVIADO', 'v4p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v4p-arroz","cantidad":10}]', now() - interval '1 hour', 'v4p-1');
    SELECT id INTO v_linea FROM pedidos.pedidos_lineas WHERE pedido_id = v_novedad;
    PERFORM pedidos.fn_pedido_transicionar(v_novedad, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v4p-1-c');
    PERFORM pedidos.fn_pedido_transicionar(v_novedad, 'DESPACHADO', NULL, NULL, NULL, now(), 'v4p-1-d');
    v_e1 := pedidos.fn_pedido_entregar(v_novedad, 'ENTREGADO_CON_NOVEDAD', 'FALTANTE', NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 7)), 'Maria', '52000111', 4.6, -74.08, now(), 'v4p-1-e');
    v_e2 := pedidos.fn_pedido_entregar(v_novedad, 'ENTREGADO_CON_NOVEDAD', 'FALTANTE', NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 7)), 'Maria', '52000111', 4.6, -74.08, now(), 'v4p-1-e');
    IF v_e1 <> v_e2
       OR (SELECT count(*) FROM pedidos.entregas WHERE tenant_id = a AND pedido_id = v_novedad) <> 1
       OR (SELECT recibe_documento FROM pedidos.entregas WHERE evento_id = v_e1) <> '52000111'
       OR (SELECT valor FROM pedidos.v_pedidos_pendiente_de_reversa WHERE tenant_id = a AND pedido_id = v_novedad) <> 15000
       OR (SELECT entregado_en FROM pedidos.pedidos WHERE id = v_novedad) IS NULL THEN
        RAISE EXCEPTION 'V4 pedidos: entregar 7 de 10 no dejo una prueba, 15000 pendientes y entregado_en';
    END IF;

    -- 2. Más de lo despachado → P0001, y nada queda.
    v_mas := pedidos.fn_pedido_crear('ENVIADO', 'v4p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v4p-arroz","cantidad":5}]', now() - interval '1 hour', 'v4p-2');
    SELECT id INTO v_linea FROM pedidos.pedidos_lineas WHERE pedido_id = v_mas;
    PERFORM pedidos.fn_pedido_transicionar(v_mas, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v4p-2-c');
    PERFORM pedidos.fn_pedido_transicionar(v_mas, 'DESPACHADO', NULL, NULL, NULL, now(), 'v4p-2-d');
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_entregar(v_mas, 'ENTREGADO', NULL, NULL,
            jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 6)), 'Maria', '52000111', NULL, NULL, now(), 'v4p-2-e');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado OR (SELECT estado FROM pedidos.pedidos WHERE id = v_mas) <> 'DESPACHADO' THEN
        RAISE EXCEPTION 'V4 pedidos: se entrego mas de lo despachado';
    END IF;
    -- 2b. ENTREGADO con menos de lo despachado → P0001: eso es una novedad.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_entregar(v_mas, 'ENTREGADO', NULL, NULL,
            jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 4)), 'Maria', '52000111', NULL, NULL, now(), 'v4p-2-e2');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V4 pedidos: un ENTREGADO con menos de lo despachado no se dijo como novedad'; END IF;

    -- 3. Entrega fallida: sin quien recibe, y todo lo despachado queda pendiente de reversa.
    v_fallida := pedidos.fn_pedido_crear('ENVIADO', 'v4p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v4p-arroz","cantidad":4}]', now() - interval '1 hour', 'v4p-3');
    PERFORM pedidos.fn_pedido_transicionar(v_fallida, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v4p-3-c');
    PERFORM pedidos.fn_pedido_transicionar(v_fallida, 'DESPACHADO', NULL, NULL, NULL, now(), 'v4p-3-d');
    PERFORM pedidos.fn_pedido_entregar(v_fallida, 'ENTREGA_FALLIDA', 'CERRADO', NULL, NULL, 'no', 'aplica', NULL, NULL, now(), 'v4p-3-e');
    IF (SELECT valor FROM pedidos.v_pedidos_pendiente_de_reversa WHERE tenant_id = a AND pedido_id = v_fallida) <> 20000
       OR (SELECT recibe_nombre FROM pedidos.entregas WHERE tenant_id = a AND pedido_id = v_fallida) IS NOT NULL
       OR EXISTS (SELECT 1 FROM pedidos.v_pedidos_pendiente_de_reversa WHERE tenant_id = a AND pedido_id = v_mas) THEN
        RAISE EXCEPTION 'V4 pedidos: la entrega fallida no dejo todo pendiente (20000), o un pedido sin entregar aparecio pendiente';
    END IF;

    -- 4. Un ENTREGADO a pelo por fn_pedido_transicionar, sin prueba → P0001 del disparador.
    v_pelo := pedidos.fn_pedido_crear('ENVIADO', 'v4p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v4p-arroz","cantidad":1}]', now() - interval '1 hour', 'v4p-4');
    PERFORM pedidos.fn_pedido_transicionar(v_pelo, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v4p-4-c');
    PERFORM pedidos.fn_pedido_transicionar(v_pelo, 'DESPACHADO', NULL, NULL, NULL, now(), 'v4p-4-d');
    -- El disparador es DIFERIDO (se comprueba al confirmar). Aquí se pone inmediato solo para este
    -- caso: al cambiarlo se comprueban también los eventos de 1-3, que sí tienen su prueba. No sirve
    -- inmediato alrededor de fn_pedido_entregar: dispararía al final del INSERT del evento, antes de
    -- escribir la prueba en la misma función.
    SET CONSTRAINTS pedidos.trg_evento_de_entrega_con_prueba IMMEDIATE;
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_pelo, 'ENTREGADO', NULL, NULL, NULL, now(), 'v4p-4-e');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V4 pedidos: un ENTREGADO entro sin su prueba de entrega'; END IF;

    IF EXISTS (SELECT 1 FROM pedidos.v_pedidos_estado WHERE tenant_id = a AND estado_guardado IS DISTINCT FROM estado_derivado) THEN
        RAISE EXCEPTION 'V4 pedidos: el estado guardado no coincide con el derivado';
    END IF;

    SET CONSTRAINTS pedidos.trg_evento_de_entrega_con_prueba DEFERRED;

    -- Limpieza (hijos antes que padres) y barrido de todas las tablas con tenant_id.
    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM pedidos.entregas               WHERE tenant_id = a;
    DELETE FROM pedidos.pedidos_eventos_lineas WHERE tenant_id = a;
    DELETE FROM pedidos.pedidos_eventos        WHERE tenant_id = a;
    DELETE FROM pedidos.pedidos_lineas         WHERE tenant_id = a;
    DELETE FROM pedidos.pedidos                WHERE tenant_id = a;
    DELETE FROM pedidos.contadores_de_pedidos  WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos        WHERE tenant_id = a;
    DELETE FROM public.clientes                WHERE tenant_id = a;
    DELETE FROM public.menu_products           WHERE tenant_id = a;
    DELETE FROM public.users                   WHERE tenant_id = a;
    DELETE FROM public.tenants                 WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text = $1', t.s, t.tn) INTO v_filas USING a;
        IF v_filas > 0 THEN
            quedan := quedan + v_filas;
            donde := donde || t.s || '.' || t.tn || '(' || v_filas || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V4 pedidos: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V4 pedidos: 7 de 10 con prueba y 15000 pendientes, idempotente; mas de lo despachado y ENTREGADO con diferencias rechazados; fallida deja todo pendiente; ENTREGADO sin prueba rechazado; tres DEFINER con search_path; 0 restos.';
END
$cierre$;
