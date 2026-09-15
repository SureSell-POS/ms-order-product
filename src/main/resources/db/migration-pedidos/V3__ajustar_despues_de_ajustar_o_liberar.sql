-- =====================================================================
-- V3 pedidos -- Un pedido ajustado, o liberado tras una retención, se puede
--               volver a ajustar antes de confirmarlo.
--
-- Hueco del catálogo de V2 que encontró DevEspecializadoEnB2BYpedidos al
-- decidir los botones del panel: desde AJUSTADO y desde LIBERADO solo había
-- → CONFIRMADO y → CANCELADO. El caso real: un pedido RETENIDO por mora se
-- LIBERA porque pagaron y, al confirmarlo, falta una referencia. `confirmar`
-- con una línea menos escribe AJUSTADO y luego CONFIRMADO, y el primer paso
-- se rechazaba: la única salida era cancelar el pedido entero.
--
-- Aprobado por ECM (2026-09-14): AJUSTADO→AJUSTADO y LIBERADO→AJUSTADO, del
-- PROVEEDOR. NO CONFIRMADO→AJUSTADO: confirmado el pedido, el faltante se dice
-- en el despacho (DESPACHADO lleva sus cantidades por línea) y el precio ya
-- está congelado.
--
-- Solo filas del catálogo: ni tablas, ni funciones, ni permisos. AJUSTADO ya
-- lleva a CONFIRMADO y a CANCELADO, así que no se abre ningún otro camino.
-- =====================================================================

INSERT INTO pedidos.transiciones (desde, hacia, actor) VALUES
    ('AJUSTADO', 'AJUSTADO', 'PROVEEDOR'),
    ('LIBERADO', 'AJUSTADO', 'PROVEEDOR');

-- =====================================================================
-- La comprobación, por comportamiento, con las dos funciones de V2.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v3p_a__';
    v_admin BIGINT;
    v_retenido UUID; v_ajustado UUID; v_confirmado UUID;
    v_linea UUID;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- El catálogo tiene las dos filas nuevas y NO la que no se aprobó.
    IF (SELECT count(*) FROM pedidos.transiciones WHERE hacia = 'AJUSTADO' AND actor = 'PROVEEDOR'
          AND desde IN ('AJUSTADO', 'LIBERADO')) <> 2
       OR EXISTS (SELECT 1 FROM pedidos.transiciones WHERE desde = 'CONFIRMADO' AND hacia = 'AJUSTADO') THEN
        RAISE EXCEPTION 'V3 pedidos: el catalogo no quedo con AJUSTADO→AJUSTADO y LIBERADO→AJUSTADO, y sin CONFIRMADO→AJUSTADO';
    END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V3 pedidos', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v3p-a@prueba.invalid', '!', a, 'admin') RETURNING id INTO v_admin;
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('v3p-aceite', a, 'Aceite', 10000, true), ('v3p-arroz', a, 'Arroz', 5000, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v3p-tienda', 'Tienda', 8, 'v3p');
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_admin::text, true);

    -- 1. RETENIDO → LIBERADO → confirmar con una línea menos: AJUSTADO + CONFIRMADO.
    v_retenido := pedidos.fn_pedido_crear('ENVIADO', 'v3p-tienda', 'vendedor', 'PREVENTA', v_admin, NULL, NULL,
        '[{"producto_id":"v3p-aceite","cantidad":10},{"producto_id":"v3p-arroz","cantidad":4}]', now(), 'v3p-1');
    SELECT id INTO v_linea FROM pedidos.pedidos_lineas WHERE pedido_id = v_retenido AND n = 2;
    PERFORM pedidos.fn_pedido_transicionar(v_retenido, 'RETENIDO', 'MORA', NULL, NULL, now(), 'v3p-1-retiene');
    PERFORM pedidos.fn_pedido_transicionar(v_retenido, 'LIBERADO', 'PAGO_RECIBIDO', NULL, NULL, now(), 'v3p-1-libera');
    PERFORM pedidos.fn_pedido_transicionar(v_retenido, 'AJUSTADO', 'SIN_EXISTENCIA', NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 0)), now(), 'v3p-1-ajusta');
    PERFORM pedidos.fn_pedido_transicionar(v_retenido, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v3p-1-confirma');
    IF (SELECT string_agg(tipo, '>' ORDER BY secuencia) FROM pedidos.pedidos_eventos WHERE tenant_id = a AND pedido_id = v_retenido)
         <> 'ENVIADO>RETENIDO>LIBERADO>AJUSTADO>CONFIRMADO'
       OR (SELECT confirmada FROM pedidos.v_pedidos_lineas WHERE tenant_id = a AND linea_id = v_linea) <> 0
       OR (SELECT estado FROM pedidos.pedidos WHERE tenant_id = a AND id = v_retenido) <> 'CONFIRMADO' THEN
        RAISE EXCEPTION 'V3 pedidos: un pedido liberado no se pudo confirmar con una linea menos';
    END IF;

    -- 2. Dos ajustes seguidos: el segundo manda sobre el primero.
    v_ajustado := pedidos.fn_pedido_crear('ENVIADO', 'v3p-tienda', 'televenta', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v3p-aceite","cantidad":10}]', now(), 'v3p-2');
    SELECT id INTO v_linea FROM pedidos.pedidos_lineas WHERE pedido_id = v_ajustado;
    PERFORM pedidos.fn_pedido_transicionar(v_ajustado, 'AJUSTADO', NULL, NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 8)), now(), 'v3p-2-ajusta-1');
    PERFORM pedidos.fn_pedido_transicionar(v_ajustado, 'AJUSTADO', NULL, NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 6)), now(), 'v3p-2-ajusta-2');
    PERFORM pedidos.fn_pedido_transicionar(v_ajustado, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v3p-2-confirma');
    IF (SELECT confirmada FROM pedidos.v_pedidos_lineas WHERE tenant_id = a AND linea_id = v_linea) <> 6 THEN
        RAISE EXCEPTION 'V3 pedidos: el segundo ajuste no quedo como la cantidad confirmada';
    END IF;

    -- 3. Lo que no se aprobó sigue cerrado: CONFIRMADO → AJUSTADO es P0001.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_ajustado, 'AJUSTADO', NULL, NULL,
            jsonb_build_array(jsonb_build_object('linea_id', v_linea, 'cantidad', 5)), now(), 'v3p-2-ajusta-confirmado');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V3 pedidos: un pedido confirmado se dejo ajustar'; END IF;

    -- 4. Estado guardado = derivado en todo lo que se movió aquí.
    IF EXISTS (SELECT 1 FROM pedidos.v_pedidos_estado WHERE tenant_id = a AND estado_guardado IS DISTINCT FROM estado_derivado) THEN
        RAISE EXCEPTION 'V3 pedidos: el estado guardado no coincide con el derivado';
    END IF;

    -- Limpieza (hijos antes que padres) y barrido de todas las tablas con tenant_id.
    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
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
        RAISE EXCEPTION 'V3 pedidos: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V3 pedidos: liberado → ajustado → confirmado con una linea menos, dos ajustes seguidos, confirmado sin reajuste; 0 restos.';
END
$cierre$;
