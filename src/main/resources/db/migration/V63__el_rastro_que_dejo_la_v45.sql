-- =====================================================================
-- V63 -- La V45 se dejo dos filas, y no fue descuido suyo.
--
-- Autor: DrDev002 (docs/operacion/scripts-preparados/limpiar-rastro-de-la-v45.sql,
-- docs 5016612). Numerado e integrado por Agente-mt, dueño de la cadena
-- `public`, al escribir el fichero: staging en V61 aplicada, V62 escrita en
-- esta misma rama. Solo cambia esta cabecera; el cuerpo es el del autor.
-- Medido antes de numerarla: 1 fila en `sites` y 1 en `tenant_order_counters`
-- con `__prueba_v45__`, en staging y en produccion.
--
-- POR QUE EXISTE
--
-- Al verificar la limpieza de comercios de QA en staging (2026-09-13)
-- aparecieron dos filas con `tenant_id = '__prueba_v45__'`: una en
-- `public.sites` y otra en `public.tenant_order_counters`. Estan tambien
-- en PRODUCCION, identicas, porque no son basura de un entorno: son la
-- huella de la V45 («listas de precio por cliente y venta a credito»,
-- aplicada el 2026-09-05).
--
-- 🔴 Y AQUI ESTA LO QUE HAY QUE ENTENDER, NO SOLO LO QUE HAY QUE BORRAR
--
-- La V45 se comprueba a si misma POR COMPORTAMIENTO: crea un negocio
-- falso, le hace una venta a credito y mide que la escala de precios y el
-- aviso de cupo funcionan. Y al terminar limpia: borra SIETE tablas
-- (`debt_transactions`, `accounts_receivable`, `orders`, `clientes`,
-- `listas_precio_items`, `listas_precio`, `menu_products`).
--
-- No se dejo estas dos por descuido: **no las inserto ella**. Su INSERT en
-- `orders` disparo `trg_set_order_id_order` -> `set_order_id_order`, que
-- le provisiona a un negocio nuevo su sede por defecto y su contador de
-- ordenes. La migracion no puede limpiar lo que no sabe que existe.
--
-- LA REGLA, que es lo que de verdad vale de todo esto:
--
--   Una verificacion que ESCRIBE en la base tiene que deshacer todo lo
--   que PROVOCO, no solo lo que inserto con su propia mano. Lo que entra
--   por un disparador tambien es suyo.
--
--   Y la forma de asegurarlo no es acordarse de cada tabla: es
--   preguntarselo a la base al final, comprobando que no queda ninguna
--   fila con ese tenant_id en NINGUNA tabla que tenga esa columna. Eso es
--   justo lo que hace el bloque de verificacion de abajo, y por eso esta
--   escrito asi y no como dos `SELECT count(*)`.
--
-- Si alguien vuelve a escribir una verificacion como la de la V45 y no lee
-- esto, dejara el mismo rastro dentro de un año.
--
-- FORMA: idempotente. Puede que alguien ya las haya quitado a mano en un
-- entorno; entonces esto no borra nada y no falla.
-- =====================================================================

-- El contador primero: apunta a `site_id`, asi que es el hijo.
DELETE FROM public.tenant_order_counters WHERE tenant_id = '__prueba_v45__';
DELETE FROM public.sites                 WHERE tenant_id = '__prueba_v45__';

DO $verificar$
DECLARE
    t       RECORD;
    n       BIGINT;
    quedan  BIGINT := 0;
    donde   TEXT   := '';
BEGIN
    -- No se comprueban dos tablas: se le pregunta a la base por todas las
    -- que tienen `tenant_id` en `public` e `inventario`. Si el disparador
    -- de mañana escribe en una tercera, este bloque se entera; una lista a
    -- mano, no.
    --
    -- Por que solo esos dos esquemas, y no todos: medido el 2026-09-13 hay
    -- 66 tablas base con `tenant_id` y NINGUNA fuera de esos dos. Ampliarlo
    -- a todos los esquemas meteria los de Supabase (`auth`, `storage`), y
    -- ahi el EXECUTE fallaria por permisos y tumbaria la migracion. Si algun
    -- dia nace un esquema propio con `tenant_id`, hay que añadirlo aqui.
    FOR t IN SELECT c.table_schema s, c.table_name n
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id = $1', t.s, t.n)
           INTO n USING '__prueba_v45__';
        IF n > 0 THEN
            quedan := quedan + n;
            donde  := donde || t.s || '.' || t.n || '(' || n || ') ';
        END IF;
    END LOOP;

    IF quedan > 0 THEN
        RAISE EXCEPTION 'Quedan % filas de __prueba_v45__ en %', quedan, donde;
    END IF;

    -- Y que no se haya llevado por delante a nadie real: el negocio falso
    -- nunca estuvo en `tenants`, asi que ninguna sede de un comercio de
    -- verdad puede haber desaparecido con esto.
    IF EXISTS (SELECT 1 FROM public.sites s
                WHERE s.tenant_id IS NOT NULL
                  AND s.tenant_id NOT IN (SELECT id FROM public.tenants)) THEN
        RAISE WARNING 'Siguen existiendo sedes sin comercio: hay otro rastro, mirarlo aparte';
    END IF;

    RAISE NOTICE 'Rastro de la V45 retirado; cero filas de __prueba_v45__ en todo el esquema.';
END
$verificar$;
