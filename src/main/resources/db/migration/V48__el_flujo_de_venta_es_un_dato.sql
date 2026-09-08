-- =====================================================================
-- V48 -- El flujo de venta es un dato, no un `if`.
--
-- LO QUE HABIA
--
-- `sites.pos_mode` con `CHECK (pos_mode IN ('PLAZOLETA','RESTAURANTE'))` (V23)
-- y esos dos nombres repetidos como constantes en tres validaciones de Java
-- (AltaDeNegocioService, SuperAdminService.setSiteMode, SiteService) y en el
-- panel del KAM. Una droguería, que ni cobra por adelantado con rastreador ni
-- abre cuenta por mesa, no cabía: había que elegir entre dos modos que no son
-- el suyo. Y cada vertical nueva volvía a ser un `if` en cuatro sitios, que es
-- exactamente lo que V50 del inventario vino a evitar para el perfil.
--
-- LA DECISION (fundador, 2026-09-08, opción (b))
--
-- El flujo de venta es un dato POR SEDE, de un catálogo cerrado:
--
--   DIRECTO     se cobra y se entrega en el acto
--   MESA        cuenta abierta por mesa, se cobra al final
--   RASTREADOR  se cobra por adelantado y se entrega por rastreador
--
-- Qué flujos admite cada perfil vertical y cuál trae por defecto son FILAS
-- (`perfil_flujos_de_venta`). Añadir una vertical con su flujo es un INSERT
-- aquí y otro en `inventario.perfiles_verticales`; cero código.
--
-- LA EQUIVALENCIA CON LO VIEJO NO VIVE EN EL CODIGO
--
-- Hay POS desplegados que leen `posMode` y solo entienden PLAZOLETA y
-- RESTAURANTE. `pos_mode` se CONSERVA y se DERIVA del flujo por un trigger que
-- lee la columna `pos_mode_legado` del catálogo: DIRECTO y RASTREADOR se
-- reportan como PLAZOLETA, MESA como RESTAURANTE. Y al revés: un escritor viejo
-- que solo ponga `pos_mode` recibe el flujo de `hereda_de_pos_mode`
-- (PLAZOLETA -> RASTREADOR, RESTAURANTE -> MESA). Ninguna de las dos
-- equivalencias está en Java: si mañana cambia, cambia una fila.
--
-- EXPAND, NO CONTRACT
--
-- Esta migración añade; no quita. `pos_mode` y su CHECK de dos valores se
-- quedan (es el dominio del contrato viejo, que no crece). Contraer -- borrar
-- `pos_mode` -- toca cuando ningún cliente lo lea, en otra ola, con medición.
--
-- Aditiva, idempotente, reversible. Rollback al final del fichero.
-- =====================================================================


-- =====================================================================
-- 1 · El catálogo de flujos
-- =====================================================================
CREATE TABLE IF NOT EXISTS flujos_de_venta (
    codigo              TEXT    NOT NULL,
    nombre              TEXT    NOT NULL,
    descripcion         TEXT    NOT NULL,
    -- Qué necesita el POS para operar con este flujo.
    usa_mesas           BOOLEAN NOT NULL,
    usa_rastreador      BOOLEAN NOT NULL,
    -- Lo que recibe un lector VIEJO de `posMode`. Todo flujo tiene uno: un POS
    -- sin actualizar tiene que seguir vendiendo.
    pos_mode_legado     TEXT    NOT NULL,
    -- El valor viejo que este flujo SUSTITUYE (solo dos flujos lo tienen). Es
    -- lo que usa el relleno de esta migración y el trigger cuando un escritor
    -- viejo solo trae `pos_mode`.
    hereda_de_pos_mode  TEXT    NULL,
    -- El flujo con el que nace una sede cuando nadie dice nada (un negocio de
    -- autoservicio, sin perfil). Exactamente uno. Es lo que PLAZOLETA siempre
    -- fue, y el nombre no está en Java: está aquí.
    es_defecto          BOOLEAN NOT NULL DEFAULT false,
    orden               SMALLINT NOT NULL,

    CONSTRAINT pk_flujos_de_venta PRIMARY KEY (codigo),
    CONSTRAINT ck_flujos_codigo CHECK (codigo ~ '^[A-Z][A-Z_]{1,29}$'),
    CONSTRAINT ck_flujos_nombre CHECK (length(btrim(nombre)) > 0),
    -- Un flujo no puede necesitar mesas Y rastreador: son dos formas de entregar.
    CONSTRAINT ck_flujos_entrega CHECK (NOT (usa_mesas AND usa_rastreador)),
    -- El dominio del contrato VIEJO. Cerrado a propósito: no crece nunca.
    CONSTRAINT ck_flujos_pos_mode_legado CHECK (pos_mode_legado IN ('PLAZOLETA', 'RESTAURANTE')),
    CONSTRAINT ck_flujos_hereda CHECK (hereda_de_pos_mode IS NULL
                                      OR hereda_de_pos_mode IN ('PLAZOLETA', 'RESTAURANTE')),
    -- Si un flujo sustituye a un valor viejo, ese valor es también lo que reporta.
    CONSTRAINT ck_flujos_hereda_coherente CHECK (hereda_de_pos_mode IS NULL
                                                OR hereda_de_pos_mode = pos_mode_legado),
    CONSTRAINT uq_flujos_hereda UNIQUE (hereda_de_pos_mode)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_flujos_de_venta_defecto
    ON flujos_de_venta (es_defecto) WHERE es_defecto;

COMMENT ON TABLE flujos_de_venta IS
    'Catálogo cerrado de flujos de venta por sede. Añadir uno es una migración; '
    'la equivalencia con el `pos_mode` viejo es la columna, no código.';

INSERT INTO flujos_de_venta
    (codigo, nombre, descripcion, usa_mesas, usa_rastreador, pos_mode_legado, hereda_de_pos_mode, es_defecto, orden)
VALUES
    ('DIRECTO',    'Venta directa',
     'Se cobra y se entrega en el acto. Sin mesas y sin rastreador.',
     false, false, 'PLAZOLETA', NULL,          false, 1),
    ('MESA',       'Cuenta por mesa',
     'Se abre una cuenta por mesa y se cobra al final. El POS muestra el plano de mesas.',
     true,  false, 'RESTAURANTE', 'RESTAURANTE', false, 2),
    ('RASTREADOR', 'Rastreador y cobro por adelantado',
     'Se cobra al pedir y se entrega llamando al rastreador (plazoleta).',
     false, true,  'PLAZOLETA', 'PLAZOLETA',   true,  3)
ON CONFLICT (codigo) DO NOTHING;


-- =====================================================================
-- 2 · Qué admite cada perfil vertical, y cuál trae por defecto
--
-- El catálogo de perfiles vive en `inventario.perfiles_verticales` (V50 del
-- inventario, otra cadena y otro servicio). Aquí NO hay FK cruzada: las dos
-- cadenas se aplican por separado y en un Postgres con solo `public` esta
-- tabla tiene que poder existir. El código del perfil sigue la misma regla de
-- forma que allí.
-- =====================================================================
CREATE TABLE IF NOT EXISTS perfil_flujos_de_venta (
    perfil_codigo TEXT    NOT NULL,
    flujo         TEXT    NOT NULL,
    es_defecto    BOOLEAN NOT NULL DEFAULT false,

    CONSTRAINT pk_perfil_flujos PRIMARY KEY (perfil_codigo, flujo),
    CONSTRAINT fk_perfil_flujos_flujo FOREIGN KEY (flujo) REFERENCES flujos_de_venta (codigo),
    CONSTRAINT ck_perfil_flujos_codigo CHECK (perfil_codigo ~ '^[a-z][a-z0-9_]{1,39}$')
);

-- Exactamente un flujo por defecto por perfil.
CREATE UNIQUE INDEX IF NOT EXISTS ux_perfil_flujos_defecto
    ON perfil_flujos_de_venta (perfil_codigo) WHERE es_defecto;

COMMENT ON TABLE perfil_flujos_de_venta IS
    'Flujos de venta que admite cada perfil vertical y cuál trae por defecto. '
    'Un restaurante elige entre MESA y RASTREADOR; una droguería vende DIRECTO.';

INSERT INTO perfil_flujos_de_venta (perfil_codigo, flujo, es_defecto) VALUES
    ('restaurante', 'MESA',       true),
    ('restaurante', 'RASTREADOR', false),
    ('drogueria',   'DIRECTO',    true),
    ('minimercado', 'DIRECTO',    true),
    ('mayorista',   'DIRECTO',    true),
    ('ferreteria',  'DIRECTO',    true)
ON CONFLICT (perfil_codigo, flujo) DO NOTHING;


-- =====================================================================
-- 3 · La sede lleva su flujo; `pos_mode` se deriva
-- =====================================================================
ALTER TABLE sites ADD COLUMN IF NOT EXISTS flujo_de_venta TEXT;

-- Relleno de lo que existe, leyendo la equivalencia del catálogo.
UPDATE sites s
   SET flujo_de_venta = f.codigo
  FROM flujos_de_venta f
 WHERE f.hereda_de_pos_mode = s.pos_mode
   AND s.flujo_de_venta IS NULL;

-- Lo de siempre para quien no diga nada: la sede que crea el trigger de V28
-- en la primera venta de un negocio de autoservicio nace con el flujo marcado
-- `es_defecto` en el catálogo (RASTREADOR, lo que PLAZOLETA significaba). El
-- DEFAULT de la columna se fija leyendo esa fila, no escribiendo el nombre.
DO $$
DECLARE v_defecto TEXT;
BEGIN
    SELECT codigo INTO v_defecto FROM flujos_de_venta WHERE es_defecto;
    IF v_defecto IS NULL THEN
        RAISE EXCEPTION 'V48: el catálogo no tiene flujo por defecto';
    END IF;
    EXECUTE format('ALTER TABLE sites ALTER COLUMN flujo_de_venta SET DEFAULT %L', v_defecto);
END $$;
ALTER TABLE sites ALTER COLUMN flujo_de_venta SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sites_flujo_de_venta') THEN
        ALTER TABLE sites ADD CONSTRAINT fk_sites_flujo_de_venta
            FOREIGN KEY (flujo_de_venta) REFERENCES flujos_de_venta (codigo);
    END IF;
END $$;

-- El trigger que mantiene las dos columnas diciendo lo mismo.
--
--   · Si viene el flujo (escritor nuevo), `pos_mode` = su valor legado.
--   · Si solo cambia `pos_mode` (escritor viejo), el flujo = el que hereda de
--     ese valor.
--
-- Es BEFORE porque decide el valor de la propia fila. Un `pos_mode` que no
-- coincida con el legado de su flujo no puede quedar escrito.
CREATE OR REPLACE FUNCTION fn_sites_flujo_y_pos_mode()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_legado TEXT;
    v_flujo  TEXT;
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.pos_mode IS DISTINCT FROM OLD.pos_mode
       AND NEW.flujo_de_venta IS NOT DISTINCT FROM OLD.flujo_de_venta THEN
        -- Solo movieron `pos_mode`: un escritor viejo. El flujo sale del catálogo.
        SELECT codigo INTO v_flujo FROM flujos_de_venta WHERE hereda_de_pos_mode = NEW.pos_mode;
        IF v_flujo IS NULL THEN
            RAISE EXCEPTION 'pos_mode % no equivale a ningún flujo de venta', NEW.pos_mode
                USING ERRCODE = 'P0001';
        END IF;
        NEW.flujo_de_venta := v_flujo;
    END IF;

    SELECT pos_mode_legado INTO v_legado FROM flujos_de_venta WHERE codigo = NEW.flujo_de_venta;
    IF v_legado IS NULL THEN
        RAISE EXCEPTION 'flujo de venta % no existe en el catálogo', NEW.flujo_de_venta
            USING ERRCODE = 'P0001';
    END IF;
    NEW.pos_mode := v_legado;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sites_flujo_y_pos_mode ON sites;
CREATE TRIGGER trg_sites_flujo_y_pos_mode
    BEFORE INSERT OR UPDATE OF pos_mode, flujo_de_venta ON sites
    FOR EACH ROW EXECUTE FUNCTION fn_sites_flujo_y_pos_mode();

COMMENT ON COLUMN sites.flujo_de_venta IS
    'Flujo de venta de la sede (catálogo flujos_de_venta). `pos_mode` se deriva de aquí.';
COMMENT ON COLUMN sites.pos_mode IS
    'LEGADO desde V48: derivado de flujo_de_venta por trigger. Lo leen los POS sin '
    'actualizar. Se contrae cuando ninguno lo lea.';


-- =====================================================================
-- 4 · Permisos y aislamiento
--
-- Dos catálogos globales, sin tenant_id, de SOLO LECTURA para la aplicación:
-- los cambia una migración. RLS activa y forzada con política de lectura
-- abierta, mismo patrón que `plans` (V27) pero sin escritura.
-- =====================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON TABLE flujos_de_venta        TO app_user;
        GRANT SELECT ON TABLE perfil_flujos_de_venta TO app_user;
    END IF;
END $$;

ALTER TABLE flujos_de_venta ENABLE ROW LEVEL SECURITY;
ALTER TABLE flujos_de_venta FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS lectura_flujos_de_venta ON flujos_de_venta;
CREATE POLICY lectura_flujos_de_venta ON flujos_de_venta FOR SELECT USING (true);

ALTER TABLE perfil_flujos_de_venta ENABLE ROW LEVEL SECURITY;
ALTER TABLE perfil_flujos_de_venta FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS lectura_perfil_flujos ON perfil_flujos_de_venta;
CREATE POLICY lectura_perfil_flujos ON perfil_flujos_de_venta FOR SELECT USING (true);


-- =====================================================================
-- 5 · La comprobación que hace fallar la migración
--
-- Comportamiento, no estado: se escribe una sede de prueba de las dos formas
-- (con flujo y solo con pos_mode), se pregunta qué quedó, se intenta un flujo
-- inexistente, y se borra el rastro. Corre con `app.tenant_id` fijado porque
-- `sites` está en FORCE RLS.
-- =====================================================================
DO $cierre$
DECLARE
    v_pos  TEXT;
    v_flu  TEXT;
    v_sin_flujo INT;
    v_fallo BOOLEAN := false;
BEGIN
    -- (a) Ninguna sede existente quedó sin flujo.
    SELECT count(*) INTO v_sin_flujo FROM sites WHERE flujo_de_venta IS NULL;
    IF v_sin_flujo > 0 THEN
        RAISE EXCEPTION 'V48: % sedes sin flujo tras el relleno', v_sin_flujo;
    END IF;

    PERFORM set_config('app.tenant_id', 'v48-comprobacion', true);
    INSERT INTO tenants (id, name, plan) VALUES ('v48-comprobacion', 'V48', 'pro')
        ON CONFLICT (id) DO NOTHING;

    -- (b) Escritor nuevo: viene el flujo, pos_mode se deriva.
    INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default)
    VALUES ('v48-comprobacion', 'P', 'P', 'DIRECTO', true);
    SELECT pos_mode, flujo_de_venta INTO v_pos, v_flu
      FROM sites WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    IF v_pos <> 'PLAZOLETA' OR v_flu <> 'DIRECTO' THEN
        RAISE EXCEPTION 'V48: DIRECTO debía reportar PLAZOLETA y quedó (%, %)', v_pos, v_flu;
    END IF;

    -- (c) Escritor viejo: solo mueve pos_mode, el flujo sale del catálogo.
    UPDATE sites SET pos_mode = 'RESTAURANTE' WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    SELECT pos_mode, flujo_de_venta INTO v_pos, v_flu
      FROM sites WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    IF v_pos <> 'RESTAURANTE' OR v_flu <> 'MESA' THEN
        RAISE EXCEPTION 'V48: pos_mode RESTAURANTE debía heredar MESA y quedó (%, %)', v_pos, v_flu;
    END IF;

    -- (d) Escritor nuevo cambia el flujo: pos_mode lo sigue.
    UPDATE sites SET flujo_de_venta = 'RASTREADOR' WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    SELECT pos_mode INTO v_pos FROM sites WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    IF v_pos <> 'PLAZOLETA' THEN
        RAISE EXCEPTION 'V48: RASTREADOR debía reportar PLAZOLETA y quedó %', v_pos;
    END IF;

    -- (e) Control negativo: un flujo que no está en el catálogo no entra.
    BEGIN
        UPDATE sites SET flujo_de_venta = 'TELEPATIA' WHERE tenant_id = 'v48-comprobacion' AND code = 'P';
    EXCEPTION WHEN foreign_key_violation OR raise_exception THEN
        v_fallo := true;
    END;
    IF NOT v_fallo THEN
        RAISE EXCEPTION 'V48: un flujo inexistente se aceptó';
    END IF;

    -- (f) Una sede insertada sin flujo nace con el defecto del catálogo.
    INSERT INTO sites (tenant_id, name, code, is_default)
    VALUES ('v48-comprobacion', 'S', 'S', false);
    SELECT flujo_de_venta INTO v_flu FROM sites WHERE tenant_id = 'v48-comprobacion' AND code = 'S';
    IF v_flu IS DISTINCT FROM (SELECT codigo FROM flujos_de_venta WHERE es_defecto) THEN
        RAISE EXCEPTION 'V48: una sede sin flujo declarado quedó en %', v_flu;
    END IF;

    -- (g) Cada perfil sembrado tiene exactamente un defecto.
    IF EXISTS (
        SELECT perfil_codigo FROM perfil_flujos_de_venta
         GROUP BY perfil_codigo HAVING count(*) FILTER (WHERE es_defecto) <> 1) THEN
        RAISE EXCEPTION 'V48: hay un perfil sin flujo por defecto único';
    END IF;

    DELETE FROM sites   WHERE tenant_id = 'v48-comprobacion';
    DELETE FROM tenants WHERE id = 'v48-comprobacion';
END
$cierre$;


-- =====================================================================
-- ROLLBACK (manual, no lo ejecuta Flyway)
--
-- DROP TRIGGER IF EXISTS trg_sites_flujo_y_pos_mode ON sites;
-- DROP FUNCTION IF EXISTS fn_sites_flujo_y_pos_mode();
-- ALTER TABLE sites DROP CONSTRAINT IF EXISTS fk_sites_flujo_de_venta;
-- ALTER TABLE sites DROP COLUMN IF EXISTS flujo_de_venta;
-- DROP TABLE IF EXISTS perfil_flujos_de_venta;
-- DROP TABLE IF EXISTS flujos_de_venta;
-- (`pos_mode` y `ck_sites_pos_mode` nunca se tocaron.)
-- =====================================================================
