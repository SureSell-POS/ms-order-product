-- =====================================================================
-- V69 -- La numeracion de ventas resuelve sus tablas en `public`, no en las
--        de quien vende.
--
-- Orden de ECM y C (2026-09-14), antes de F5.3. Encontrada al contar TODAS
-- las funciones SECURITY DEFINER de la base, no por aviso de nadie:
--
--   staging: 8 DEFINER; 3 de Supabase (search_path=""), 5 nuestras, 4 cubiertas
--   desde V39 (search_path=public, pg_temp; sin EXECUTE para PUBLIC).
--   La quinta, fuera: `public.set_order_id_order()`, el disparador
--   `trg_set_order_id_order` BEFORE INSERT ON orders. C lo midio igual en
--   produccion (dueño postgres, proconfig NULL, PUBLIC ejecuta).
--
-- 🔴 POR QUE NO ES UNA PEGA DE ESTILO
--
-- Corre como su dueño, `postgres`, que tiene BYPASSRLS, y en CADA venta.
-- Nombra `sites` y `tenant_order_counters` sin esquema y sin `search_path`
-- propio, asi que los resuelve con el `search_path` de la sesion que inserta.
-- `pg_temp` se busca PRIMERO cuando no esta en la lista: una sesion de
-- `app_user` (tiene TEMP) crea una tabla temporal con ese nombre y el
-- disparador, con los privilegios de postgres, lee y escribe la suya. El
-- cierre de abajo lo reproduce ANTES del cambio y comprueba que DESPUES ya no.
--
-- 🔴 ES EL CAMINO DE TODAS LAS VENTAS (ck_int_reloj, 2026-09-03)
--
-- Por eso esta migracion NO toca la logica:
--   1. Guarda: el cuerpo vigente tiene que ser EXACTAMENTE el medido en
--      staging (md5 de prosrc 91e59cd107da3462bef4b337c6223621, el de V28).
--      Si en un entorno es otro, la migracion se para y no cambia nada.
--   2. Solo `ALTER FUNCTION ... SET search_path = pg_catalog, public, pg_temp`
--      (sus dos tablas estan en public; no hace falta calificar nada) y
--      `REVOKE EXECUTE ... FROM PUBLIC`. Un disparador no comprueba EXECUTE
--      al dispararse; lo prueba `NumeracionDeVentasTest` insertando ventas
--      como `app_user` con el EXECUTE ya revocado.
--   3. El cierre compara que el md5 del cuerpo sigue igual.
--
-- LO QUE EL CIERRE HACE COMO DUEÑO Y NO COMO app_user: en staging `postgres`
-- no puede `SET ROLE app_user` (medido: pg_has_role SET = false), y una
-- migracion que depende de eso se cae al arrancar. Lo que depende del rol que
-- llama (EXECUTE, la tabla temporal de app_user, dos ventas a la vez) esta en
-- `NumeracionDeVentasTest`, que compara la base en v68 y en v69.
--
-- Deja rastro cero: lo que provoca el disparador (sede y contador de un
-- negocio sin sede) se borra, y al final se pregunta a la base por todas las
-- tablas con tenant_id de `public` e `inventario` (la regla de V63).
-- =====================================================================

-- ── 0 · Guarda: el cuerpo es el que se midio ─────────────────────────
DO $guarda$
DECLARE
    v_md5 TEXT;
BEGIN
    SELECT md5(p.prosrc) INTO v_md5
      FROM pg_proc p
     WHERE p.oid = to_regprocedure('public.set_order_id_order()');
    IF v_md5 IS NULL THEN
        RAISE EXCEPTION 'V69: no existe public.set_order_id_order()';
    END IF;
    IF v_md5 <> '91e59cd107da3462bef4b337c6223621' THEN
        RAISE EXCEPTION 'V69: el cuerpo de set_order_id_order no es el medido (md5 %). No se cambia nada: hay que mirarlo antes', v_md5;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                    WHERE tgname = 'trg_set_order_id_order' AND tgrelid = 'public.orders'::regclass
                      AND tgfoid = 'public.set_order_id_order()'::regprocedure AND tgenabled = 'O') THEN
        RAISE EXCEPTION 'V69: trg_set_order_id_order no esta habilitado sobre orders con esta funcion';
    END IF;
END $guarda$;

-- ── 1 · Antes del cambio: consecutivos y el agujero, medidos ─────────
CREATE TEMP TABLE v69_medida (fase TEXT, caso TEXT, valor BIGINT) ON COMMIT DROP;

DO $antes$
DECLARE
    a CONSTANT TEXT := '__prueba_v69_a__';
    b CONSTANT TEXT := '__prueba_v69_b__';
    v_a1 BIGINT; v_a2 BIGINT; v_b1 BIGINT;
    v_n BIGINT;
    v_sp TEXT := current_setting('search_path');
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V69 A', 'basico'), (b, 'Prueba V69 B', 'basico');
    INSERT INTO public.sites (tenant_id, name, code, is_default) VALUES (a, 'A1', 'V69-A1', true) RETURNING id INTO v_a1;
    INSERT INTO public.sites (tenant_id, name, code, is_default) VALUES (a, 'A2', 'V69-A2', false) RETURNING id INTO v_a2;
    INSERT INTO public.sites (tenant_id, name, code, is_default) VALUES (b, 'B1', 'V69-B1', true) RETURNING id INTO v_b1;

    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a1) RETURNING id_order INTO v_n;
    INSERT INTO v69_medida VALUES ('antes', 'a1-1', v_n);
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a1) RETURNING id_order INTO v_n;
    INSERT INTO v69_medida VALUES ('antes', 'a1-2', v_n);
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a2) RETURNING id_order INTO v_n;
    INSERT INTO v69_medida VALUES ('antes', 'a2-1', v_n);
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), b, 'pagado', v_b1) RETURNING id_order INTO v_n;
    INSERT INTO v69_medida VALUES ('antes', 'b1-1', v_n);

    -- El agujero: un contador temporal con el mismo nombre y la sesion con
    -- `pg_temp` delante. Sin search_path propio, el disparador escribe en ESTE
    -- y no en el de public. (Cambiar el search_path de la sesion es tambien lo
    -- que obliga a replanificar: el disparador ya corrio en esta sesion y su
    -- plan guardado apunta a public; una tabla nueva sola no lo invalida.)
    CREATE TEMP TABLE tenant_order_counters (tenant_id TEXT, site_id BIGINT, last_id BIGINT, PRIMARY KEY (tenant_id, site_id));
    INSERT INTO pg_temp.tenant_order_counters VALUES (a, v_a1, 900000);
    PERFORM set_config('search_path', 'pg_temp, ' || v_sp, true);
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a1) RETURNING id_order INTO v_n;
    PERFORM set_config('search_path', v_sp, true);
    INSERT INTO v69_medida VALUES ('antes', 'a1-sombra', v_n);
    DROP TABLE pg_temp.tenant_order_counters;
END $antes$;

-- ── 2 · El cambio ─────────────────────────────────────────────────────
ALTER FUNCTION public.set_order_id_order() SET search_path = pg_catalog, public, pg_temp;
REVOKE EXECUTE ON FUNCTION public.set_order_id_order() FROM PUBLIC;

-- ── 3 · Cierre ────────────────────────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v69_a__';
    b CONSTANT TEXT := '__prueba_v69_b__';
    c CONSTANT TEXT := '__prueba_v69_c__';
    v_a1 BIGINT; v_a2 BIGINT; v_b1 BIGINT;
    v_n BIGINT;
    t RECORD;
    v_filas BIGINT;
    v_quedan BIGINT := 0;
    v_donde TEXT := '';
    v_sp TEXT := current_setting('search_path');
BEGIN
    -- La logica no cambio: mismo cuerpo, mismo disparador.
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = 'public.set_order_id_order()'::regprocedure) <> '91e59cd107da3462bef4b337c6223621' THEN
        RAISE EXCEPTION 'V69: el cuerpo de set_order_id_order cambio';
    END IF;
    IF (SELECT proconfig FROM pg_proc WHERE oid = 'public.set_order_id_order()'::regprocedure)
       IS DISTINCT FROM ARRAY['search_path=pg_catalog, public, pg_temp'] THEN
        RAISE EXCEPTION 'V69: set_order_id_order no quedo con search_path = pg_catalog, public, pg_temp';
    END IF;
    IF has_function_privilege('public', 'public.set_order_id_order()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V69: PUBLIC sigue pudiendo ejecutar set_order_id_order';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND has_function_privilege('app_user', 'public.set_order_id_order()', 'EXECUTE') THEN
        RAISE EXCEPTION 'V69: app_user sigue pudiendo ejecutar set_order_id_order';
    END IF;
    -- Y ninguna DEFINER nuestra queda sin search_path o ejecutable por PUBLIC.
    -- Las de una extension no son nuestras (C midio dos de dblink en produccion).
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
                WHERE p.prosecdef AND ns.nspname IN ('public', 'inventario', 'pedidos')
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
                  AND (NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) x WHERE x LIKE 'search\_path=%pg\_temp')
                       OR has_function_privilege('public', p.oid, 'EXECUTE'))) THEN
        RAISE EXCEPTION 'V69: queda una SECURITY DEFINER en public/inventario/pedidos sin search_path con pg_temp o ejecutable por PUBLIC';
    END IF;

    SELECT id INTO v_a1 FROM public.sites WHERE tenant_id = a AND code = 'V69-A1';
    SELECT id INTO v_a2 FROM public.sites WHERE tenant_id = a AND code = 'V69-A2';
    SELECT id INTO v_b1 FROM public.sites WHERE tenant_id = b AND code = 'V69-B1';

    -- Antes: 1,2 en A1; 1 en A2; 1 en B1; y la sombra se uso (900001).
    IF (SELECT string_agg(caso || '=' || valor, ',' ORDER BY caso) FROM v69_medida WHERE fase = 'antes')
       IS DISTINCT FROM 'a1-1=1,a1-2=2,a1-sombra=900001,a2-1=1,b1-1=1' THEN
        RAISE EXCEPTION 'V69: lo medido antes no es lo esperado: %',
            (SELECT string_agg(caso || '=' || valor, ',' ORDER BY caso) FROM v69_medida WHERE fase = 'antes');
    END IF;

    -- Despues: los consecutivos siguen donde iban, por sede y por negocio.
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a1) RETURNING id_order INTO v_n;
    IF v_n <> 3 THEN RAISE EXCEPTION 'V69: A1 debia seguir en 3 y dio %', v_n; END IF;
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a2) RETURNING id_order INTO v_n;
    IF v_n <> 2 THEN RAISE EXCEPTION 'V69: A2 debia seguir en 2 y dio %', v_n; END IF;
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), b, 'pagado', v_b1) RETURNING id_order INTO v_n;
    IF v_n <> 2 THEN RAISE EXCEPTION 'V69: B1 debia seguir en 2 y dio %', v_n; END IF;

    -- Despues, la misma sombra con la misma sesion ya no se usa: el numero sale de public.
    CREATE TEMP TABLE tenant_order_counters (tenant_id TEXT, site_id BIGINT, last_id BIGINT, PRIMARY KEY (tenant_id, site_id));
    INSERT INTO pg_temp.tenant_order_counters VALUES (a, v_a1, 900000);
    PERFORM set_config('search_path', 'pg_temp, ' || v_sp, true);
    INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (gen_random_uuid(), a, 'pagado', v_a1) RETURNING id_order INTO v_n;
    PERFORM set_config('search_path', v_sp, true);
    IF v_n <> 4 OR (SELECT last_id FROM pg_temp.tenant_order_counters WHERE tenant_id = a) <> 900000 THEN
        RAISE EXCEPTION 'V69: con el search_path fijo el disparador siguio usando la tabla temporal (dio %)', v_n;
    END IF;
    DROP TABLE pg_temp.tenant_order_counters;

    -- Un negocio sin sede sigue pudiendo vender: el camino que crea la sede
    -- Principal tambien resuelve `sites` en public.
    INSERT INTO public.tenants (id, name, plan) VALUES (c, 'Prueba V69 C', 'basico');
    INSERT INTO public.orders (uuid_id, tenant_id, status) VALUES (gen_random_uuid(), c, 'pagado') RETURNING id_order INTO v_n;
    IF v_n <> 1 OR NOT EXISTS (SELECT 1 FROM public.sites WHERE tenant_id = c AND code = 'PRINCIPAL' AND is_default) THEN
        RAISE EXCEPTION 'V69: un negocio sin sede no obtuvo su sede Principal y su primer numero (dio %)', v_n;
    END IF;

    -- Limpieza: lo insertado y lo que provoco el disparador.
    DELETE FROM public.orders                WHERE tenant_id IN (a, b, c);
    DELETE FROM public.tenant_order_counters WHERE tenant_id IN (a, b, c);
    DELETE FROM public.sites                 WHERE tenant_id IN (a, b, c);
    DELETE FROM public.tenants               WHERE id IN (a, b, c);

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v69%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v69%') THEN
        RAISE EXCEPTION 'V69: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;
    -- Control del patron: el marcador lo cumple y un parecido no.
    IF NOT ('__prueba_v69_a__' LIKE '\_\_prueba\_v69%') OR ('xxprueba_v69_a__' LIKE '\_\_prueba\_v69%') THEN
        RAISE EXCEPTION 'V69: el patron del barrido no distingue el marcador';
    END IF;

    RAISE NOTICE 'V69: antes la sombra dio 900001; despues A1=3,4 A2=2 B1=2 C=1 desde public; search_path fijo, sin EXECUTE para PUBLIC; 0 restos';
END $cierre$;
