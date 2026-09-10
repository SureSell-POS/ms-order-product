-- =====================================================================
-- V54 -- Fuera las políticas `panel_shark_*`: un negocio no ve las ventas
--        de otro por una regla que se escribió cuando solo había uno.
--
-- LO QUE SE ENCONTRÓ (ola 4, 2026-09-10, midiendo la precarga en staging)
--
-- En STAGING, nueve tablas de `public` tienen, además de su política
-- `tenant_isolation_*` (por `app.tenant_id`), una política PERMISIVA para el
-- rol `panel_user` con `tenant_id = 'shark-burger'`:
--
--     orders, order_item, order_payments, order_delivery_tracking,
--     order_edit_history, daily_closures, waiters, menu_products,
--     menu_categories
--
-- Las políticas permisivas se SUMAN. Medido con el rol del core y
-- `app.tenant_id = 'qa-f0-ferreteria'`: veía las 15 órdenes, las 27 líneas de
-- venta, el cierre y los 7 productos de Shark Burger. Es la trampa «RLS
-- activa con una política abierta al lado», la de las 17 tablas de V33, en
-- tres tablas de venta.
--
-- DE DÓNDE SALIERON
--
-- De ninguna migración: ningún repositorio las contiene. Se crearon A MANO en
-- staging (sus oid van después de los de `tenant_isolation_*`), solo para
-- `panel_user`, cuando el panel se conectaba sin fijar `app.tenant_id` y
-- Shark era el único negocio. Misma familia que el `DEFAULT 'shark-burger'`
-- (V32) y que el `legacyDataTenant` incrustado en el panel: algo que
-- funcionaba porque solo había un negocio.
--
-- EN PRODUCCIÓN NO ESTÁN (medido el 2026-09-10 en solo lectura): las tres
-- tablas tienen únicamente `tenant_isolation_*`, y el panel de Shark funciona
-- igual. Esa es la prueba de que ningún camino las necesita: el core fija
-- `app.tenant_id` en cada conexión (`TenantAwareDataSource`) y la política
-- por negocio basta. Aquí la migración es un no-op (IF EXISTS).
--
-- DOWN (solo si algún camino de staging las necesitara, que no se encontró):
--   CREATE POLICY panel_shark_<tabla> ON public.<tabla>
--       TO panel_user USING (tenant_id = 'shark-burger');
-- =====================================================================

SET lock_timeout = '3s';

DROP POLICY IF EXISTS panel_shark_orders                  ON public.orders;
DROP POLICY IF EXISTS panel_shark_order_item              ON public.order_item;
DROP POLICY IF EXISTS panel_shark_order_payments          ON public.order_payments;
DROP POLICY IF EXISTS panel_shark_order_delivery_tracking ON public.order_delivery_tracking;
DROP POLICY IF EXISTS panel_shark_order_edit_history      ON public.order_edit_history;
DROP POLICY IF EXISTS panel_shark_daily_closures          ON public.daily_closures;
DROP POLICY IF EXISTS panel_shark_waiters                 ON public.waiters;
DROP POLICY IF EXISTS panel_shark_menu_products           ON public.menu_products;
DROP POLICY IF EXISTS panel_shark_menu_categories         ON public.menu_categories;

-- Verificación: ninguna política de `public` nombra a un negocio concreto.
DO $verificar$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM pg_policies
     WHERE schemaname = 'public' AND qual LIKE '%shark%';
    IF n <> 0 THEN
        RAISE EXCEPTION 'V54: quedan % politicas con un negocio escrito a mano', n;
    END IF;
END $verificar$;
