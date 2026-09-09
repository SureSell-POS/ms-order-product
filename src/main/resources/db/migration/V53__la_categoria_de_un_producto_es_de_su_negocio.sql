-- =====================================================================
-- V53 -- La categoría de un producto es de su mismo negocio, y la base lo
--        garantiza.
--
-- EL DEFECTO (segunda revisión manual de Santiago, 2026-09-09)
--
-- `menu_categories` y `menu_products` (V2) tienen clave primaria GLOBAL
-- (`id_category`, `id_product`) con `tenant_id` aparte, y
-- `menu_products.category_id` NO tiene ninguna clave foránea: ni a la
-- categoría, ni al negocio. Dos consecuencias:
--
--   1. «herramientas» de un negocio le cierra el paso a «herramientas» de
--      otro (409 al crear). Eso lo mitiga el panel poniendo el negocio delante
--      del identificador; cambiar la clave primaria toca las entidades JPA de
--      dos servicios (core y -mt) y va en otra ola.
--   2. Nada impide que un producto apunte a la categoría de OTRO negocio.
--      Hoy no pasa (medido: 0 en staging; en producción, 0 en shark-burger y
--      la FK de abajo valida el resto al aplicarse), pero es aislamiento por
--      costumbre, no por regla. Misma familia que V50.
--
-- LO QUE HACE
--
--   a. UNIQUE (tenant_id, id_category) en `menu_categories`: redundante con la
--      clave primaria global, pero es lo que una FK compuesta necesita
--      apuntar. Cuando la clave pase a ser por negocio, esta será la clave.
--   b. FK (tenant_id, category_id) -> menu_categories (tenant_id, id_category)
--      en `menu_products`. MATCH SIMPLE: un producto sin categoría (hay uno en
--      producción) sigue valiendo.
--   c. Índice de apoyo para la FK (tenant_id, category_id): sin él, borrar
--      una categoría recorre la tabla de productos entera.
--
-- Si al aplicarla existe un producto con categoría de otro negocio o de
-- ninguno, la FK falla y la migración se deshace entera: se arregla a mano
-- en la ventana (es un dato, no un defecto de la migración), no se pone en
-- NULL a escondidas.
-- =====================================================================

SET lock_timeout = '3s';

ALTER TABLE menu_categories
    ADD CONSTRAINT ux_menu_categories_negocio_id UNIQUE (tenant_id, id_category);

CREATE INDEX IF NOT EXISTS ix_menu_products_negocio_categoria
    ON menu_products (tenant_id, category_id);

ALTER TABLE menu_products
    ADD CONSTRAINT fk_menu_products_categoria_del_negocio
    FOREIGN KEY (tenant_id, category_id) REFERENCES menu_categories (tenant_id, id_category);

COMMENT ON CONSTRAINT fk_menu_products_categoria_del_negocio ON menu_products IS
    'V53: la categoria de un producto es del mismo negocio que el producto. NULL sigue valiendo (producto sin categoria).';

-- REVERSION (a mano, si hiciera falta):
-- ALTER TABLE menu_products DROP CONSTRAINT fk_menu_products_categoria_del_negocio;
-- DROP INDEX IF EXISTS ix_menu_products_negocio_categoria;
-- ALTER TABLE menu_categories DROP CONSTRAINT ux_menu_categories_negocio_id;
