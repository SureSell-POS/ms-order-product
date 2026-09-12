-- =====================================================================
-- V58 -- El cliente de la factura electronica viaja y AHORA se guarda.
--
-- POR QUE (FACTUS-FACTURACION-ELECTRONICA.md §7.1, confirmado con un test
-- el 2026-09-12)
--
-- El POS manda `facturaElectronica` { nombre, documento, correo, telefono? }
-- en el cuerpo de POST /orders/create -- viaja el StoredOrder entero
-- (POS `core/offline/db.ts:20-62`), que el outbox usa tal cual de payload
-- (`offline-order.repository.ts:104`). Este servicio corre con
-- `fail-on-unknown-properties: true` y el DTO no declaraba el campo, asi que
-- MARCAR FACTURA EN EL POS DEVOLVIA 400 Y LA VENTA NO SE CREABA. Medido:
--
--   {"error":"BAD_REQUEST","message":"Unrecognized field \"facturaElectronica\""}
--
-- Y era peor que un error visible: el outbox del POS marca los 4xx como
-- FAILED definitivo, sin reintento (`http-order-sync.gateway.ts:56-58`), asi
-- que esa venta no se sincronizaba NUNCA.
--
-- DONDE SE GUARDA, Y POR QUE ASI
--
-- Una columna JSONB en `orders` y no una tabla aparte: es un dato de la
-- orden, uno por orden, que se escribe en el mismo INSERT de la venta (sin
-- una segunda escritura en el camino del cobro, que es el camino que no se
-- puede permitir fallar), y hereda tal cual el aislamiento por negocio que
-- `orders` ya tiene con RLS. El dia que la emision exista, lo que hara falta
-- es el documento emitido (CUFE, numero, estado), que SI es otra tabla con
-- su propio ciclo de vida; esto es solo a nombre de quien se pidio.
--
-- FORMA: aditiva y nullable. NULL = esta venta no pidio factura, que es la
-- inmensa mayoria. Ninguna fila existente cambia.
--
-- NOTA DE NUMERACION: V56 esta reservada para el SKU y NO se toca. V57 ya la
-- ocupo el rastro del descuento (order_edit_history.discount_code) en este
-- mismo worktree, asi que esto va en V58.
-- =====================================================================

ALTER TABLE orders ADD COLUMN IF NOT EXISTS factura_electronica JSONB;

-- Un objeto, no un arreglo ni un numero suelto: si algun dia alguien escribe
-- aqui desde SQL, que la base lo diga en el momento y no tres meses despues.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_factura_electronica_objeto') THEN
        ALTER TABLE orders ADD CONSTRAINT ck_orders_factura_electronica_objeto
            CHECK (factura_electronica IS NULL OR jsonb_typeof(factura_electronica) = 'object')
            NOT VALID;
    END IF;
END
$$;

ALTER TABLE orders VALIDATE CONSTRAINT ck_orders_factura_electronica_objeto;

COMMENT ON COLUMN orders.factura_electronica IS
    'Cliente al que hay que emitirle la factura electronica, tal y como lo pidio el POS: '
    '{ nombre, documento, correo, telefono? }. NULL = esta venta no pidio factura. '
    'No es el documento emitido: aqui no hay CUFE ni numeracion; hoy no se emite nada.';

-- Las ventas que esperan factura, que es la pregunta que se va a hacer el dia
-- que exista la emision. Parcial: la inmensa mayoria de las ventas no piden.
CREATE INDEX IF NOT EXISTS idx_orders_factura_pendiente
    ON orders (tenant_id, created_at DESC)
    WHERE factura_electronica IS NOT NULL;
