-- =====================================================================
-- V81 -- Los super administradores (KAM) solo se leen y se crean por
--        funcion; app_user no toca la tabla.
--
-- S1, orden de ECM y C (2026-09-15). C lo midio igual en staging y en
-- produccion: `super_admins` tenia RLS con `app_rw_super_admins FOR ALL TO
-- app_user USING (true) WITH CHECK (true)` y app_user con SELECT, INSERT,
-- UPDATE y DELETE (V8). La API no expone la tabla, pero una credencial de
-- aplicacion comprometida o una inyeccion podia leer todos los correos y
-- hashes, crear un super administrador o borrar los que hay.
--
-- LO QUE EL CODIGO USA (medido en todas las ramas remotas de -mt, core e
-- inventario; core e inventario: ninguna sentencia):
--   SuperAdminRepository.java:34  SELECT id, email, password_hash ... WHERE lower(email) = lower(?)
--                                 (login del KAM, alta de KAM, KAM de arranque)
--   SuperAdminRepository.java:69  INSERT INTO super_admins (email, password_hash)
--                                 (alta desde el KAM, SuperAdminService:474; KAM de arranque, KamDeArranque:104)
--   Ningun UPDATE, DELETE ni TRUNCATE. No hay cambio de clave del KAM.
--
-- EL CAMBIO:
--   1. fn_super_admin_por_correo(correo): la fila de ESE correo. Ya no se
--      lista la tabla: hay que saber el correo.
--   2. fn_super_admin_crear(correo, hash): valida correo y formato BCrypt e
--      inserta. SECURITY DEFINER, search_path fijo, sin EXECUTE para PUBLIC,
--      EXECUTE solo para app_user (como V39/V69).
--   3. REVOKE ALL a app_user sobre la tabla y la secuencia; tambien a anon y
--      authenticated si existen (Supabase). DROP de la politica: con RLS
--      activa y sin politicas, nadie salvo el dueño lee una fila.
--
-- 🔴 RIESGO RESIDUAL, CON SU NOMBRE: `fn_super_admin_crear` SIGUE SIENDO
--    INVOCABLE POR app_user. Una credencial de aplicacion comprometida ya no
--    lista, no lee todos los hashes, no cambia ni borra, pero TODAVIA PUEDE
--    CREAR un super administrador llamando a la funcion. Eso solo se cierra
--    con un rol de base distinto para el KAM o con una comprobacion fuera de
--    la base; es decision de C (arquitectura), no de esta migracion.
--    Mejora futura anotada por ECM: comprobar la clave dentro de la base
--    (pgcrypto crypt()) para que el hash no salga nunca; hoy NO.
--
-- El KAM de arranque por variable (KamDeArranque) sigue funcionando: usa las
-- mismas dos funciones. Regla: en produccion KAM_BOOTSTRAP_* no se define;
-- esta migracion no puede verlo (es una variable del servicio) y lo
-- comprueba C en Railway.
--
-- Deja rastro cero: la fila de prueba del cierre se borra y se comprueba.
-- =====================================================================

-- ── 0 · Guarda: la tabla es la de V8 y quien migra puede leerla sin politica
DO $guarda$
DECLARE
    v_cols TEXT;
    v_otras TEXT;
BEGIN
    SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'super_admins';
    IF v_cols IS DISTINCT FROM 'id:bigint,email:text,password_hash:text,created_at:timestamp with time zone' THEN
        RAISE EXCEPTION 'V81: public.super_admins no es la de V8 (columnas %). No se cambia nada', v_cols;
    END IF;
    SELECT string_agg(policyname, ',') INTO v_otras
      FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'super_admins' AND policyname <> 'app_rw_super_admins';
    IF v_otras IS NOT NULL THEN
        RAISE EXCEPTION 'V81: super_admins tiene politicas no medidas (%). Hay que mirarlas antes', v_otras;
    END IF;
    -- Las funciones DEFINER corren como quien migra: sin politica, solo el dueño
    -- de la tabla (sin FORCE) o un rol con BYPASSRLS ve filas.
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_roles r ON r.rolname = current_user
                    WHERE c.oid = 'public.super_admins'::regclass
                      AND ((c.relowner = r.oid AND NOT c.relforcerowsecurity) OR r.rolbypassrls OR r.rolsuper)) THEN
        RAISE EXCEPTION 'V81: % no es dueño de super_admins ni salta RLS: las funciones no verian filas', current_user;
    END IF;
END $guarda$;

-- ── 1 · Las dos funciones ─────────────────────────────────────────────
CREATE FUNCTION public.fn_super_admin_por_correo(p_correo TEXT)
RETURNS TABLE (id BIGINT, email TEXT, password_hash TEXT)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT s.id, s.email, s.password_hash
      FROM public.super_admins s
     WHERE lower(s.email) = lower(p_correo);
$$;

CREATE FUNCTION public.fn_super_admin_crear(p_correo TEXT, p_password_hash TEXT)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_id BIGINT;
BEGIN
    IF p_correo IS NULL OR btrim(p_correo) = '' OR position('@' IN p_correo) = 0 THEN
        RAISE EXCEPTION 'El correo del KAM no parece valido' USING ERRCODE = '22023';
    END IF;
    IF p_password_hash IS NULL OR p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'La clave del KAM llega sin hash BCrypt' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.super_admins (email, password_hash)
    VALUES (lower(btrim(p_correo)), p_password_hash)
    RETURNING super_admins.id INTO v_id;
    RETURN v_id;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.fn_super_admin_por_correo(TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.fn_super_admin_crear(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_super_admin_por_correo(TEXT) TO app_user;
GRANT EXECUTE ON FUNCTION public.fn_super_admin_crear(TEXT, TEXT) TO app_user;

-- ── 2 · La tabla deja de ser de app_user ──────────────────────────────
REVOKE ALL ON public.super_admins FROM app_user;
REVOKE ALL ON SEQUENCE public.super_admins_id_seq FROM app_user;
REVOKE ALL ON public.super_admins FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.super_admins_id_seq FROM PUBLIC;
DO $supabase$
DECLARE
    r TEXT;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON public.super_admins FROM %I', r);
            EXECUTE format('REVOKE ALL ON SEQUENCE public.super_admins_id_seq FROM %I', r);
            EXECUTE format('REVOKE EXECUTE ON FUNCTION public.fn_super_admin_por_correo(TEXT) FROM %I', r);
            EXECUTE format('REVOKE EXECUTE ON FUNCTION public.fn_super_admin_crear(TEXT, TEXT) FROM %I', r);
        END IF;
    END LOOP;
END $supabase$;
DROP POLICY IF EXISTS app_rw_super_admins ON public.super_admins;

-- ── 3 · Cierre ────────────────────────────────────────────────────────
DO $cierre$
DECLARE
    v_rol TEXT;
    v_priv TEXT;
    v_id BIGINT;
    v_n BIGINT;
    v_hash CONSTANT TEXT := '$2a$10$' || repeat('a', 53);
    v_correo CONSTANT TEXT := '__prueba_v81__@suresell.invalid';
    v_fn REGPROCEDURE;
BEGIN
    -- app_user (y anon/authenticated si existen) sin ningun privilegio en la tabla ni en la secuencia.
    FOR v_rol IN SELECT rolname FROM pg_roles WHERE rolname IN ('app_user', 'anon', 'authenticated') LOOP
        FOREACH v_priv IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
            IF has_table_privilege(v_rol, 'public.super_admins', v_priv) THEN
                RAISE EXCEPTION 'V81 cierre: % conserva % sobre super_admins', v_rol, v_priv;
            END IF;
        END LOOP;
        FOREACH v_priv IN ARRAY ARRAY['USAGE', 'SELECT', 'UPDATE'] LOOP
            IF has_sequence_privilege(v_rol, 'public.super_admins_id_seq', v_priv) THEN
                RAISE EXCEPTION 'V81 cierre: % conserva % sobre super_admins_id_seq', v_rol, v_priv;
            END IF;
        END LOOP;
        IF v_rol <> 'app_user' AND (has_function_privilege(v_rol, 'public.fn_super_admin_crear(text,text)', 'EXECUTE')
                                  OR has_function_privilege(v_rol, 'public.fn_super_admin_por_correo(text)', 'EXECUTE')) THEN
            RAISE EXCEPTION 'V81 cierre: % puede ejecutar las funciones del KAM', v_rol;
        END IF;
    END LOOP;

    -- Politica retirada, RLS activa.
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'super_admins') THEN
        RAISE EXCEPTION 'V81 cierre: super_admins conserva politicas';
    END IF;
    IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.super_admins'::regclass) THEN
        RAISE EXCEPTION 'V81 cierre: super_admins sin RLS';
    END IF;

    -- Las dos funciones: DEFINER, search_path fijo, sin EXECUTE para PUBLIC, EXECUTE para app_user.
    FOREACH v_fn IN ARRAY ARRAY['public.fn_super_admin_por_correo(text)'::regprocedure,
                                'public.fn_super_admin_crear(text,text)'::regprocedure] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid = v_fn AND p.prosecdef
                          AND 'search_path=pg_catalog, public, pg_temp' = ANY (p.proconfig)) THEN
            RAISE EXCEPTION 'V81 cierre: % no es DEFINER con search_path fijo', v_fn;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_proc p, aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a
                    WHERE p.oid = v_fn AND a.grantee = 0 AND a.privilege_type = 'EXECUTE') THEN
            RAISE EXCEPTION 'V81 cierre: PUBLIC ejecuta %', v_fn;
        END IF;
        IF NOT has_function_privilege('app_user', v_fn, 'EXECUTE') THEN
            RAISE EXCEPTION 'V81 cierre: app_user no ejecuta %', v_fn;
        END IF;
    END LOOP;

    -- Funcionan: crear, leer por correo sin distinguir mayusculas, rechazar lo invalido.
    v_id := public.fn_super_admin_crear(upper(v_correo), v_hash);
    SELECT count(*) INTO v_n FROM public.fn_super_admin_por_correo(v_correo) f
     WHERE f.id = v_id AND f.email = v_correo AND f.password_hash = v_hash;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V81 cierre: la fila creada no se lee por correo (%)', v_n;
    END IF;
    BEGIN
        PERFORM public.fn_super_admin_crear('sin-arroba', v_hash);
        RAISE EXCEPTION 'V81 cierre: acepto un correo sin arroba';
    EXCEPTION WHEN invalid_parameter_value THEN NULL;
    END;
    BEGIN
        PERFORM public.fn_super_admin_crear('otro__v81__@suresell.invalid', 'clave-en-claro');
        RAISE EXCEPTION 'V81 cierre: acepto una clave sin hash BCrypt';
    EXCEPTION WHEN invalid_parameter_value THEN NULL;
    END;
    BEGIN
        PERFORM public.fn_super_admin_crear(v_correo, v_hash);
        RAISE EXCEPTION 'V81 cierre: acepto un correo repetido';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    -- Rastro cero.
    DELETE FROM public.super_admins WHERE id = v_id;
    SELECT count(*) INTO v_n FROM public.super_admins WHERE email LIKE '%\_\_v81\_\_%' OR email LIKE '%\_\_prueba\_v81\_\_%';
    IF v_n <> 0 THEN
        RAISE EXCEPTION 'V81 cierre: quedan % filas de prueba', v_n;
    END IF;
END $cierre$;
