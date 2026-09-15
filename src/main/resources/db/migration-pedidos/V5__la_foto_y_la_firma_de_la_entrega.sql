-- =====================================================================
-- V5 pedidos -- La foto y la firma de una entrega (plan de mayoristas F5.7b;
--               diseño docs/planes/F5-7B-ALMACENAMIENTO-ENTREGAS.md, decisiones de ECM del 2026-09-15).
--
-- Los objetos viven en Supabase Storage (bucket privado `entregas`); aquí solo la RUTA y su rastro.
--
-- 1. `foto_asset_id` y `firma_asset_id` (V4, TEXT sin FK, nunca escritas) guardan la RUTA
--    `<tenant>/<pedido>/<entrega>/foto.jpg` y `.../firma.png`. No se crean columnas nuevas para lo mismo.
-- 2. Una PRUEBA es inmutable: una vez registrada una ruta no se cambia (la función falla), y tras la purga
--    tampoco se reabre. Cuándo y quién la registró queda en columnas propias, por foto y por firma: una foto
--    subida un día después no es lo mismo que una del momento (F6 sin red lo hará normal): no se rechaza, se ve.
-- 3. Retención de un año POR EVENTO, sin temporizador: `fn_entregas_purgar` vacía las rutas de hasta N entregas
--    de más de un año del negocio de la SESIÓN y deja constancia en `entregas_purgadas` (con ids y rutas). La fila
--    de la entrega no se borra. El borrado del objeto en el almacén lo hace -mt antes de llamarla.
-- 4. app_user no gana UPDATE: escribe solo por estas dos funciones SECURITY DEFINER con search_path fijo.
--
-- IMPACTO: seis columnas nulas en `pedidos.entregas`, un índice parcial (vacío hoy: ninguna ruta escrita), una
-- tabla nueva vacía y dos funciones. Sin datos que migrar.
-- =====================================================================

SET lock_timeout = '3s';

ALTER TABLE pedidos.entregas
    ADD COLUMN foto_registrada_en   TIMESTAMPTZ NULL,
    ADD COLUMN foto_registrada_por  BIGINT      NULL,
    ADD COLUMN firma_registrada_en  TIMESTAMPTZ NULL,
    ADD COLUMN firma_registrada_por BIGINT      NULL,
    ADD COLUMN prueba_purgada_en    TIMESTAMPTZ NULL;
ALTER TABLE pedidos.entregas ADD CONSTRAINT ck_entregas_foto_con_rastro
    CHECK ((foto_asset_id IS NULL OR foto_registrada_en IS NOT NULL AND foto_registrada_por IS NOT NULL)
       AND (firma_asset_id IS NULL OR firma_registrada_en IS NOT NULL AND firma_registrada_por IS NOT NULL));

COMMENT ON COLUMN pedidos.entregas.foto_asset_id IS
    'F5.7b: RUTA del objeto en el bucket entregas (<tenant>/<pedido>/<entrega>/foto.jpg), no un id. La pone solo '
    'fn_entrega_registrar_prueba, una vez; la purga al año la vuelve NULL. V5 pedidos.';
COMMENT ON COLUMN pedidos.entregas.firma_asset_id IS
    'F5.7b: RUTA del objeto en el bucket entregas (<tenant>/<pedido>/<entrega>/firma.png), no un id. Igual que la foto. V5 pedidos.';
COMMENT ON COLUMN pedidos.entregas.prueba_purgada_en IS
    'F5.7b: cuándo se borraron foto y firma por la retención de un año. Con valor, no se registra otra prueba. V5 pedidos.';

-- La purga busca, por negocio, lo viejo que todavía tiene ruta.
CREATE INDEX ix_entregas_prueba_por_purgar ON pedidos.entregas (tenant_id, registrado_en)
    WHERE foto_asset_id IS NOT NULL OR firma_asset_id IS NOT NULL;

CREATE TABLE pedidos.entregas_purgadas (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   TEXT        NOT NULL,
    corrida_en  TIMESTAMPTZ NOT NULL DEFAULT now(),
    usuario     BIGINT      NOT NULL,
    conteo      INTEGER     NOT NULL,
    entregas    UUID[]      NOT NULL,
    rutas       TEXT[]      NOT NULL,
    CONSTRAINT pk_entregas_purgadas PRIMARY KEY (id),
    CONSTRAINT ck_entregas_purgadas_conteo CHECK (conteo > 0 AND cardinality(entregas) = conteo)
);
CREATE INDEX ix_entregas_purgadas_negocio ON pedidos.entregas_purgadas (tenant_id, corrida_en);
COMMENT ON TABLE pedidos.entregas_purgadas IS
    'F5.7b: constancia de cada purga por retención (un año): negocio, quién la disparó, cuántas, qué entregas y qué rutas. Solo anexa. V5 pedidos.';
ALTER TABLE pedidos.entregas_purgadas ENABLE ROW LEVEL SECURITY;
ALTER TABLE pedidos.entregas_purgadas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_entregas_purgadas ON pedidos.entregas_purgadas
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- ── Registrar la prueba: una vez, con la ruta de ESTA entrega de ESTE negocio ──
CREATE FUNCTION pedidos.fn_entrega_registrar_prueba(p_entrega_id UUID, p_foto_ruta TEXT, p_firma_ruta TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    e RECORD;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_foto_ruta IS NULL AND p_firma_ruta IS NULL THEN
        RAISE EXCEPTION 'Una prueba de entrega lleva foto, firma o las dos.' USING ERRCODE = 'P0001';
    END IF;
    SELECT en.id, en.pedido_id, en.foto_asset_id, en.firma_asset_id, en.prueba_purgada_en INTO e
      FROM pedidos.entregas en WHERE en.tenant_id = s.negocio AND en.id = p_entrega_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Esa entrega no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    IF e.prueba_purgada_en IS NOT NULL THEN
        RAISE EXCEPTION 'La prueba de esta entrega ya se registro y se purgo por retencion: no se registra otra.' USING ERRCODE = 'P0001';
    END IF;
    IF (p_foto_ruta IS NOT NULL AND e.foto_asset_id IS NOT NULL) OR (p_firma_ruta IS NOT NULL AND e.firma_asset_id IS NOT NULL) THEN
        RAISE EXCEPTION 'La prueba de esta entrega ya se registro: una prueba no se cambia.' USING ERRCODE = 'P0001';
    END IF;
    IF p_foto_ruta IS NOT NULL AND p_foto_ruta <> s.negocio || '/' || e.pedido_id || '/' || e.id || '/foto.jpg' THEN
        RAISE EXCEPTION 'La ruta de la foto no es la de esta entrega.' USING ERRCODE = 'P0001';
    END IF;
    IF p_firma_ruta IS NOT NULL AND p_firma_ruta <> s.negocio || '/' || e.pedido_id || '/' || e.id || '/firma.png' THEN
        RAISE EXCEPTION 'La ruta de la firma no es la de esta entrega.' USING ERRCODE = 'P0001';
    END IF;
    UPDATE pedidos.entregas en
       SET foto_asset_id        = COALESCE(p_foto_ruta, en.foto_asset_id),
           foto_registrada_en   = CASE WHEN p_foto_ruta IS NULL THEN en.foto_registrada_en ELSE now() END,
           foto_registrada_por  = CASE WHEN p_foto_ruta IS NULL THEN en.foto_registrada_por ELSE s.usuario END,
           firma_asset_id       = COALESCE(p_firma_ruta, en.firma_asset_id),
           firma_registrada_en  = CASE WHEN p_firma_ruta IS NULL THEN en.firma_registrada_en ELSE now() END,
           firma_registrada_por = CASE WHEN p_firma_ruta IS NULL THEN en.firma_registrada_por ELSE s.usuario END
     WHERE en.tenant_id = s.negocio AND en.id = p_entrega_id;
END $$;
COMMENT ON FUNCTION pedidos.fn_entrega_registrar_prueba(UUID, TEXT, TEXT) IS
    'F5.7b: registra la ruta de la foto y/o la firma de una entrega del negocio de la sesion. Una vez: si ya hay ruta, o '
    'se purgo, falla. La ruta tiene que ser la de esa entrega. V5 pedidos.';

-- ── Purgar por retención: el negocio es el de la SESIÓN ─────────────
CREATE FUNCTION pedidos.fn_entregas_purgar(p_entregas UUID[])
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_ids UUID[];
    v_rutas TEXT[];
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    -- Primero se bloquean y se leen (las rutas van a la constancia), después se vacían.
    SELECT array_agg(v.id ORDER BY v.id),
           COALESCE(array_agg(r.ruta ORDER BY v.id, r.ruta) FILTER (WHERE r.ruta IS NOT NULL), ARRAY[]::TEXT[])
      INTO v_ids, v_rutas
      FROM (SELECT en.id, en.foto_asset_id, en.firma_asset_id
              FROM pedidos.entregas en
             WHERE en.tenant_id = s.negocio
               AND en.id = ANY (p_entregas)
               AND en.registrado_en < now() - interval '1 year'
               AND (en.foto_asset_id IS NOT NULL OR en.firma_asset_id IS NOT NULL)
             FOR UPDATE) v
      LEFT JOIN LATERAL unnest(ARRAY[v.foto_asset_id, v.firma_asset_id]) AS r(ruta) ON true;
    IF v_ids IS NULL THEN
        RETURN 0;
    END IF;
    SELECT array_agg(DISTINCT i ORDER BY i) INTO v_ids FROM unnest(v_ids) i;
    UPDATE pedidos.entregas en
       SET foto_asset_id = NULL, firma_asset_id = NULL, prueba_purgada_en = now()
     WHERE en.tenant_id = s.negocio AND en.id = ANY (v_ids);
    INSERT INTO pedidos.entregas_purgadas (tenant_id, usuario, conteo, entregas, rutas)
    VALUES (s.negocio, s.usuario, cardinality(v_ids), v_ids, v_rutas);
    RETURN cardinality(v_ids);
END $$;
COMMENT ON FUNCTION pedidos.fn_entregas_purgar(UUID[]) IS
    'F5.7b: vacía foto y firma de las entregas dadas que tengan mas de un año, SOLO del negocio de la sesion, marca '
    'prueba_purgada_en y deja constancia en entregas_purgadas. Las que no cumplan se ignoran. V5 pedidos.';

REVOKE ALL ON FUNCTION pedidos.fn_entrega_registrar_prueba(UUID, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_entregas_purgar(UUID[]) FROM PUBLIC;

DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON pedidos.entregas_purgadas TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_entrega_registrar_prueba(UUID, TEXT, TEXT) TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_entregas_purgar(UUID[]) TO app_user;
    END IF;
END
$permisos$;

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v5p_a__';
    b CONSTANT TEXT := '__prueba_v5p_b__';
    v_admin BIGINT; v_admin_b BIGINT;
    v_pedido UUID; v_entrega UUID;
    v_ruta_foto TEXT; v_ruta_firma TEXT;
    v_n INTEGER;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
         WHERE ns.nspname = 'pedidos' AND p.prosecdef) <> 5
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
                   WHERE ns.nspname = 'pedidos' AND p.prosecdef
                     AND (NOT COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pedidos, public, pg_temp'], false)
                          OR has_function_privilege('public', p.oid, 'EXECUTE'))) THEN
        RAISE EXCEPTION 'V5 pedidos: las SECURITY DEFINER de pedidos no son cinco con search_path fijo y sin EXECUTE para PUBLIC';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'pedidos.entregas', 'UPDATE')
            OR has_table_privilege('app_user', 'pedidos.entregas_purgadas', 'INSERT')
            OR has_table_privilege('app_user', 'pedidos.entregas_purgadas', 'UPDATE')
            OR has_table_privilege('app_user', 'pedidos.entregas_purgadas', 'DELETE')) THEN
        RAISE EXCEPTION 'V5 pedidos: app_user escribe entregas o constancias sin pasar por las funciones';
    END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V5 pedidos A', 'basico'), (b, 'Prueba V5 pedidos B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v5p-a@prueba.invalid', '!', a, 'admin') RETURNING id INTO v_admin;
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v5p-b@prueba.invalid', '!', b, 'admin') RETURNING id INTO v_admin_b;
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active) VALUES ('v5p-arroz', a, 'Arroz', 5000, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v5p-tienda', 'Tienda', 8, 'v5p');
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_admin::text, true);
    v_pedido := pedidos.fn_pedido_crear('ENVIADO', 'v5p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v5p-arroz","cantidad":4}]', now() - interval '1 hour', 'v5p-1');
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v5p-1-c');
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL, NULL, now(), 'v5p-1-d');
    PERFORM pedidos.fn_pedido_entregar(v_pedido, 'ENTREGA_FALLIDA', 'CERRADO', NULL, NULL, NULL, NULL, NULL, NULL, now(), 'v5p-1-e');
    -- El disparador diferido «todo evento de entrega tiene su prueba» se comprueba AQUÍ, con la entrega presente; si
    -- esperara al commit, la limpieza del final ya la habría borrado (el mismo cuidado que en el cierre de V4).
    SET CONSTRAINTS pedidos.trg_evento_de_entrega_con_prueba IMMEDIATE;
    SET CONSTRAINTS pedidos.trg_evento_de_entrega_con_prueba DEFERRED;
    SELECT id INTO v_entrega FROM pedidos.entregas WHERE tenant_id = a AND pedido_id = v_pedido;
    v_ruta_foto := a || '/' || v_pedido || '/' || v_entrega || '/foto.jpg';
    v_ruta_firma := a || '/' || v_pedido || '/' || v_entrega || '/firma.png';

    -- Registrar la foto: con su rastro.
    PERFORM pedidos.fn_entrega_registrar_prueba(v_entrega, v_ruta_foto, NULL);
    IF (SELECT foto_asset_id FROM pedidos.entregas WHERE id = v_entrega) <> v_ruta_foto
       OR (SELECT foto_registrada_por FROM pedidos.entregas WHERE id = v_entrega) <> v_admin
       OR (SELECT foto_registrada_en FROM pedidos.entregas WHERE id = v_entrega) IS NULL THEN
        RAISE EXCEPTION 'V5 pedidos: la foto no quedo registrada con su rastro';
    END IF;
    -- Una prueba no se cambia; la firma sí se puede añadir después, una vez.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_entrega_registrar_prueba(v_entrega, v_ruta_foto, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V5 pedidos: una foto ya registrada se volvio a registrar'; END IF;
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_entrega_registrar_prueba(v_entrega, NULL, a || '/otro/' || v_entrega || '/firma.png');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V5 pedidos: una firma con la ruta de otra entrega entro'; END IF;
    PERFORM pedidos.fn_entrega_registrar_prueba(v_entrega, NULL, v_ruta_firma);

    -- La purga no toca lo de menos de un año.
    IF pedidos.fn_entregas_purgar(ARRAY[v_entrega]) <> 0 THEN
        RAISE EXCEPTION 'V5 pedidos: se purgo una prueba de menos de un año';
    END IF;
    -- Con más de un año: la de OTRO negocio no la purga; la del propio sí, con constancia, y no se reabre.
    UPDATE pedidos.entregas SET registrado_en = now() - interval '13 months', ocurrido_en = now() - interval '13 months' WHERE id = v_entrega;
    PERFORM set_config('app.tenant_id', b, true);
    PERFORM set_config('app.user_id', v_admin_b::text, true);
    IF pedidos.fn_entregas_purgar(ARRAY[v_entrega]) <> 0 OR (SELECT foto_asset_id FROM pedidos.entregas WHERE id = v_entrega) IS NULL THEN
        RAISE EXCEPTION 'V5 pedidos: otro negocio purgo una prueba ajena';
    END IF;
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_admin::text, true);
    v_n := pedidos.fn_entregas_purgar(ARRAY[v_entrega]);
    IF v_n <> 1
       OR (SELECT foto_asset_id FROM pedidos.entregas WHERE id = v_entrega) IS NOT NULL
       OR (SELECT firma_asset_id FROM pedidos.entregas WHERE id = v_entrega) IS NOT NULL
       OR (SELECT prueba_purgada_en FROM pedidos.entregas WHERE id = v_entrega) IS NULL
       OR (SELECT count(*) FROM pedidos.entregas_purgadas WHERE tenant_id = a AND conteo = 1 AND entregas = ARRAY[v_entrega]
              AND rutas @> ARRAY[v_ruta_foto, v_ruta_firma]) <> 1 THEN
        RAISE EXCEPTION 'V5 pedidos: la purga de un año no vacio las rutas con su constancia';
    END IF;
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_entrega_registrar_prueba(v_entrega, v_ruta_foto, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V5 pedidos: una prueba purgada se reabrio'; END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM pedidos.entregas_purgadas      WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.entregas               WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos_eventos_lineas WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos_eventos        WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos_lineas         WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos                WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.contadores_de_pedidos  WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes_eventos        WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes                WHERE tenant_id IN (a, b);
    DELETE FROM public.menu_products           WHERE tenant_id IN (a, b);
    DELETE FROM public.users                   WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants                 WHERE id IN (a, b);

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text IN ($1, $2)', t.s, t.tn) INTO v_filas USING a, b;
        IF v_filas > 0 THEN
            quedan := quedan + v_filas;
            donde := donde || t.s || '.' || t.tn || '(' || v_filas || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id IN (a, b)) THEN
        RAISE EXCEPTION 'V5 pedidos: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V5 pedidos: foto y firma registradas una vez con rastro, ruta de otra entrega rechazada; purga solo de mas de un año, solo del negocio de la sesion, con constancia y sin reabrir; cinco DEFINER con search_path; app_user sin UPDATE; 0 restos.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- (antes, decidir qué pasa con los objetos del bucket: sin rutas no se encuentran)
-- DROP FUNCTION pedidos.fn_entregas_purgar(UUID[]); DROP FUNCTION pedidos.fn_entrega_registrar_prueba(UUID, TEXT, TEXT);
-- DROP TABLE pedidos.entregas_purgadas; DROP INDEX pedidos.ix_entregas_prueba_por_purgar;
-- ALTER TABLE pedidos.entregas DROP CONSTRAINT ck_entregas_foto_con_rastro, DROP COLUMN foto_registrada_en, DROP COLUMN foto_registrada_por,
--     DROP COLUMN firma_registrada_en, DROP COLUMN firma_registrada_por, DROP COLUMN prueba_purgada_en;
