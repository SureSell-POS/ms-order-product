-- =====================================================================
-- V51 -- Los codigos de un producto: lo que el lector escribe y el POS busca.
--
-- POR QUE (VENTA-RAPIDA-2026-09 §3.1, medido el 2026-09-09)
--
-- El POS vende de `menu_products` (id, nombre, precio, activo, categoria).
-- No hay columna de codigo de barras en ninguna tabla que el POS lea; el
-- inventario tiene `insumos.codigo_interno` (el codigo de COMPRA: SKU,
-- referencia del proveedor, EAN de la factura) pero sus GET no lo devuelven
-- y el POS no habla con el inventario salvo para el perfil. Hoy un lector
-- conectado a este POS escribe trece digitos en una caja que no escucha.
--
-- EL CODIGO PERTENECE AL PRODUCTO, NO AL INSUMO
--
-- El codigo identifica la UNIDAD QUE SE VENDE: el EAN de la caja de 12 y el
-- de la unidad son distintos y venden cosas distintas; un insumo granel
-- (arroz) no tiene EAN y sus productos (libra, kilo) llevan un codigo corto
-- (PLU) o una etiqueta de bascula. Por eso vive en `public`, junto al
-- producto, y no en `inventario`.
--
-- UN PRODUCTO TIENE VARIOS CODIGOS
--
-- El EAN de la unidad, el de la caja (con `cantidad` 12), el PLU corto que
-- el tendero se sabe de memoria, el EAN viejo tras un cambio de empaque.
-- Una sola columna se queda corta el segundo dia: es una tabla.
--
-- LO QUE HACE
--
--   · Crea `public.codigos_de_producto`. Aditiva: 0 filas tocadas en
--     ninguna tabla existente; un POS o un panel viejos no la ven.
--   · `tipo` es un enum cerrado (regla 9 de los lineamientos): EAN, PLU,
--     BASCULA, PROVEEDOR. Sin "otro".
--   · Un codigo es unico DENTRO del negocio mientras este vigente. Dos
--     negocios pueden tener el mismo EAN (es el mismo producto del mismo
--     fabricante en dos tiendas). Retirar un codigo no lo borra: `retirado_en`
--     lo saca del indice unico parcial y deja la historia (regla 1: la fila
--     no se edita, se cierra), y el mismo codigo puede pasar a otro producto
--     (el empaque cambio) sin perder que antes fue de aquel.
--   · Quien lo creo y cuando (regla 4), y de donde salio (regla 6).
--   · RLS con el patron de V1/V35/V42: ENABLE + FORCE + politica por
--     app.tenant_id + GRANT explicito. Indices por negocio.
--
-- IMPACTO EN PRODUCCION: ninguno sobre datos. Se crea una tabla vacia.
-- ROLLBACK al final.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.codigos_de_producto (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       TEXT        NOT NULL
                    DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),

    -- Tal como lo escribe el lector, sin espacios alrededor. Se compara
    -- exacto: un EAN no se normaliza, un PLU tampoco.
    codigo          TEXT        NOT NULL,
    producto_id     TEXT        NOT NULL,

    -- Cuantas unidades del producto representa este codigo. 1 para el EAN de
    -- la unidad; 12 para el de la caja. Es lo que la caja anade al leerlo.
    cantidad        NUMERIC(12, 3) NOT NULL DEFAULT 1,

    -- Enum cerrado. EAN del fabricante; PLU corto del comercio; BASCULA
    -- (etiqueta de bascula con PLU y peso/precio embebidos, v2); PROVEEDOR
    -- (la referencia de la factura, cuando se vende por ella).
    tipo            TEXT        NOT NULL DEFAULT 'EAN',

    -- Regla 6: de donde salio el dato. `panel` lo tecleo o escaneo alguien
    -- en el catalogo; `pos` se registro desde la caja; `siembra` lo puso una
    -- carga (QA o masiva).
    fuente          TEXT        NOT NULL DEFAULT 'panel',
    creado_por      TEXT        NULL,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Se cierra, no se borra. NULL = vigente.
    retirado_en     TIMESTAMPTZ NULL,
    retirado_por    TEXT        NULL,

    CONSTRAINT pk_codigos_de_producto PRIMARY KEY (id),

    CONSTRAINT ck_codigos_codigo_no_vacio CHECK (length(btrim(codigo)) > 0),
    CONSTRAINT ck_codigos_sin_espacios CHECK (codigo = btrim(codigo)),
    CONSTRAINT ck_codigos_cantidad_positiva CHECK (cantidad > 0),
    CONSTRAINT ck_codigos_tipo CHECK (tipo IN ('EAN', 'PLU', 'BASCULA', 'PROVEEDOR')),
    CONSTRAINT ck_codigos_fuente CHECK (fuente IN ('panel', 'pos', 'siembra')),
    CONSTRAINT ck_codigos_retiro_coherente CHECK (
        (retirado_en IS NULL) OR (retirado_en >= creado_en))
);

COMMENT ON TABLE public.codigos_de_producto IS
    'Los codigos (EAN, PLU, bascula, proveedor) con los que la caja encuentra '
    'un producto. Varios por producto; unico por negocio mientras este vigente; '
    'se retira, no se borra.';

-- Unico por negocio SOLO entre los vigentes: un codigo retirado puede volver a
-- asignarse (el empaque cambio) y la historia se conserva. Mismo recurso que
-- `ux_terminals_tenant_codigo` (V35).
CREATE UNIQUE INDEX IF NOT EXISTS ux_codigos_tenant_codigo_vigente
    ON public.codigos_de_producto (tenant_id, codigo)
    WHERE retirado_en IS NULL;

-- Por negocio (patron de la casa) y por producto: "los codigos de este
-- producto" es la consulta del panel; "todos los vigentes del negocio" es la
-- del catalogo del POS y la resuelve el unico de arriba.
CREATE INDEX IF NOT EXISTS ix_codigos_tenant
    ON public.codigos_de_producto (tenant_id);
CREATE INDEX IF NOT EXISTS ix_codigos_tenant_producto
    ON public.codigos_de_producto (tenant_id, producto_id)
    WHERE retirado_en IS NULL;


-- =====================================================================
-- Aislamiento (V1:48-79, V35, V42: el mismo patron, no uno nuevo)
-- =====================================================================
ALTER TABLE public.codigos_de_producto ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.codigos_de_producto FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_codigos_de_producto ON public.codigos_de_producto;
CREATE POLICY tenant_isolation_codigos_de_producto ON public.codigos_de_producto
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

-- LECCION DE V20/V22: la migracion corre como owner y pasa verde aunque falten
-- los GRANT; la aplicacion conecta como `app_user`. UPDATE si: retirar es
-- cerrar la fila. DELETE no: un codigo no se borra.
DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT, UPDATE ON public.codigos_de_producto TO app_user;
    END IF;
END
$permisos$;


-- =====================================================================
-- La comprobacion que hace fallar la migracion si el aislamiento no quedo
-- (la de V35: RLS activo, forzado, con politica, y sin ninguna abierta).
-- =====================================================================
DO $verificar$
DECLARE
    con_rls   BOOLEAN;
    forzado   BOOLEAN;
    politicas INT;
    abiertas  INT;
BEGIN
    SELECT relrowsecurity, relforcerowsecurity INTO con_rls, forzado
      FROM pg_class WHERE oid = 'public.codigos_de_producto'::regclass;
    SELECT count(*) INTO politicas FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'codigos_de_producto';
    SELECT count(*) INTO abiertas FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'codigos_de_producto'
       AND (qual = 'true' OR qual IS NULL);
    IF NOT con_rls OR NOT forzado OR politicas <> 1 OR abiertas <> 0 THEN
        RAISE EXCEPTION 'V51: codigos_de_producto sin aislamiento real (rls=%, force=%, politicas=%, abiertas=%)',
            con_rls, forzado, politicas, abiertas;
    END IF;
END
$verificar$;

-- =====================================================================
-- ROLLBACK (a mano, si hiciera falta; nada de esto toca otra tabla)
--
--   DROP TABLE IF EXISTS public.codigos_de_producto;
--   DELETE FROM public.flyway_schema_history WHERE version = '51';
-- =====================================================================
