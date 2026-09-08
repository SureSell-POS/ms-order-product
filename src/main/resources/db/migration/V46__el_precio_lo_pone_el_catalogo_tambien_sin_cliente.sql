-- =====================================================================
-- V46 -- El precio lo pone el catálogo también en la venta SIN cliente.
--
-- COMO APARECIO
--
-- La ola 2 (V45) hizo que, cuando la orden trae cliente, el precio lo resuelva
-- la base con su lista y el del POS se descarte. Al integrar se hizo la
-- pregunta obvia: ¿y cuando no trae cliente? Se midió en staging:
--
--     orden 4 de qa-zeta-v38, producto de 120.000, unitPrice 1 desde el POS
--     total cobrado: 1,00   precio_origen: POS
--
-- Un POS manipulado fijaba su propio precio en toda venta sin cliente, que
-- son casi todas. Es la misma familia que los importes de la Fase 2 (V36,
-- `total_discrepancia`), abierta por otro lado.
--
-- Y una segunda medida, en producción y en solo lectura, antes de decidir:
-- de 2.407 líneas de los últimos 30 días, 206 se cobraron a un precio distinto
-- del que hoy tiene el catálogo -- todas del "Plato del Día", cuyo precio en el
-- menú cambia cada día -- y 15 son de productos que ya no están en el menú.
-- El POS no tiene ningún camino para editar precios: vende lo que el menú
-- decía ese día. Así que resolver SIEMPRE en el servidor no rompe ningún
-- flujo honesto; solo deja de aceptar el precio de un POS que no coincide con
-- el catálogo de ese momento.
--
-- QUE HACE
--
-- El código (`OrderHandler` + `ResolucionDePrecios`) resuelve el precio en la
-- base para TODA venta: lista del cliente si la hay, precio BASE del catálogo
-- si no. El que mandó el POS se guarda aquí, en `precio_declarado`, para
-- compararlo -- nunca para cobrarlo. Si el producto no está en el catálogo,
-- la línea conserva lo declarado con `precio_origen = 'POS'`, que ya existía y
-- es visible: una venta no se pierde por un producto que el menú todavía no
-- conoce.
--
-- IMPACTO: una columna nueva, NULL en todo lo existente. Cero filas cambian.
--
-- ROLLBACK: ver DOWN.
-- =====================================================================

ALTER TABLE order_item
    ADD COLUMN IF NOT EXISTS precio_declarado NUMERIC(12,2) NULL;

COMMENT ON COLUMN order_item.precio_declarado IS
    'Lo que mando el POS por unidad, tal cual. unit_price es lo que se cobro, '
    'resuelto por el catalogo (V45/V46). Si difieren, alguien tiene que mirar.';

-- Lo que se cobra nunca es negativo; lo declarado puede ser cualquier cosa,
-- por eso no se restringe: es evidencia, no un importe.
ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_unit_price_no_negativo;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_unit_price_no_negativo
    CHECK (unit_price IS NULL OR unit_price >= 0);

-- =====================================================================
-- La comprobacion, por comportamiento: sin cliente, la base da el precio BASE
-- =====================================================================
DO $cierre$
DECLARE
    v_tenant TEXT := '__prueba_v46__';
    v_precio NUMERIC; v_origen TEXT;
BEGIN
    PERFORM set_config('app.tenant_id', v_tenant, true);
    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('__p46__', v_tenant, 'Producto V46', 120000, true);

    -- Sin cliente (NULL): el precio tiene que ser el del catalogo, con origen BASE.
    SELECT precio, origen INTO v_precio, v_origen
      FROM fn_precio_para(NULL, '__p46__', 1, now());
    IF v_precio IS DISTINCT FROM 120000 OR v_origen IS DISTINCT FROM 'BASE' THEN
        RAISE EXCEPTION 'V46: sin cliente fn_precio_para dio % % y deberia dar 120000 BASE',
            v_precio, v_origen;
    END IF;

    -- Un producto que no existe no da fila: es el caso en que el POS conserva
    -- su precio, con origen POS.
    IF EXISTS (SELECT 1 FROM fn_precio_para(NULL, '__no_existe_46__', 1, now())) THEN
        RAISE EXCEPTION 'V46: un producto inexistente devolvio precio';
    END IF;

    DELETE FROM menu_products WHERE id_product = '__p46__' AND tenant_id = v_tenant;
    RAISE NOTICE 'V46: el catalogo pone el precio tambien sin cliente.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_unit_price_no_negativo;
-- ALTER TABLE order_item DROP COLUMN IF EXISTS precio_declarado;
