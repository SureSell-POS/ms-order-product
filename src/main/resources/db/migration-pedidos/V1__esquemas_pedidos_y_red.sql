-- =====================================================================
-- V1 (cadena `pedidos`) -- Los esquemas `pedidos` y `red`, y nada más.
--
-- Primera migración de la cadena propia del pedido (plan de mayoristas D1/D2,
-- F0.9) y del canal entre negocios (plan de la red B2B D2, T1.1). La escriben
-- juntos Agente-mt y DevEspecializadoEnB2BYpedidos; el orden acordado es
-- V1 conjunta, V2 red, V3 pedido, V4-V5 red, y desde ahí por orden de llegada.
--
-- ── Una cadena aparte, con su historial aparte ───────────────────────
--
-- `public` es la cadena de ventas. Una vertical no escribe en ella
-- (operacion/FLYWAY.md §5). Esta cadena la corre `FlywayPedidos` con su
-- tabla de historial `pedidos.flyway_schema_history_pedidos`. Dos trampas,
-- las dos irreversibles y silenciosas, y la guarda de las dos es
-- `HistorialesSeparadosTest`, no esta migración:
--
--   · sin `table:` propia, esta cadena escribiría en
--     `public.flyway_schema_history` y pisaría el historial de ventas;
--   · declarada como `@Bean Flyway`, Spring Boot 3.4.1 retira su Flyway
--     autoconfigurado (`@ConditionalOnMissingBean(Flyway.class)`) y `public`
--     deja de migrar sin decir nada.
--
-- ── Quién crea los esquemas: Flyway, no esta migración ────────────────
--
-- La propuesta era `createSchemas(false)` y que el esquema naciera aquí,
-- sellado. Medido el 2026-09-13 con Flyway 10.20.1: NO ARRANCA. La tabla de
-- historial vive en `pedidos` y Flyway la crea ANTES de ejecutar V1:
--
--     ERROR: schema "pedidos" does not exist
--
-- Así que `FlywayPedidos` deja `createSchemas` por defecto (true): Flyway
-- crea `pedidos` y `red` y lo anota como `<< Flyway Schema Creation >>` en
-- su historial. Los `CREATE SCHEMA IF NOT EXISTS` de abajo quedan como
-- no-op en un arranque normal; se conservan para que el script, leído solo,
-- diga lo que necesita. Lo que esta migración verifica de verdad es el
-- resultado: esquemas, esquema por defecto y permisos.
--
-- IMPACTO: dos esquemas vacíos y USAGE para `app_user`. Ninguna tabla.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS pedidos;
CREATE SCHEMA IF NOT EXISTS red;

-- Igual que la V1 del inventario: en la suite de otros servicios el rol
-- puede no existir, y un GRANT a un rol inexistente rompe la migración.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT USAGE ON SCHEMA pedidos TO app_user;
        GRANT USAGE ON SCHEMA red TO app_user;
    END IF;
END
$$;

COMMENT ON SCHEMA pedidos IS
    'El pedido con entrega diferida (plan de mayoristas). Cadena Flyway propia: '
    'pedidos.flyway_schema_history_pedidos.';
COMMENT ON SCHEMA red IS
    'El canal entre dos negocios: relacion, catalogo publicado, puerta del comprador '
    '(plan de la red B2B). Misma cadena que `pedidos`.';

DO $cierre$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'pedidos')
       OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'red') THEN
        RAISE EXCEPTION 'V1 pedidos: faltan los esquemas pedidos y red';
    END IF;

    -- Esto SÍ lo detecta la migración: si la cadena corre sin su esquema por
    -- defecto, sus tablas nacerían en `public`.
    IF current_schema() IS DISTINCT FROM 'pedidos' THEN
        RAISE EXCEPTION 'V1 pedidos: la migracion corre con esquema por defecto %, y debe ser pedidos',
            current_schema();
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND NOT (has_schema_privilege('app_user', 'pedidos', 'USAGE')
                AND has_schema_privilege('app_user', 'red', 'USAGE')) THEN
        RAISE EXCEPTION 'V1 pedidos: app_user no puede usar los esquemas';
    END IF;

    RAISE NOTICE 'V1 pedidos: esquemas pedidos y red creados, app_user con USAGE.';
END
$cierre$;
