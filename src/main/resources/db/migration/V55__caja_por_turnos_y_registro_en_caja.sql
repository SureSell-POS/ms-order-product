-- =====================================================================
-- V55 -- Caja por turnos y registro de productos desde la caja.
--
-- POR QUE (CAJA-POR-TURNOS-Y-REGISTRO-EN-CAJA.md, decisiones de Santiago del
-- 2026-09-11 tras la revision de ferreteria y farmacia)
--
-- 1. UN CIERRE = UN TURNO, VARIOS POR DIA.
--
--    `uq_daily_closures_tenant_date` (V2) admitia UN cierre por negocio y dia
--    natural. Eso era la carta de un solo cliente metida en el esquema: un
--    restaurante que cierra una vez por la noche. Una farmacia con dos
--    turnos, o un minimercado que cambia de cajero al mediodia, se comia el
--    mensaje "la caja de hoy ya esta cerrada" y el segundo turno cuadraba
--    contra nada. Ya lo habia mostrado el cierre en ceros del 2026-08-31:
--    una fila basura a las 09:30 quemaba el unico cupo del dia.
--
--    Ahora la fila lleva `turno` (1, 2, 3... dentro del dia; lo calcula el
--    servidor) y la unicidad es (negocio, fecha, turno). Las filas de cierre
--    existentes quedan en turno 1, que es lo que siempre fueron. La ventana
--    del turno sigue siendo "desde el cierre anterior", tambien si fue hoy.
--
-- 2. LA BASE DE CAJA ES UN DATO DEL NEGOCIO, NO UN CALCULO.
--
--    `calculateBaseForNextDay` decidia la base por denominaciones (dos de
--    50k, diez de 20k...) con numeros escritos en el codigo. Ningun negocio
--    deja la base asi: deja "200.000". `sites.base_caja` guarda cuanto deja el
--    negocio para la siguiente apertura; el cajero la ve precargada al cerrar
--    y puede cambiarla para ese turno. Vive en la sede por defecto
--    (`is_default`) hasta que el cierre sea por sede.
--
-- 3. REGISTRAR DESDE LA CAJA, CON PIN Y CON TOPE.
--
--    Escanear un codigo que el catalogo no conoce hoy solo permite "vender
--    sin registrar" (V51 §4.3). Ahora la caja puede dar de alta el producto
--    con nombre, categoria y precio, si el administrador configuro un PIN de
--    registro (`pin_registro_caja_hash`, BCrypt, nunca en claro) y sin pasar
--    de `max_registros_caja_por_dia` (30 por defecto). Sin PIN configurado,
--    la caja no registra y lo dice.
--
--    `menu_products.creado_en_caja_por/en` dejan escrito que el producto
--    nacio en la caja (regla 4: quien; regla 6: de donde). El panel lo pinta
--    como "registrado en la caja, falta enlazar" al inventario. Esa tabla la
--    comparten el core y -mt: columnas nuevas, anulables, sin default que
--    cambie filas; el core no las mapea y no las necesita.
--
-- LO QUE HACE
--
--   · `sites`: base_caja, pin_registro_caja_hash, max_registros_caja_por_dia.
--   · `daily_closures`: `turno` (DEFAULT 1 para lo existente); fuera
--     `uq_daily_closures_tenant_date`, entra
--     `uq_daily_closures_tenant_date_turno` (tenant_id, closure_date, turno).
--   · `menu_products`: creado_en_caja_por, creado_en_caja_en.
--
-- Sin tablas nuevas, sin politicas RLS nuevas (las tres tablas ya las
-- tienen). Ninguna politica ni trigger depende del constraint que se quita
-- (grep `uq_daily_closures` en migraciones y codigo: V2 lo crea y
-- DailyClosureController lo leia para responder 409; ese codigo se retira).
-- `idx_daily_closures_qr_fallo` (V34) tambien es (tenant_id, closure_date),
-- pero es un indice parcial NO unico: no limita cuantos cierres hay por dia.
-- ms-core-app mapea `daily_closures` solo para leer (historial y Excel) y no
-- inserta cierres, asi que el DEFAULT 1 de `turno` no le cambia nada.
--
-- IMPACTO EN PRODUCCION: 0 filas cambian de valor (el DEFAULT 1 de `turno`
-- rellena las filas existentes con lo que siempre fueron). Idempotente.
--
-- ROLLBACK (a mano, si hiciera falta):
--   ALTER TABLE daily_closures DROP CONSTRAINT IF EXISTS uq_daily_closures_tenant_date_turno;
--   ALTER TABLE daily_closures ADD CONSTRAINT uq_daily_closures_tenant_date UNIQUE (tenant_id, closure_date);
--     -- (falla si ya hay dos turnos el mismo dia: entonces no hay vuelta atras)
--   ALTER TABLE daily_closures DROP COLUMN IF EXISTS turno;
--   ALTER TABLE sites DROP COLUMN IF EXISTS base_caja, DROP COLUMN IF EXISTS pin_registro_caja_hash,
--                     DROP COLUMN IF EXISTS max_registros_caja_por_dia;
--   ALTER TABLE menu_products DROP COLUMN IF EXISTS creado_en_caja_por, DROP COLUMN IF EXISTS creado_en_caja_en;
--   DELETE FROM flyway_schema_history WHERE version = '55';
-- =====================================================================

SET lock_timeout = '3s';

ALTER TABLE sites
  ADD COLUMN IF NOT EXISTS base_caja                  NUMERIC(15,2),
  ADD COLUMN IF NOT EXISTS pin_registro_caja_hash     TEXT,
  ADD COLUMN IF NOT EXISTS max_registros_caja_por_dia SMALLINT NOT NULL DEFAULT 30;

COMMENT ON COLUMN sites.base_caja IS
    'V55: cuanto deja el negocio en caja para la siguiente apertura. NULL = sin configurar (se usa 0).';
COMMENT ON COLUMN sites.pin_registro_caja_hash IS
    'V55: hash BCrypt del PIN que autoriza registrar productos desde la caja. NULL = la caja no registra.';
COMMENT ON COLUMN sites.max_registros_caja_por_dia IS
    'V55: tope de productos registrados desde la caja por dia natural (hora de Bogota).';

ALTER TABLE daily_closures
  ADD COLUMN IF NOT EXISTS turno SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE daily_closures DROP CONSTRAINT IF EXISTS uq_daily_closures_tenant_date;
-- Postgres no tiene ADD CONSTRAINT IF NOT EXISTS: se comprueba a mano para
-- que la migracion pueda correr dos veces sin romperse.
DO $turno$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'uq_daily_closures_tenant_date_turno'
           AND conrelid = 'public.daily_closures'::regclass
    ) THEN
        ALTER TABLE daily_closures ADD CONSTRAINT uq_daily_closures_tenant_date_turno
            UNIQUE (tenant_id, closure_date, turno);
    END IF;
END
$turno$;

COMMENT ON COLUMN daily_closures.turno IS
    'V55: numero del turno dentro del dia (1, 2, 3...). Lo calcula el servidor: cierres del dia + 1.';

ALTER TABLE menu_products
  ADD COLUMN IF NOT EXISTS creado_en_caja_por TEXT,
  ADD COLUMN IF NOT EXISTS creado_en_caja_en  TIMESTAMPTZ;

COMMENT ON COLUMN menu_products.creado_en_caja_por IS
    'V55: usuario del JWT que registro el producto desde la caja. NULL = nacio en el panel.';
COMMENT ON COLUMN menu_products.creado_en_caja_en IS
    'V55: cuando se registro desde la caja. Cuenta para el tope diario y marca "falta enlazar" en el panel.';

-- Regla dura desde V22 (la leccion de V20): la migracion corre como owner y
-- pasa verde aunque falten los GRANT; la aplicacion conecta como `app_user`.
-- Las columnas nuevas heredan los permisos de la tabla; se repiten los que
-- ya tenian (V2, V23, V34) para no depender de eso. No amplian nada.
DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.sites          TO app_user;
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.daily_closures TO app_user;
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.menu_products  TO app_user;
    END IF;
END
$permisos$;

-- =====================================================================
-- Comprobacion: la unicidad nueva esta y la vieja no. Si no, la migracion
-- falla aqui y no en el primer segundo turno de un negocio.
-- =====================================================================
DO $verificar$
DECLARE
    vieja INT;
    nueva INT;
BEGIN
    SELECT count(*) INTO vieja FROM pg_constraint
     WHERE conname = 'uq_daily_closures_tenant_date' AND conrelid = 'public.daily_closures'::regclass;
    SELECT count(*) INTO nueva FROM pg_constraint
     WHERE conname = 'uq_daily_closures_tenant_date_turno' AND conrelid = 'public.daily_closures'::regclass;
    IF vieja <> 0 OR nueva <> 1 THEN
        RAISE EXCEPTION 'V55: la unicidad del cierre no quedo por turno (vieja=%, nueva=%)', vieja, nueva;
    END IF;
END
$verificar$;
