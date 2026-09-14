-- =====================================================================
-- V62 -- El cliente del mayorista, con lo que hace falta para venderle, y
--        la historia de lo que se le cambia. Y el rol del usuario, cerrado.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F1.1 (el
-- CHECK que V60 dejó pendiente) y F1.6 (M-P2 mínimo y `clientes_eventos`).
--
-- ── 1 · users.role, cerrado con lo medido ─────────────────────────────
--
-- V4 lo dejó TEXT libre. Medido el 2026-09-13 con autorización de Santiago:
-- producción admin 7, cajero 4, sistema 1; staging admin 3. El CHECK admite
-- esos tres más `vendedor` (V60, F1.2). `super_admin` no vive en `users`
-- (tabla `super_admins`). Se crea NOT VALID y se valida después: la
-- validación no bloquea escrituras mientras recorre la tabla.
--
-- ── 2 · El cliente ampliado, lo mínimo de F1.6 ────────────────────────
--
-- `tipo_documento`, `razon_social`, `tipo_cliente`, `direccion_entrega`,
-- `municipio_dane`, `correo`, `whatsapp`, `vendedor_id` y `exige_factura`.
-- Todas nulas salvo `exige_factura` (FALSE: la factura electrónica se
-- desincentiva y se enciende por cliente, S2). El dígito de verificación y el
-- resto de lo fiscal llegan con F4.10.
--
-- `tipo_cliente` es la lista cerrada de §6.1 con DULCERIA (Q3), SIN «otro»
-- (regla 10): una especialidad nueva entra por migración con su nombre.
--
-- `direccion_entrega` se llama así a propósito. Es donde se entrega, y el
-- proveedor la ve (lo promete el texto de conexión B2B). Si la DIAN necesita
-- una dirección fiscal distinta, será otra columna.
--
-- `vendedor_id`: el vendedor asignado. Un vendedor de otro negocio se rechaza
-- con P0001, con la misma función de V60.
--
-- ── 3 · clientes_eventos: la historia que `clientes` no guarda ────────
--
-- `clientes` es mutable (decisión de la ola 2) y cambiarle la lista o el plazo
-- borraba lo anterior (NOTAS #2). Un disparador AFTER UPDATE escribe una fila
-- por campo que cambia, con antes, después y autor. Es una escritura por
-- cambio, no por lectura (R14).
--
-- El autor sale de `app.user_id`, que la aplicación fija en la transacción del
-- cambio junto a `app.tenant_id`. Si falta, el evento se escribe igual (no se
-- pierde el cambio) y el disparador lanza un WARNING al log de la base: un
-- evento sin autor es un defecto del camino que lo escribió, y se busca, no se
-- descubre tarde. Las pruebas exigen autor en el camino de la API.
--
-- IMPACTO: un CHECK en `users`, nueve columnas en `clientes` (una con DEFAULT
-- constante, sin reescritura), una tabla nueva con RLS FORCE y un disparador.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · users.role ──────────────────────────────────────────────────
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('admin', 'cajero', 'vendedor', 'sistema')) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_role;

-- ── 2 · clientes ────────────────────────────────────────────────────
ALTER TABLE clientes
    ADD COLUMN IF NOT EXISTS tipo_documento    TEXT    NULL,
    ADD COLUMN IF NOT EXISTS razon_social      TEXT    NULL,
    ADD COLUMN IF NOT EXISTS tipo_cliente      TEXT    NULL,
    ADD COLUMN IF NOT EXISTS direccion_entrega TEXT    NULL,
    ADD COLUMN IF NOT EXISTS municipio_dane    TEXT    NULL,
    ADD COLUMN IF NOT EXISTS correo            TEXT    NULL,
    ADD COLUMN IF NOT EXISTS whatsapp          TEXT    NULL,
    ADD COLUMN IF NOT EXISTS vendedor_id       BIGINT  NULL REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS exige_factura     BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_tipo_documento;
ALTER TABLE clientes ADD CONSTRAINT ck_clientes_tipo_documento
    CHECK (tipo_documento IS NULL OR tipo_documento IN ('CC', 'NIT', 'CE', 'PAS', 'PPT', 'TI'));
ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_tipo_cliente;
ALTER TABLE clientes ADD CONSTRAINT ck_clientes_tipo_cliente
    CHECK (tipo_cliente IS NULL OR tipo_cliente IN (
        'TIENDA_DE_BARRIO', 'MINIMERCADO', 'SUPERMERCADO', 'GRANERO', 'LICORERIA', 'DROGUERIA',
        'FERRETERIA', 'MISCELANEA', 'PAPELERIA', 'DULCERIA', 'PANADERIA', 'RESTAURANTE',
        'CAFETERIA', 'BAR_CIGARRERIA', 'HOTEL', 'INSTITUCIONAL', 'SUBDISTRIBUIDOR'));
ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_municipio_dane;
ALTER TABLE clientes ADD CONSTRAINT ck_clientes_municipio_dane
    CHECK (municipio_dane IS NULL OR municipio_dane ~ '^[0-9]{5}$');

COMMENT ON COLUMN clientes.direccion_entrega IS
    'Donde se le ENTREGA al cliente. La ve el proveedor (texto de conexion B2B). '
    'Una direccion fiscal distinta, si la DIAN la pide (F9), va en otra columna. V62.';
COMMENT ON COLUMN clientes.tipo_cliente IS
    'Lista cerrada sin OTRO (regla 10, Q3): una especialidad nueva entra por migracion. V62.';
COMMENT ON COLUMN clientes.vendedor_id IS
    'Vendedor asignado (users.id del mismo negocio). Un vendedor ve solo sus clientes (F1.6). V62.';
COMMENT ON COLUMN clientes.exige_factura IS
    'La venta a este cliente propone factura electronica. FALSE por defecto: se desincentiva (S2). V62.';

CREATE INDEX IF NOT EXISTS ix_clientes_vendedor
    ON clientes (tenant_id, vendedor_id) WHERE vendedor_id IS NOT NULL;

-- El mismo guardián de V60, sobre la columna de clientes.
CREATE OR REPLACE FUNCTION fn_vendedor_del_cliente_es_del_negocio()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.vendedor_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.users u WHERE u.id = NEW.vendedor_id AND u.tenant_id = NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'El vendedor % no es un usuario de este negocio.', NEW.vendedor_id
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_clientes_vendedor ON clientes;
CREATE TRIGGER trg_clientes_vendedor BEFORE INSERT OR UPDATE OF vendedor_id, tenant_id ON clientes
    FOR EACH ROW WHEN (NEW.vendedor_id IS NOT NULL)
    EXECUTE FUNCTION fn_vendedor_del_cliente_es_del_negocio();

-- ── 3 · clientes_eventos ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clientes_eventos (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      TEXT        NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    cliente_id     UUID        NOT NULL REFERENCES clientes(id),
    campo          TEXT        NOT NULL,
    valor_anterior TEXT        NULL,
    valor_nuevo    TEXT        NULL,
    usuario_id     BIGINT      NULL REFERENCES users(id),
    ocurrido_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_clientes_eventos PRIMARY KEY (id),
    CONSTRAINT ck_clientes_eventos_campo CHECK (campo IN (
        'nombre', 'lista_precio_id', 'plazo_dias', 'activo', 'vendedor_id', 'tipo_cliente',
        'exige_factura', 'tipo_documento', 'razon_social', 'direccion_entrega', 'municipio_dane',
        'correo', 'whatsapp', 'telefono'))
);
CREATE INDEX IF NOT EXISTS ix_clientes_eventos_cliente ON clientes_eventos (tenant_id, cliente_id, ocurrido_en);

COMMENT ON TABLE clientes_eventos IS
    'Una fila por campo que cambia en clientes: antes, despues, autor (app.user_id) y cuando. '
    'Solo anexa. La escribe trg_clientes_eventos; nadie mas. V62.';

CREATE OR REPLACE FUNCTION fn_clientes_eventos()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_autor_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_autor     BIGINT := CASE WHEN v_autor_txt ~ '^[0-9]+$' THEN v_autor_txt::BIGINT END;
    v_campo     TEXT;
    v_antes     TEXT;
    v_despues   TEXT;
    v_escritos  INT := 0;
BEGIN
    FOR v_campo, v_antes, v_despues IN
        SELECT c.campo, c.antes, c.despues FROM (VALUES
            ('nombre',            OLD.nombre,                   NEW.nombre),
            ('lista_precio_id',   OLD.lista_precio_id::text,    NEW.lista_precio_id::text),
            ('plazo_dias',        OLD.plazo_dias::text,         NEW.plazo_dias::text),
            ('activo',            OLD.activo::text,             NEW.activo::text),
            ('vendedor_id',       OLD.vendedor_id::text,        NEW.vendedor_id::text),
            ('tipo_cliente',      OLD.tipo_cliente,             NEW.tipo_cliente),
            ('exige_factura',     OLD.exige_factura::text,      NEW.exige_factura::text),
            ('tipo_documento',    OLD.tipo_documento,           NEW.tipo_documento),
            ('razon_social',      OLD.razon_social,             NEW.razon_social),
            ('direccion_entrega', OLD.direccion_entrega,        NEW.direccion_entrega),
            ('municipio_dane',    OLD.municipio_dane,           NEW.municipio_dane),
            ('correo',            OLD.correo,                   NEW.correo),
            ('whatsapp',          OLD.whatsapp,                 NEW.whatsapp),
            ('telefono',          OLD.telefono,                 NEW.telefono)
        ) AS c(campo, antes, despues)
        WHERE c.antes IS DISTINCT FROM c.despues
    LOOP
        INSERT INTO public.clientes_eventos (tenant_id, cliente_id, campo, valor_anterior, valor_nuevo, usuario_id)
        VALUES (NEW.tenant_id, NEW.id, v_campo, v_antes, v_despues, v_autor);
        v_escritos := v_escritos + 1;
    END LOOP;
    IF v_escritos > 0 AND v_autor IS NULL THEN
        RAISE WARNING 'clientes_eventos sin autor: cliente % del negocio % cambio % campos sin app.user_id',
            NEW.id, NEW.tenant_id, v_escritos;
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_clientes_eventos ON clientes;
CREATE TRIGGER trg_clientes_eventos AFTER UPDATE ON clientes
    FOR EACH ROW EXECUTE FUNCTION fn_clientes_eventos();

ALTER TABLE clientes_eventos ENABLE ROW LEVEL SECURITY;
ALTER TABLE clientes_eventos FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_clientes_eventos ON clientes_eventos;
CREATE POLICY tenant_isolation_clientes_eventos ON clientes_eventos
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        -- Solo anexa: sin UPDATE ni DELETE. El disparador corre como quien actualiza clientes.
        GRANT SELECT, INSERT ON public.clientes_eventos TO app_user;
    END IF;
END
$$;

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v62_a__';
    b CONSTANT TEXT := '__prueba_v62_b__';
    v_admin BIGINT; v_vendedor BIGINT; v_ajeno BIGINT; v_cliente UUID;
    v_rechazado BOOLEAN; v_n INT; v_autor BIGINT;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- 1. El CHECK de rol.
    v_rechazado := false;
    BEGIN
        INSERT INTO tenants (id, name, plan) VALUES ('__prueba_v62_rol__', 'Rol V62', 'basico');
        INSERT INTO users (email, password_hash, tenant_id, role, status)
        VALUES ('m@prueba-v62.invalid', '!', '__prueba_v62_rol__', 'mesero', 'disabled');
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V62: users.role admitio un rol inventado';
    END IF;
    DELETE FROM tenants WHERE id = '__prueba_v62_rol__';

    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V62 A', 'basico'), (b, 'Prueba V62 B', 'basico');
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('admin@prueba-v62.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_admin;
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('ana@prueba-v62.invalid', '!', a, 'vendedor', 'disabled') RETURNING id INTO v_vendedor;
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('luis@prueba-v62.invalid', '!', b, 'vendedor', 'disabled') RETURNING id INTO v_ajeno;
    PERFORM set_config('app.tenant_id', a, true);

    INSERT INTO clientes (tenant_id, documento, nombre, creado_por, tipo_cliente, direccion_entrega)
    VALUES (a, '__doc62__', 'Tienda V62', 'v62', 'DULCERIA', 'Calle 1 # 2-3') RETURNING id INTO v_cliente;

    -- 2. Un tipo de cliente fuera de la lista se rechaza.
    v_rechazado := false;
    BEGIN
        UPDATE clientes SET tipo_cliente = 'OTRO' WHERE id = v_cliente;
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V62: tipo_cliente admitio OTRO';
    END IF;

    -- 3. Un vendedor de otro negocio se rechaza con P0001.
    v_rechazado := false;
    BEGIN
        UPDATE clientes SET vendedor_id = v_ajeno WHERE id = v_cliente;
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V62: un cliente de A tomo un vendedor de B';
    END IF;

    -- 4. Cambiar dos campos con autor deja dos eventos con autor; no cambiar nada, ninguno.
    PERFORM set_config('app.user_id', v_admin::text, true);
    UPDATE clientes SET vendedor_id = v_vendedor, plazo_dias = 8 WHERE id = v_cliente;
    UPDATE clientes SET nombre = nombre WHERE id = v_cliente;
    SELECT count(*), min(usuario_id) INTO v_n, v_autor FROM clientes_eventos WHERE cliente_id = v_cliente;
    IF v_n <> 2 OR v_autor IS DISTINCT FROM v_admin THEN
        RAISE EXCEPTION 'V62: esperaba 2 eventos con autor %, hubo % con autor %', v_admin, v_n, v_autor;
    END IF;

    -- Limpieza.
    PERFORM set_config('app.user_id', '', true);
    PERFORM set_config('app.tenant_id', '', true);
    DELETE FROM clientes_eventos WHERE tenant_id IN (a, b);
    DELETE FROM clientes         WHERE tenant_id IN (a, b);
    DELETE FROM users            WHERE tenant_id IN (a, b);
    DELETE FROM tenants          WHERE id IN (a, b);

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario', 'pedidos', 'red')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id IN ($1, $2, $3)', t.s, t.tn)
           INTO n USING a, b, '__prueba_v62_rol__';
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id IN (a, b, '__prueba_v62_rol__')) THEN
        RAISE EXCEPTION 'V62: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V62: rol cerrado, cliente ampliado y su historia con autor.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TRIGGER IF EXISTS trg_clientes_eventos ON clientes; DROP FUNCTION IF EXISTS fn_clientes_eventos();
-- DROP TABLE IF EXISTS clientes_eventos;
-- DROP TRIGGER IF EXISTS trg_clientes_vendedor ON clientes; DROP FUNCTION IF EXISTS fn_vendedor_del_cliente_es_del_negocio();
-- DROP INDEX IF EXISTS ix_clientes_vendedor;
-- ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_municipio_dane,
--     DROP CONSTRAINT IF EXISTS ck_clientes_tipo_cliente, DROP CONSTRAINT IF EXISTS ck_clientes_tipo_documento;
-- ALTER TABLE clientes DROP COLUMN IF EXISTS exige_factura, DROP COLUMN IF EXISTS vendedor_id, ...
-- ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_role;
