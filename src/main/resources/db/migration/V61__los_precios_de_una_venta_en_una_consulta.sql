-- =====================================================================
-- V61 -- Los precios de una venta, en UNA consulta.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F1.5.
--
-- ── El N+1 ────────────────────────────────────────────────────────────
--
-- `ResolucionDePrecios` llama a `fn_precio_para` una vez por producto de la
-- venta (anexo A-4 del plan). Un pedido de mayorista trae 20, 50 líneas: 50
-- viajes a la base desde Railway us-west al pooler de Supabase, que se miden
-- en segundos. Y la regla de costo (R14) prohíbe exactamente eso.
--
-- `fn_precios_para` resuelve todas las líneas en un solo plan: el cliente y su
-- lista una vez, y para cada producto la línea vigente con la mayor escala
-- alcanzada o el precio BASE del catálogo.
--
-- ── Una regla, dos formas, y la prueba que las ata ────────────────────
--
-- `fn_precio_para` (V45, con el filtro por negocio de V59) NO se toca: la
-- siguen usando la venta del mesero y `/api/mayorista/precio`. Las dos
-- funciones son la misma regla escrita dos veces, y eso solo es aceptable
-- con una prueba de paridad que las compare línea a línea sobre escalas,
-- vigencias, listas inactivas, clientes sin lista, productos inexistentes y
-- negocios distintos: `PreciosEnLoteTest`. El bloque de cierre de abajo hace
-- lo mismo en pequeño contra la base donde se aplica.
--
-- Mismo contrato que `fn_precio_para`: SECURITY INVOKER, STABLE, el negocio
-- ESCRITO en cada lectura, sin negocio fijado no resuelve nada, y un producto
-- que no está ni en la lista ni en el catálogo no devuelve fila.
--
-- IMPACTO: una función nueva. Nada cambia hasta que el código la llame.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_precios_para(
    p_documento  TEXT,
    p_productos  TEXT[],
    p_cantidades INTEGER[],
    p_momento    TIMESTAMPTZ DEFAULT now())
RETURNS TABLE (producto_id TEXT, cantidad INTEGER, precio NUMERIC, origen TEXT,
               lista_precio_item_id UUID, lista_precio_id UUID)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    WITH negocio AS (
        SELECT NULLIF(current_setting('app.tenant_id', true), '') AS id
    ),
    pedido AS (
        SELECT u.producto_id, u.cantidad, u.n
          FROM unnest(p_productos, p_cantidades) WITH ORDINALITY AS u(producto_id, cantidad, n)
    ),
    lista AS (
        SELECT c.lista_precio_id
          FROM public.clientes c, negocio n
         WHERE c.tenant_id = n.id
           AND c.documento = p_documento AND c.activo
    )
    SELECT p.producto_id,
           p.cantidad,
           CASE WHEN l.id IS NOT NULL THEN l.precio ELSE mp.price::numeric END,
           CASE WHEN l.id IS NOT NULL THEN 'LISTA' ELSE 'BASE' END,
           l.id,
           CASE WHEN l.id IS NOT NULL THEN l.lista_id ELSE (SELECT lista_precio_id FROM lista) END
      FROM pedido p
      CROSS JOIN negocio n
      LEFT JOIN LATERAL (
            SELECT i.precio, i.id, i.lista_id
              FROM public.listas_precio_items i
              JOIN lista ls ON ls.lista_precio_id = i.lista_id
              JOIN public.listas_precio lp ON lp.id = i.lista_id AND lp.activa
             WHERE i.tenant_id = n.id
               AND lp.tenant_id = n.id
               AND i.producto_id = p.producto_id
               AND i.cantidad_minima <= GREATEST(p.cantidad, 1)
               AND i.vigente_desde <= p_momento
               AND (i.vigente_hasta IS NULL OR i.vigente_hasta > p_momento)
             ORDER BY i.cantidad_minima DESC, i.vigente_desde DESC
             LIMIT 1
      ) l ON true
      LEFT JOIN public.menu_products mp
             ON mp.tenant_id = n.id AND mp.id_product = p.producto_id
     WHERE l.id IS NOT NULL OR mp.id_product IS NOT NULL
     ORDER BY p.n;
$$;

COMMENT ON FUNCTION fn_precios_para IS
    'fn_precio_para para todas las lineas de una venta en una consulta (F1.5): una '
    'fila por producto con precio, origen LISTA/BASE y la linea de lista aplicada, '
    'en el orden de entrada. Misma regla y mismo filtro por negocio que fn_precio_para; '
    'la paridad la fija PreciosEnLoteTest. Sin fila si el producto no existe.';

-- =====================================================================
-- La comprobación: paridad con fn_precio_para en la base donde se aplica.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v61_a__';
    v_lista UUID;
    r RECORD;
    v_uno RECORD;
    v_filas INT;
BEGIN
    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V61', 'basico');
    PERFORM set_config('app.tenant_id', a, true);
    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('__p61_arroz__', a, 'Arroz V61', 120000, true),
           ('__p61_aceite__', a, 'Aceite V61', 210000, true);
    INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por)
    VALUES (a, 'dist', 'Distribuidor', 'v61') RETURNING id INTO v_lista;
    INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza)
    VALUES (a, v_lista, '__p61_arroz__', 1, 100000, 'v61', 'declarado_comerciante', 1),
           (a, v_lista, '__p61_arroz__', 10, 95000, 'v61', 'declarado_comerciante', 1);
    INSERT INTO clientes (tenant_id, documento, nombre, lista_precio_id, creado_por)
    VALUES (a, '__doc61__', 'Tienda V61', v_lista, 'v61');

    v_filas := 0;
    FOR r IN SELECT * FROM fn_precios_para('__doc61__',
                ARRAY['__p61_arroz__', '__p61_arroz__', '__p61_aceite__', '__no_existe_61__'],
                ARRAY[5, 12, 1, 1], now())
    LOOP
        v_filas := v_filas + 1;
        SELECT * INTO v_uno FROM fn_precio_para('__doc61__', r.producto_id, r.cantidad, now());
        IF v_uno.precio IS DISTINCT FROM r.precio OR v_uno.origen IS DISTINCT FROM r.origen
           OR v_uno.lista_precio_item_id IS DISTINCT FROM r.lista_precio_item_id
           OR v_uno.lista_precio_id IS DISTINCT FROM r.lista_precio_id THEN
            RAISE EXCEPTION 'V61: % x % en lote dio % % y fn_precio_para % %',
                r.producto_id, r.cantidad, r.precio, r.origen, v_uno.precio, v_uno.origen;
        END IF;
    END LOOP;
    -- 5 y 12 bultos de arroz (100.000 y 95.000 LISTA), el aceite BASE; el inexistente no da fila.
    IF v_filas <> 3 THEN
        RAISE EXCEPTION 'V61: el lote devolvio % filas y debian ser 3', v_filas;
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    IF EXISTS (SELECT 1 FROM fn_precios_para('__doc61__', ARRAY['__p61_arroz__'], ARRAY[1], now())) THEN
        RAISE EXCEPTION 'V61: sin negocio fijado el lote devolvio precio';
    END IF;

    DELETE FROM clientes            WHERE tenant_id = a;
    DELETE FROM listas_precio_items WHERE tenant_id = a;
    DELETE FROM listas_precio       WHERE tenant_id = a;
    DELETE FROM menu_products       WHERE tenant_id = a;
    DELETE FROM tenants             WHERE id = a;
    RAISE NOTICE 'V61: los precios de una venta en una consulta, iguales a fn_precio_para.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP FUNCTION IF EXISTS fn_precios_para(TEXT, TEXT[], INTEGER[], TIMESTAMPTZ);
