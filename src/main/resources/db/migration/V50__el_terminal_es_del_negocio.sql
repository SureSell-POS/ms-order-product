-- =====================================================================
-- V50 -- La identidad de un terminal es (negocio, UUID), no el UUID solo.
--
-- EL DEFECTO (fase 0, 2026-09-09)
--
-- `terminals.id` era clave primaria GLOBAL y el POS guarda UN UUID por
-- navegador, no por negocio. La segunda cuenta que vendía desde el mismo
-- equipo chocaba con `terminals_pkey` y la venta salía con 500. El parche
-- de la ola 4 (`ON CONFLICT DO NOTHING` en el alta) evita el 500, pero deja
-- la orden apuntando a un terminal de OTRO negocio: lo hace invisible, no
-- imposible. Es el mismo defecto de aislamiento que ya se cerró tres veces
-- en otras tablas, y aquí se cierra igual: la fila lleva el negocio en la
-- clave.
--
-- LO QUE HACE, EN ESTE ORDEN (el orden importa)
--
--   1. Suelta la FK vieja de `orders` (apuntaba a terminals(id), sin negocio).
--   2. `terminals` pasa a clave primaria (tenant_id, id). El mismo UUID puede
--      existir una vez por negocio; bajo RLS cada negocio ve la suya.
--   3. Da de alta, a nombre de su negocio, el terminal de toda orden que hoy
--      apunta a un terminal registrado por otro (en staging hay una, la de la
--      medición; en producción ninguna). Va DESPUES del paso 2: con la clave
--      vieja ese INSERT chocaba con `terminals_pkey`, que es exactamente lo
--      que pasó en staging el 2026-09-09 04:33 con la primera versión de esta
--      migración (arrancó, falló, y el servicio quedó caído hasta reparar la
--      fila de flyway_schema_history). El test `V50RegularizaLasOrdenesTest`
--      reproduce ese estado antes de migrar para que no vuelva a pasar.
--   4. `orders.terminal_id` pasa a (tenant_id, terminal_id) ->
--      terminals(tenant_id, id). A partir de aquí NO EXISTE forma de que una
--      orden apunte a un terminal ajeno: la base lo rechaza (23503).
--
-- LO QUE YA ESTABA PREPARADO
--
-- El indice unico de la cadena es (tenant_id, terminal_id, epoch, seq)
-- (V36:364) y `CadenaDelServidor` consulta por tenant_id AND terminal_id:
-- la cadena siempre fue por negocio. `ux_terminals_tenant_codigo` (V35) ya
-- llevaba el negocio. El POS no cambia nada.
--
-- EN PRODUCCION
--
-- Cambia la FK de `orders`: bloqueo exclusivo breve sobre la tabla al añadir
-- la restriccion (valida las filas existentes). Con `lock_timeout` de 3 s,
-- si hay una venta larga en curso la migracion falla y se reintenta, no se
-- queda esperando. Se aplica en la ventana de la ola, no como parche. Todo va
-- en una transaccion: si algo falla, no queda nada a medias.
-- =====================================================================

SET lock_timeout = '3s';

-- 1. La FK vieja fuera.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_terminal_id_fkey;

-- 2. Clave primaria compuesta.
ALTER TABLE terminals DROP CONSTRAINT IF EXISTS terminals_pkey;
ALTER TABLE terminals ADD CONSTRAINT terminals_pkey PRIMARY KEY (tenant_id, id);

-- 3. Los terminales que faltan a nombre de su negocio (ya con la clave nueva).
INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto)
SELECT DISTINCT o.terminal_id, o.tenant_id, 'activo', now(), now(), 1
  FROM orders o
 WHERE o.terminal_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM terminals t
                    WHERE t.id = o.terminal_id AND t.tenant_id = o.tenant_id);

-- 4. La FK nueva: una orden solo puede apuntar a un terminal de SU negocio.
--    MATCH SIMPLE: con terminal_id NULL (clientes viejos) no se comprueba.
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_terminal_del_negocio
    FOREIGN KEY (tenant_id, terminal_id) REFERENCES terminals (tenant_id, id);

COMMENT ON CONSTRAINT fk_orders_terminal_del_negocio ON orders IS
    'V50: el terminal de una orden es del mismo negocio que la orden. Un UUID puede existir una vez por negocio.';

-- Verificacion: ninguna orden con terminal apunta fuera de su negocio.
DO $verificar$
DECLARE
    huerfanas INTEGER;
BEGIN
    SELECT count(*) INTO huerfanas
      FROM orders o
     WHERE o.terminal_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM terminals t
                        WHERE t.id = o.terminal_id AND t.tenant_id = o.tenant_id);
    IF huerfanas <> 0 THEN
        RAISE EXCEPTION 'V50: quedan % ordenes con terminal de otro negocio', huerfanas;
    END IF;
END $verificar$;

-- REVERSION (a mano, si hiciera falta):
-- ALTER TABLE orders DROP CONSTRAINT fk_orders_terminal_del_negocio;
-- ALTER TABLE terminals DROP CONSTRAINT terminals_pkey;
-- ALTER TABLE terminals ADD CONSTRAINT terminals_pkey PRIMARY KEY (id);  -- falla si un UUID esta en dos negocios
-- ALTER TABLE orders ADD CONSTRAINT orders_terminal_id_fkey FOREIGN KEY (terminal_id) REFERENCES terminals (id);
