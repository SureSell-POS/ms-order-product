-- =====================================================================
-- V57 -- El descuento de una venta deja rastro.
--
-- POR QUE (ANULAR-ORDENES-Y-AUTORIZACION-POR-TARJETA.md §1.4, orden de
-- Santiago del 2026-09-12)
--
-- `PATCH /orders/{id}/apply-discount` cambiaba el TOTAL de una venta y no
-- escribia una sola fila en ninguna parte: ni quien, ni cuando, ni cuanto
-- era antes. Una edicion normal si dejaba rastro en `order_edit_history`,
-- asi que el camino mas barato para mover dinero sin que se notara era
-- justamente el del descuento. Y el endpoint tampoco exigia rol.
--
-- El rastro del descuento va a la MISMA tabla que las ediciones —mismo
-- sitio donde mirar, mismo endpoint que el panel ya lee— con
-- `edit_type = 'DISCOUNT_APPLIED'`, `old_total`/`new_total` y `edited_by`.
-- Lo unico que no cabia en el modelo era QUE cupon se aplico: eso es esta
-- columna.
--
-- NOTA DE NUMERACION: V56 esta reservada para el SKU y NO se toca. Esta
-- migracion se llevo a V57 por eso.
--
-- FORMA: aditiva y nullable. Ninguna fila existente cambia; las ediciones
-- de items la dejan nula, que es lo correcto: no hubo cupon.
-- =====================================================================

ALTER TABLE order_edit_history ADD COLUMN IF NOT EXISTS discount_code TEXT;

COMMENT ON COLUMN order_edit_history.discount_code IS
    'Codigo del cupon aplicado, solo en las filas con edit_type = DISCOUNT_APPLIED. '
    'NULL en las ediciones de items (no hubo cupon) y en todo lo anterior a V57.';
