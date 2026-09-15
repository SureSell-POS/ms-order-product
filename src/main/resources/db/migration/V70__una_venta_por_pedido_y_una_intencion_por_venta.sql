-- =====================================================================
-- V70 -- Un pedido despachado es UNA venta, y una venta es UNA intención de
--        inventario, sea cual sea la forma de su clave.
--
-- Plan de mayoristas F5.5a (diseño aprobado por ECM, 2026-09-14).
--
-- 1. `ux_orders_pedido`: la venta que nace al despachar un pedido (F5.5) lleva
--    `orders.pedido_id`, que es la ÚNICA fuente del enlace pedido → venta (ECM:
--    el pedido no guarda copia). Un segundo despacho no puede crear otra venta
--    del mismo pedido; hasta que exista la reversa (D7), la API lo responde 409
--    REVERSA_PENDIENTE y este índice es el suelo. Las ventas borradas
--    (`deleted_at`) no cuentan.
--
-- 2. `ux_int_orden_uuid`: la clave de la intención deja de ser `orden-<id_order>`
--    y pasa a `venta-<uuid>` (RegistroDeIntencionDeInventario). El número de
--    orden vuelve a empezar en cada sede (V28), así que en un negocio con dos
--    sedes la segunda «orden-7» se tomaba por repetida y no descontaba. Durante
--    el cambio conviven las dos formas; este índice garantiza que ninguna venta
--    tenga dos intenciones, una con cada forma. `orden_uuid` NULL (intenciones
--    escritas a mano en pruebas) no choca.
--
-- Medido antes de escribirla (staging, 2026-09-15): 26 intenciones, 0 sin
-- `orden_uuid`, 0 repetidas por `orden_uuid`; 0 ventas con `pedido_id`.
-- Producción: 0 negocios con más de una sede (C). La guarda de abajo NO crea
-- nada si en la base donde corre hay repetidos: se para y lo dice.
-- =====================================================================

-- ── 0 · Guarda: sin repetidos que el índice rechazaría ────────────────
DO $guarda$
DECLARE
    v_pedidos BIGINT;
    v_intenciones BIGINT;
BEGIN
    SELECT count(*) INTO v_pedidos FROM (
        SELECT tenant_id, pedido_id FROM public.orders
         WHERE pedido_id IS NOT NULL AND deleted_at IS NULL
         GROUP BY tenant_id, pedido_id HAVING count(*) > 1) x;
    SELECT count(*) INTO v_intenciones FROM (
        SELECT tenant_id, orden_uuid FROM public.inventario_intenciones
         WHERE orden_uuid IS NOT NULL
         GROUP BY tenant_id, orden_uuid HAVING count(*) > 1) y;
    IF v_pedidos > 0 OR v_intenciones > 0 THEN
        RAISE EXCEPTION 'V70: hay % pedidos con mas de una venta y % ventas con mas de una intencion. No se crea nada: hay que mirarlo antes',
            v_pedidos, v_intenciones;
    END IF;
END $guarda$;

-- ── 1 · Los dos índices ──────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_pedido
    ON public.orders (tenant_id, pedido_id)
 WHERE pedido_id IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_int_orden_uuid
    ON public.inventario_intenciones (tenant_id, orden_uuid)
 WHERE orden_uuid IS NOT NULL;

-- ── 2 · Cierre, por comportamiento ────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v70_a__';
    b CONSTANT TEXT := '__prueba_v70_b__';
    v_pedido UUID := gen_random_uuid();
    v_venta UUID := gen_random_uuid();
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V70 A', 'basico'), (b, 'Prueba V70 B', 'basico');

    -- Una venta por pedido.
    INSERT INTO public.orders (uuid_id, tenant_id, status, pedido_id, origen) VALUES (gen_random_uuid(), a, 'pagado', v_pedido, 'pedido');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, pedido_id, origen) VALUES (gen_random_uuid(), a, 'pagado', v_pedido, 'pedido');
    EXCEPTION WHEN unique_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V70: un pedido tuvo dos ventas'; END IF;
    -- Pero: la venta borrada no cuenta, otro negocio con el mismo id de pedido tampoco, y sin pedido no hay límite.
    UPDATE public.orders SET deleted_at = now() WHERE tenant_id = a AND pedido_id = v_pedido;
    INSERT INTO public.orders (uuid_id, tenant_id, status, pedido_id, origen) VALUES (gen_random_uuid(), a, 'pagado', v_pedido, 'pedido');
    INSERT INTO public.orders (uuid_id, tenant_id, status, pedido_id, origen) VALUES (gen_random_uuid(), b, 'pagado', v_pedido, 'pedido');
    INSERT INTO public.orders (uuid_id, tenant_id, status) VALUES (gen_random_uuid(), a, 'pagado'), (gen_random_uuid(), a, 'pagado');

    -- Una intención por venta, aunque la clave tenga otra forma.
    INSERT INTO public.inventario_intenciones (tenant_id, orden_id, orden_uuid, ocurrido_en, lineas, idempotency_key)
    VALUES (a, 1, v_venta, now(), '[]'::jsonb, 'orden-1');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.inventario_intenciones (tenant_id, orden_id, orden_uuid, ocurrido_en, lineas, idempotency_key)
        VALUES (a, 1, v_venta, now(), '[]'::jsonb, 'venta-' || v_venta);
    EXCEPTION WHEN unique_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V70: una venta tuvo dos intenciones, una con cada forma de clave'; END IF;
    -- Dos sedes, el mismo número de orden, dos ventas distintas: las dos entran con la clave nueva.
    INSERT INTO public.inventario_intenciones (tenant_id, orden_id, orden_uuid, ocurrido_en, lineas, idempotency_key)
    VALUES (a, 7, gen_random_uuid(), now(), '[]'::jsonb, 'venta-sede-1'), (a, 7, gen_random_uuid(), now(), '[]'::jsonb, 'venta-sede-2');
    INSERT INTO public.inventario_intenciones (tenant_id, orden_id, orden_uuid, ocurrido_en, lineas, idempotency_key)
    VALUES (a, 8, NULL, now(), '[]'::jsonb, 'sin-uuid-1'), (a, 8, NULL, now(), '[]'::jsonb, 'sin-uuid-2');

    -- Limpieza: lo insertado y lo que provocó el disparador de numeración (sede y contador).
    DELETE FROM public.inventario_intenciones WHERE tenant_id IN (a, b);
    DELETE FROM public.orders                 WHERE tenant_id IN (a, b);
    DELETE FROM public.tenant_order_counters  WHERE tenant_id IN (a, b);
    DELETE FROM public.sites                  WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants                WHERE id IN (a, b);

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v70%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v70%') THEN
        RAISE EXCEPTION 'V70: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;
    IF NOT ('__prueba_v70_a__' LIKE '\_\_prueba\_v70%') OR ('xxprueba_v70_a__' LIKE '\_\_prueba\_v70%') THEN
        RAISE EXCEPTION 'V70: el patron del barrido no distingue el marcador';
    END IF;

    RAISE NOTICE 'V70: una venta por pedido (la borrada no cuenta, otro negocio tampoco); una intencion por venta con clave orden- o venta-; dos sedes con el mismo numero entran; 0 restos';
END $cierre$;
