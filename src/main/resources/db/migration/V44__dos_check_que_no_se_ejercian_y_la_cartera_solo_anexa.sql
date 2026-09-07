-- =====================================================================
-- V44 -- Dos CHECK de `public` que no se ejercian, y el libro de cartera pasa
--        a ser solo-anexar de verdad.
--
-- 1 · LOS DOS CHECK (docs/auditoria/CHECKS-CON-NULL.md, 2026-09-04)
--
-- Un CHECK que evalua a NULL DEJA PASAR la fila. Se sondearon las 31
-- restricciones de `public` evaluando cada expresion con sus columnas
-- anulables en NULL; dos dieron NULL en alguna combinacion:
--
--   daily_closures.ck_daily_closures_qr_coherencia   (V34)
--       `(qr_fuente = 'pos' AND qr_confianza = 0)` con qr_confianza NULL es
--       NULL: un cierre con fuente y SIN confianza entra. Es la coherencia
--       fuente/confianza, la misma que protege el libro de inventario.
--
--   orders.ck_orders_cadena_coherente                (V41)
--       `(cadena_origen = 'servidor' AND hash_propio IS NOT NULL)` con
--       cadena_origen NULL es NULL: una orden con hash y sin origen entra.
--       Un hash del que nadie sabe que garantiza es justo lo que V41 venia
--       a evitar.
--
-- Se reescriben con COALESCE. Antes de sustituirlas se cuenta cuantas filas
-- existentes violarian la version nueva: en produccion, medido en solo
-- lectura el 2026-09-04, son 0 de 108 cierres y 0 de 5.373 ordenes. Si en
-- otra base hubiera alguna, la migracion se para y lo dice, en vez de dejar
-- una restriccion NOT VALID que nadie revisa.
--
-- 2 · LA CARTERA (accounts_receivable / debt_transactions, V28)
--
-- Se midio en staging con un cliente de prueba antes de escribir nada
-- (2026-09-05): crear cuenta, deuda de 30.000, abono de 20.000. La cuenta
-- dice deuda 10.000; el libro `debt_transactions` tiene UNA fila, el abono.
-- La deuda desaparecio: `AccountReceivableRepositoryImpl.updateTransactions`
-- hace DELETE de todos los movimientos de la cuenta y reescribe los del
-- objeto en memoria, que no carga los anteriores. Cada operacion borra la
-- historia de la anterior. V33 dio `DELETE` a `app_user` sobre esta tabla
-- en bloque, y por eso pudo.
--
-- Aqui se quita: `debt_transactions` queda con SELECT e INSERT para la
-- aplicacion, como `movimientos_inventario`. El codigo que borraba se
-- corrige en ms-core-app en la misma rama; si alguien lo reintroduce, la
-- base se niega. `total_debt` sigue siendo un agregado almacenado (deuda de
-- V28, regla 7); la comprobacion de cierre verifica que hoy coincide con la
-- suma del libro para toda cuenta que tenga movimientos, y deja constancia
-- si no.
--
-- Y tres columnas para que una deuda pueda venir de una VENTA (V45):
--   order_uuid    de que orden salio (referencia blanda a orders.uuid_id)
--   excede_cupo   si al registrarla la cuenta quedo por encima del cupo. La
--                 venta a credito AVISA, no bloquea (ver V45)
--   registrado_por quien la origino (usuario o 'sistema:venta')
--
-- IMPACTO: 0 filas tocadas. Dos CHECK sustituidos, un REVOKE, tres columnas
-- nuevas anulables. ROLLBACK al final.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1 · Los dos CHECK
-- ---------------------------------------------------------------------
DO $revision$
DECLARE
    v_cierres INT;
    v_ordenes INT;
BEGIN
    SELECT count(*) INTO v_cierres FROM daily_closures
     WHERE NOT (
        qr_fuente IS NULL
        OR (qr_fuente = 'conciliado_core' AND COALESCE(qr_confianza, -1) >= 1)
        OR (qr_fuente IN ('pos', 'manual_cajero', 'sin_registro_externo', 'fallo_integracion')
            AND COALESCE(qr_confianza, -1) = 0));
    IF v_cierres > 0 THEN
        RAISE EXCEPTION 'V44: % cierres tienen fuente sin confianza coherente; corrigelos antes de endurecer el CHECK', v_cierres;
    END IF;

    SELECT count(*) INTO v_ordenes FROM orders
     WHERE NOT (
        (cadena_origen IS NULL AND hash_propio IS NULL)
        OR (COALESCE(cadena_origen, '') = 'cliente'  AND hash_propio IS NULL)
        OR (COALESCE(cadena_origen, '') = 'servidor' AND hash_propio IS NOT NULL));
    IF v_ordenes > 0 THEN
        RAISE EXCEPTION 'V44: % ordenes tienen hash sin origen de cadena coherente; corrigelas antes de endurecer el CHECK', v_ordenes;
    END IF;
END
$revision$;

ALTER TABLE daily_closures DROP CONSTRAINT IF EXISTS ck_daily_closures_qr_coherencia;
ALTER TABLE daily_closures ADD CONSTRAINT ck_daily_closures_qr_coherencia
    CHECK (
        qr_fuente IS NULL
        -- COALESCE a -1: con la confianza en NULL la rama da FALSE, no NULL.
        OR (qr_fuente = 'conciliado_core' AND COALESCE(qr_confianza, -1) >= 1)
        OR (qr_fuente IN ('pos', 'manual_cajero', 'sin_registro_externo', 'fallo_integracion')
            AND COALESCE(qr_confianza, -1) = 0)
    );

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_cadena_coherente;
ALTER TABLE orders ADD CONSTRAINT ck_orders_cadena_coherente
    CHECK ((cadena_origen IS NULL AND hash_propio IS NULL)
        -- COALESCE a '': con el origen en NULL las dos ramas dan FALSE.
        OR (COALESCE(cadena_origen, '') = 'cliente'  AND hash_propio IS NULL)
        OR (COALESCE(cadena_origen, '') = 'servidor' AND hash_propio IS NOT NULL));

-- ---------------------------------------------------------------------
-- 2 · El libro de cartera: solo-anexar por privilegio, y de donde salio
-- ---------------------------------------------------------------------
ALTER TABLE debt_transactions
    ADD COLUMN IF NOT EXISTS order_uuid     UUID    NULL,
    ADD COLUMN IF NOT EXISTS excede_cupo    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS registrado_por TEXT    NULL;

COMMENT ON COLUMN debt_transactions.order_uuid IS
    'La venta a credito de la que salio este debito (orders.uuid_id, referencia blanda). NULL si se registro a mano.';
COMMENT ON COLUMN debt_transactions.excede_cupo IS
    'TRUE si al registrar este debito la cuenta quedo por encima de su cupo. La venta avisa, no bloquea.';

CREATE INDEX IF NOT EXISTS ix_debt_transactions_orden
    ON debt_transactions (tenant_id, order_uuid) WHERE order_uuid IS NOT NULL;

DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        -- Un abono equivocado se corrige con otro movimiento, nunca borrando.
        REVOKE DELETE, UPDATE ON public.debt_transactions FROM app_user;
    END IF;
END
$permisos$;

-- ---------------------------------------------------------------------
-- 3 · La comprobacion: comportamiento, no estado
-- ---------------------------------------------------------------------
DO $cierre$
DECLARE
    v_paso BOOLEAN;
    v_puede_borrar BOOLEAN;
    v_descuadradas INT;
BEGIN
    -- (a) Las dos expresiones ya no dan NULL con sus columnas en NULL.
    SELECT (( 'pos' IS NULL
        OR ('pos' = 'conciliado_core' AND COALESCE(NULL::smallint, -1) >= 1)
        OR ('pos' IN ('pos','manual_cajero','sin_registro_externo','fallo_integracion')
            AND COALESCE(NULL::smallint, -1) = 0))) IS NULL INTO v_paso;
    IF v_paso THEN
        RAISE EXCEPTION 'V44: ck_daily_closures_qr_coherencia sigue evaluando a NULL';
    END IF;
    SELECT (((NULL::text IS NULL AND 'abc' IS NULL)
        OR (COALESCE(NULL::text, '') = 'cliente'  AND 'abc' IS NULL)
        OR (COALESCE(NULL::text, '') = 'servidor' AND 'abc' IS NOT NULL))) IS NULL INTO v_paso;
    IF v_paso THEN
        RAISE EXCEPTION 'V44: ck_orders_cadena_coherente sigue evaluando a NULL';
    END IF;

    -- (b) app_user ya no puede borrar del libro.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        SELECT has_table_privilege('app_user', 'public.debt_transactions', 'DELETE')
            OR has_table_privilege('app_user', 'public.debt_transactions', 'UPDATE')
          INTO v_puede_borrar;
        IF v_puede_borrar THEN
            RAISE EXCEPTION 'V44: app_user conserva DELETE o UPDATE sobre debt_transactions';
        END IF;
    END IF;

    -- (c) Constancia: cuentas cuyo agregado no coincide con su libro. No se
    --     corrige aqui (seria inventar movimientos); se avisa con cifras.
    SELECT count(*) INTO v_descuadradas
      FROM accounts_receivable a
     WHERE a.total_debt <> COALESCE((SELECT sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END)
                                       FROM debt_transactions d WHERE d.account_id = a.id), 0);
    RAISE NOTICE 'V44: dos CHECK endurecidos; debt_transactions solo-anexar; % cuenta(s) con total_debt distinto de su libro (historia borrada antes de V44).', v_descuadradas;
END
$cierre$;

-- =====================================================================
-- DOWN -- rollback explicito
-- =====================================================================
--
-- ALTER TABLE daily_closures DROP CONSTRAINT IF EXISTS ck_daily_closures_qr_coherencia;
-- ALTER TABLE daily_closures ADD CONSTRAINT ck_daily_closures_qr_coherencia CHECK (... version de V34 ...);
-- ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_cadena_coherente;
-- ALTER TABLE orders ADD CONSTRAINT ck_orders_cadena_coherente CHECK (... version de V41 ...);
-- GRANT UPDATE, DELETE ON public.debt_transactions TO app_user;   -- reabre el borrado del libro
-- DROP INDEX IF EXISTS ix_debt_transactions_orden;
-- ALTER TABLE debt_transactions DROP COLUMN IF EXISTS order_uuid, DROP COLUMN IF EXISTS excede_cupo, DROP COLUMN IF EXISTS registrado_por;
