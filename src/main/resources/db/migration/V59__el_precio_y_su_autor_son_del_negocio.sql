-- =====================================================================
-- V59 -- El precio y su autor son del negocio, escrito en la consulta.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F0.5 y F0.6.
--
-- ── 1 · fn_precio_para no preguntaba de quién es el cliente ───────────
--
-- V45 la creó SECURITY INVOKER y ninguna de sus consultas filtra por negocio:
-- busca el cliente solo por documento, encadena lista y línea sin volver a
-- preguntar, y la rama BASE lee `menu_products` solo por producto. Hoy no
-- muerde porque la aplicación corre como `app_user` y V45 deja RLS FORZADO en
-- esas tablas. Pero el filtro que DECIDE el resultado no puede depender de
-- RLS: falla en cuanto consulta un rol que lo salta.
--
-- Medido el 2026-09-13 con Testcontainers sobre V1-V58, como dueño de la
-- base y con `app.tenant_id` fijado en el negocio A, cuando B tiene un
-- cliente con el mismo documento (lo normal: un NIT compra a dos
-- distribuidoras):
--
--     cliente de A, producto con línea    -> 1 LISTA   (la de B; debía ser 100000)
--     cliente de A, producto sin línea    -> ERROR: more than one row returned
--                                            by a subquery used as an expression
--     SIN negocio fijado                  -> 1 LISTA   (debía no resolver nada)
--
-- Como `app_user` respondía bien y sigue igual. La prueba permanente es
-- `PrecioConFiltroDeNegocioTest` (roja sobre V58, verde con esta).
--
-- Cuerpo de partida: el de V45, el único que la define (V46 solo la llama;
-- verificado con grep sobre las 57 migraciones y con pg_get_functiondef
-- tras migrar V1-V58). Cambia UNA cosa: cada lectura lleva
-- `tenant_id = <negocio de la sesión>`. Firma, volatilidad, SECURITY INVOKER,
-- orden de escalas y forma del resultado, idénticos.
--
-- ── 2 · Quién fijó el precio, con identidad real ──────────────────────
--
-- `listas_precio.creado_por`, `listas_precio_items.usuario_id` y
-- `clientes.creado_por` son TEXT y los rellenaba la cabecera `X-User-Name`,
-- que escribe el cliente HTTP a su gusto. Un precio es un dato de dinero:
-- V37 ya explicó por qué su autor no puede ser un nombre suelto.
--
-- Se añade `autor_id BIGINT -> users(id)` en las tres, NULLABLE
-- (expand/contract, como V37). Las columnas TEXT se quedan: la aplicación
-- pasa a escribir en ellas el correo del token, no la cabecera. Meter el id
-- numérico en el TEXT habría cambiado el significado del campo sin cambiarle
-- el nombre.
--
-- Un autor de otro negocio se rechaza con P0001 (nunca 0A000: Hikari cierra
-- la conexión). `fn_lpi_solo_se_cierra` (V45) gana `autor_id` en su lista de
-- columnas que no se editan, o el UPDATE que versiona un precio sería una
-- puerta para reescribir el autor.
--
-- IMPACTO: tres columnas nullable, un disparador BEFORE INSERT/UPDATE por
-- tabla y dos funciones redefinidas. Cero filas cambian.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · fn_precio_para con el negocio escrito ───────────────────────
CREATE OR REPLACE FUNCTION fn_precio_para(
    p_documento TEXT,
    p_producto  TEXT,
    p_cantidad  INTEGER,
    p_momento   TIMESTAMPTZ DEFAULT now())
RETURNS TABLE (precio NUMERIC, origen TEXT, lista_precio_item_id UUID, lista_precio_id UUID)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    -- El negocio sale de la sesión, como RLS; pero aquí se ESCRIBE. Sin
    -- negocio fijado es NULL y ninguna comparación pasa: no hay precio.
    WITH negocio AS (
        SELECT NULLIF(current_setting('app.tenant_id', true), '') AS id
    ),
    lista AS (
        SELECT c.lista_precio_id
          FROM public.clientes c, negocio n
         WHERE c.tenant_id = n.id
           AND c.documento = p_documento AND c.activo
    ),
    linea AS (
        SELECT i.precio, i.id, i.lista_id
          FROM public.listas_precio_items i
          JOIN lista l ON l.lista_precio_id = i.lista_id
          JOIN public.listas_precio lp ON lp.id = i.lista_id AND lp.activa
          CROSS JOIN negocio n
         WHERE i.tenant_id = n.id
           AND lp.tenant_id = n.id
           AND i.producto_id = p_producto
           AND i.cantidad_minima <= GREATEST(p_cantidad, 1)
           AND i.vigente_desde <= p_momento
           AND (i.vigente_hasta IS NULL OR i.vigente_hasta > p_momento)
         ORDER BY i.cantidad_minima DESC, i.vigente_desde DESC
         LIMIT 1
    )
    SELECT l.precio, 'LISTA', l.id, l.lista_id FROM linea l
    UNION ALL
    SELECT mp.price::numeric, 'BASE', NULL::uuid, (SELECT lista_precio_id FROM lista)
      FROM public.menu_products mp, negocio n
     WHERE mp.tenant_id = n.id
       AND mp.id_product = p_producto
       AND NOT EXISTS (SELECT 1 FROM linea)
    LIMIT 1;
$$;

COMMENT ON FUNCTION fn_precio_para IS
    'El precio para un cliente, un producto y una cantidad en un momento, DENTRO '
    'del negocio de la sesion (app.tenant_id, filtrado en la consulta y no solo '
    'por RLS): la linea vigente de su lista con la mayor escala alcanzada (LISTA) '
    'o el precio base del producto (BASE). Sin fila si el producto no existe o '
    'no hay negocio fijado. V45, filtro por negocio en V59.';

-- ── 2 · Autor con identidad real ────────────────────────────────────
ALTER TABLE listas_precio       ADD COLUMN IF NOT EXISTS autor_id BIGINT NULL REFERENCES users(id);
ALTER TABLE listas_precio_items ADD COLUMN IF NOT EXISTS autor_id BIGINT NULL REFERENCES users(id);
ALTER TABLE clientes            ADD COLUMN IF NOT EXISTS autor_id BIGINT NULL REFERENCES users(id);

COMMENT ON COLUMN listas_precio.autor_id IS
    'Quien creo la lista: users(id) del token. NULL = anterior a V59. '
    'creado_por (TEXT) queda con el correo del token.';
COMMENT ON COLUMN listas_precio_items.autor_id IS
    'Quien fijo el precio: users(id) del token. NULL = anterior a V59. '
    'usuario_id (TEXT, pese al nombre) queda con el correo del token.';
COMMENT ON COLUMN clientes.autor_id IS
    'Quien registro el cliente: users(id) del token. NULL = anterior a V59.';

CREATE OR REPLACE FUNCTION fn_autor_es_del_negocio()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.autor_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.users u WHERE u.id = NEW.autor_id AND u.tenant_id = NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'El autor % no es un usuario de este negocio.', NEW.autor_id
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_listas_precio_autor ON listas_precio;
CREATE TRIGGER trg_listas_precio_autor BEFORE INSERT OR UPDATE OF autor_id, tenant_id ON listas_precio
    FOR EACH ROW EXECUTE FUNCTION fn_autor_es_del_negocio();
DROP TRIGGER IF EXISTS trg_lpi_autor ON listas_precio_items;
CREATE TRIGGER trg_lpi_autor BEFORE INSERT OR UPDATE OF autor_id, tenant_id ON listas_precio_items
    FOR EACH ROW EXECUTE FUNCTION fn_autor_es_del_negocio();
DROP TRIGGER IF EXISTS trg_clientes_autor ON clientes;
CREATE TRIGGER trg_clientes_autor BEFORE INSERT OR UPDATE OF autor_id, tenant_id ON clientes
    FOR EACH ROW EXECUTE FUNCTION fn_autor_es_del_negocio();

-- El cuerpo de V45, con `autor_id` en la lista de lo que no se edita.
CREATE OR REPLACE FUNCTION fn_lpi_solo_se_cierra()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.vigente_hasta IS NOT NULL THEN
        RAISE EXCEPTION 'La linea de precio ya estaba cerrada: no se reabre ni se edita. Abre otra.';
    END IF;
    IF NEW.vigente_hasta IS NULL
       OR NEW.precio IS DISTINCT FROM OLD.precio
       OR NEW.producto_id IS DISTINCT FROM OLD.producto_id
       OR NEW.cantidad_minima IS DISTINCT FROM OLD.cantidad_minima
       OR NEW.lista_id IS DISTINCT FROM OLD.lista_id
       OR NEW.vigente_desde IS DISTINCT FROM OLD.vigente_desde
       OR NEW.fuente IS DISTINCT FROM OLD.fuente
       OR NEW.confianza IS DISTINCT FROM OLD.confianza
       OR NEW.usuario_id IS DISTINCT FROM OLD.usuario_id
       OR NEW.autor_id IS DISTINCT FROM OLD.autor_id THEN
        RAISE EXCEPTION 'Un precio no se edita: se cierra la linea vigente y se abre otra con el precio nuevo.';
    END IF;
    RETURN NEW;
END $$;

-- =====================================================================
-- La comprobación, por comportamiento, con el rol que MIGRA (salta RLS):
-- justo el caso en que un filtro delegado fallaría.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v59_a__';
    b CONSTANT TEXT := '__prueba_v59_b__';
    v_lista_a UUID; v_lista_b UUID;
    v_autor_a BIGINT; v_autor_b BIGINT;
    v_precio NUMERIC; v_origen TEXT;
    v_rechazado BOOLEAN;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V59 A', 'basico'), (b, 'Prueba V59 B', 'basico');
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('a@prueba-v59.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_autor_a;
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('b@prueba-v59.invalid', '!', b, 'admin', 'disabled') RETURNING id INTO v_autor_b;

    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('__p59_arroz__', a, 'Arroz V59', 120000, true),
           ('__p59_aceite__', a, 'Aceite V59', 210000, true);

    INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por, autor_id)
    VALUES (a, 'dist', 'Distribuidor', 'a@prueba-v59.invalid', v_autor_a) RETURNING id INTO v_lista_a;
    INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por, autor_id)
    VALUES (b, 'dist', 'Distribuidor', 'b@prueba-v59.invalid', v_autor_b) RETURNING id INTO v_lista_b;
    INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, autor_id)
    VALUES (a, v_lista_a, '__p59_arroz__', 1, 100000, 'a@prueba-v59.invalid', 'declarado_comerciante', 1, v_autor_a),
           (b, v_lista_b, '__p59_arroz__', 1, 1,      'b@prueba-v59.invalid', 'declarado_comerciante', 1, v_autor_b);
    INSERT INTO clientes (tenant_id, documento, nombre, lista_precio_id, creado_por, autor_id)
    VALUES (a, '__doc59__', 'Tienda A', v_lista_a, 'a@prueba-v59.invalid', v_autor_a),
           (b, '__doc59__', 'Tienda B', v_lista_b, 'b@prueba-v59.invalid', v_autor_b);

    -- 1a. Con el negocio A, la lista de A (sobre V58 salía la de B: 1).
    PERFORM set_config('app.tenant_id', a, true);
    SELECT precio, origen INTO v_precio, v_origen FROM fn_precio_para('__doc59__', '__p59_arroz__', 1, now());
    IF v_precio IS DISTINCT FROM 100000 OR v_origen IS DISTINCT FROM 'LISTA' THEN
        RAISE EXCEPTION 'V59: con el negocio A el precio fue % % y debia ser 100000 LISTA', v_precio, v_origen;
    END IF;

    -- 1b. Sin línea, el BASE de A (sobre V58: error de subconsulta con dos filas).
    SELECT precio, origen INTO v_precio, v_origen FROM fn_precio_para('__doc59__', '__p59_aceite__', 1, now());
    IF v_precio IS DISTINCT FROM 210000 OR v_origen IS DISTINCT FROM 'BASE' THEN
        RAISE EXCEPTION 'V59: sin linea el precio fue % % y debia ser 210000 BASE', v_precio, v_origen;
    END IF;

    -- 1c. Con el negocio B, el producto de A no existe para B.
    PERFORM set_config('app.tenant_id', b, true);
    IF EXISTS (SELECT 1 FROM fn_precio_para('__doc59__', '__p59_aceite__', 1, now())) THEN
        RAISE EXCEPTION 'V59: el negocio B resolvio el precio base de un producto de A';
    END IF;

    -- 1d. Sin negocio, nada.
    PERFORM set_config('app.tenant_id', '', true);
    IF EXISTS (SELECT 1 FROM fn_precio_para('__doc59__', '__p59_arroz__', 1, now())) THEN
        RAISE EXCEPTION 'V59: sin negocio fijado fn_precio_para devolvio precio';
    END IF;

    -- 2a. Un autor de otro negocio se rechaza, con P0001.
    v_rechazado := false;
    BEGIN
        INSERT INTO clientes (tenant_id, documento, nombre, creado_por, autor_id)
        VALUES (a, '__doc59_ajeno__', 'Autor ajeno', 'b@prueba-v59.invalid', v_autor_b);
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V59: un cliente de A entro con un autor de B';
    END IF;

    -- 2b. El autor de una línea de precio no se reescribe al cerrarla.
    v_rechazado := false;
    BEGIN
        UPDATE listas_precio_items SET vigente_hasta = now(), autor_id = NULL
         WHERE tenant_id = a AND lista_id = v_lista_a;
    EXCEPTION WHEN raise_exception THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V59: se pudo borrar el autor de una linea de precio al cerrarla';
    END IF;

    -- Limpieza: hijos antes que padres.
    DELETE FROM clientes            WHERE tenant_id IN (a, b);
    DELETE FROM listas_precio_items WHERE tenant_id IN (a, b);
    DELETE FROM listas_precio       WHERE tenant_id IN (a, b);
    DELETE FROM menu_products       WHERE tenant_id IN (a, b);
    DELETE FROM users               WHERE tenant_id IN (a, b);
    DELETE FROM tenants             WHERE id IN (a, b);

    -- Y no se confía en la lista de arriba: se le pregunta a la base por
    -- TODAS las tablas con tenant_id (la lección del rastro de V45, que
    -- dejó filas que había creado un disparador y no ella).
    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id IN ($1, $2)', t.s, t.tn)
           INTO n USING a, b;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id IN (a, b)) THEN
        RAISE EXCEPTION 'V59: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V59: fn_precio_para responde dentro del negocio y el autor es del negocio.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TRIGGER IF EXISTS trg_listas_precio_autor ON listas_precio;
-- DROP TRIGGER IF EXISTS trg_lpi_autor ON listas_precio_items;
-- DROP TRIGGER IF EXISTS trg_clientes_autor ON clientes;
-- DROP FUNCTION IF EXISTS fn_autor_es_del_negocio();
-- ALTER TABLE clientes DROP COLUMN IF EXISTS autor_id;
-- ALTER TABLE listas_precio_items DROP COLUMN IF EXISTS autor_id;
-- ALTER TABLE listas_precio DROP COLUMN IF EXISTS autor_id;
-- fn_lpi_solo_se_cierra y fn_precio_para: volver al cuerpo de V45.
