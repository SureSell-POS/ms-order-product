-- =====================================================================
-- V52 -- Cobrado, no impreso.
--
-- LO QUE HABIA
--
-- Una venta se cobra, la tirilla no sale, y el POS dice «¡Orden Creada!» en
-- verde: el error de impresión iba a la consola del navegador y a ningún otro
-- sitio (docs/operacion/PERIFERICOS-2026-09.md §1.3). No existía ningún dato
-- que dijera si la tirilla de una venta salió o no. `orders.is_printed` NO
-- sirve para esto: significa «la comanda salió de la cola de cocina»
-- (OrderHandler, «isPrinted es lo que saca la orden de la cola de cocina») y
-- reutilizarlo mezclaría dos documentos distintos.
--
-- LA DECISION (docs/arquitectura/21-cobrado-no-impreso.md, aprobado 2026-09-09)
--
-- Un estado POR VENTA para la tirilla, `recibo_estado`, con un motivo cuando
-- no salió. Lo escribe el POS con un PATCH; sin sondeo, sin trigger, sin fila
-- extra por venta (costo de infraestructura al mínimo). Y un dato POR SEDE,
-- `sites.imprime_tirilla`: un negocio sin impresora no tiene por qué ver una
-- alerta roja eterna por un programa que nunca tendrá.
--
-- Estados:  no_solicitado (nace así)  ·  enviado (el spooler aceptó)  ·
--           confirmado (el trabajo salió de la cola)  ·  no_impreso (con motivo)
--           ·  descartado («no hace falta»).
--
-- COSTO EN PRODUCCION
--
-- `ADD COLUMN ... DEFAULT <constante>` es solo metadato desde Postgres 11: no
-- reescribe `orders`. El CHECK se añade NOT VALID (no escanea) y se valida
-- aparte: VALIDATE toma SHARE UPDATE EXCLUSIVE, que no bloquea ventas, y
-- recorre la tabla una vez leyendo un valor que acaba de ponerse por defecto.
--
-- Aditiva, idempotente, reversible. Rollback al final del fichero.
-- =====================================================================

ALTER TABLE orders ADD COLUMN IF NOT EXISTS recibo_estado         TEXT        NOT NULL DEFAULT 'no_solicitado';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS recibo_motivo         TEXT        NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS recibo_actualizado_at TIMESTAMPTZ NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_recibo_estado') THEN
        ALTER TABLE orders ADD CONSTRAINT ck_orders_recibo_estado
            CHECK (recibo_estado IN ('no_solicitado', 'enviado', 'confirmado', 'no_impreso', 'descartado'))
            NOT VALID;
    END IF;
END $$;

ALTER TABLE orders VALIDATE CONSTRAINT ck_orders_recibo_estado;

-- Lo que el POS pregunta cada vez que abre: «¿qué ventas de este negocio se
-- quedaron sin tirilla?». Casi ninguna fila cumple el predicado, así que el
-- índice parcial es diminuto y la consulta no toca el resto de `orders`.
CREATE INDEX IF NOT EXISTS idx_orders_recibo_no_impreso
    ON orders (tenant_id, id_order DESC)
    WHERE recibo_estado = 'no_impreso';

-- Por sede, no por negocio: una cadena puede tener una caja con impresora y un
-- punto de venta sin ella. Nace en true para que ningún negocio que hoy
-- imprime deje de hacerlo por esta migración.
ALTER TABLE sites ADD COLUMN IF NOT EXISTS imprime_tirilla BOOLEAN NOT NULL DEFAULT true;

-- =====================================================================
-- ROLLBACK (manual; NO lo ejecuta Flyway)
--
-- DROP INDEX IF EXISTS idx_orders_recibo_no_impreso;
-- ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_recibo_estado;
-- ALTER TABLE orders DROP COLUMN IF EXISTS recibo_actualizado_at;
-- ALTER TABLE orders DROP COLUMN IF EXISTS recibo_motivo;
-- ALTER TABLE orders DROP COLUMN IF EXISTS recibo_estado;
-- ALTER TABLE sites  DROP COLUMN IF EXISTS imprime_tirilla;
-- =====================================================================
