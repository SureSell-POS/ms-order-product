-- =====================================================================
-- V65 -- La venta a crédito sabe cuándo vence, mide el cupo contra el libro
--        y no le vende a un cliente en insolvencia.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F4.3.
--
-- ── Lo que había ──────────────────────────────────────────────────────
--
-- `fn_venta_a_credito` (V45, disparador BEFORE INSERT de `orders` con
-- `payment_method = 'CREDITO'`). Cuerpo leído en staging con
-- `pg_get_functiondef` el 2026-09-13 (md5 a7bbd5bb47da1a8f390f0f25dfe7d55b):
-- ninguna migración posterior la redefinió. Tres cosas que F4 no puede aceptar:
--   · el débito nacía sin `vence_el` (V64): la cartera no sabía qué está vencido;
--   · `excede_cupo` se medía contra `total_debt`, un saldo guardado, y no contra
--     el libro;
--   · las fechas eran `CURRENT_DATE`, que en una sesión UTC ya es mañana desde
--     las 19:00 de Bogotá.
--
-- ── Lo que cambia ─────────────────────────────────────────────────────
--
-- 1. `vence_el` = día de Bogotá + `clientes.plazo_dias`, congelado al nacer. Sin
--    plazo pactado (NULL, o cliente sin ficha) queda NULL: no se inventa un plazo;
--    la vista lo pone en SIN_PLAZO_PACTADO. `transaction_date` y
--    `last_transaction_date` pasan también al día de Bogotá.
-- 2. `excede_cupo` = saldo del libro de la cuenta (DEBIT − CREDIT) + esta venta >
--    cupo. Sigue AVISANDO, no bloquea (D9: retener es F5). Se mide siempre salvo
--    con `plazo_dias = 0` explícito (se paga al entregar: no es crédito que ocupe
--    cupo). NULL no es 0: sin plazo pactado, el cupo se mide.
--    Se suma el libro por cuenta y no la vista por documento a propósito: un abono
--    registrado desde el panel viejo de core es un CREDIT sin aplicaciones, que
--    la vista por documento no ve y el libro sí.
-- 3. Cliente con `en_insolvencia_desde` ya cumplida → P0001 con texto. Nada se
--    escribe: la venta entera se revierte.
-- 4. Hueco para F5: la excepción de contraentrega entrará en el cálculo de
--    `v_mide_cupo`. No se implementa aquí.
--
-- `total_debt` se sigue moviendo igual (lo lee el panel viejo de core; V64
-- comprueba que cuadra con el libro).
--
-- 🔴 R16: el débito sigue siendo `type = 'DEBIT'` con `payment_method` NULL.
--
-- IMPACTO: redefine una función; sin DDL de tablas. Libro vacío en staging y
-- producción (medido al preparar V64).
-- =====================================================================

SET lock_timeout = '3s';

CREATE OR REPLACE FUNCTION public.fn_venta_a_credito()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_cuenta     public.accounts_receivable%ROWTYPE;
    v_hoy        DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_plazo      INTEGER;
    v_insolvente DATE;
    v_saldo      NUMERIC;
    v_mide_cupo  BOOLEAN;
    v_excede     BOOLEAN;
BEGIN
    IF NEW.cliente_documento IS NULL OR btrim(NEW.cliente_documento) = '' THEN
        RAISE EXCEPTION 'Una venta a credito necesita el documento del cliente.';
    END IF;

    SELECT c.plazo_dias, c.en_insolvencia_desde INTO v_plazo, v_insolvente
      FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id
       AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL AND v_insolvente <= v_hoy THEN
        RAISE EXCEPTION 'El cliente % esta en proceso de insolvencia desde el %: no se le vende a credito.',
            NEW.cliente_documento, to_char(v_insolvente, 'DD/MM/YYYY');
    END IF;

    SELECT * INTO v_cuenta FROM public.accounts_receivable
     WHERE customer_document = NEW.cliente_documento
       AND tenant_id = NEW.tenant_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El cliente % no tiene cuenta de cartera: abrela antes de venderle a credito.', NEW.cliente_documento;
    END IF;
    IF v_cuenta.status IN ('SUSPENDED', 'CLOSED') THEN
        RAISE EXCEPTION 'La cuenta de cartera del cliente % esta %: no admite mas credito.', NEW.cliente_documento,
            CASE v_cuenta.status WHEN 'SUSPENDED' THEN 'suspendida' ELSE 'cerrada' END;
    END IF;

    -- Plazo 0 explícito: se paga al entregar y no ocupa cupo. NULL (sin pactar) sí se mide.
    -- F5: la contraentrega entrará aquí.
    v_mide_cupo := v_plazo IS DISTINCT FROM 0;
    IF v_mide_cupo THEN
        -- El saldo, del libro (la cuenta está bloqueada: nadie escribe en medio).
        SELECT COALESCE(sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END), 0) INTO v_saldo
          FROM public.debt_transactions d
         WHERE d.tenant_id = NEW.tenant_id
           AND d.account_id = v_cuenta.id;
        -- AVISA, NO BLOQUEA: la venta entra y queda marcada.
        v_excede := v_saldo + COALESCE(NEW.total, 0) > v_cuenta.credit_limit;
    ELSE
        v_excede := false;
    END IF;

    INSERT INTO public.debt_transactions
        (id, tenant_id, account_id, amount, created_at, description, payment_method,
         reference, transaction_date, type, order_uuid, excede_cupo, registrado_por, vence_el)
    VALUES (gen_random_uuid()::text, NEW.tenant_id, v_cuenta.id, COALESCE(NEW.total, 0), now(),
            'Venta a credito', NULL, NEW.uuid_id::text, v_hoy, 'DEBIT',
            NEW.uuid_id, v_excede, 'sistema:venta', v_hoy + v_plazo);

    UPDATE public.accounts_receivable
       SET total_debt = v_cuenta.total_debt + COALESCE(NEW.total, 0),
           last_transaction_date = v_hoy,
           status = 'ACTIVE',
           updated_at = now()
     WHERE id = v_cuenta.id;

    NEW.excede_cupo := v_excede;
    RETURN NEW;
END $function$;

-- =====================================================================
-- La comprobación, por comportamiento. Corre con la sesión en una zona horaria
-- cuyo día NO es el de Bogotá (se elige al vuelo: +14 o −12, una de las dos
-- siempre difiere). Así, un `current_date` que se cuele en la función o en las
-- vistas de V64 da un día equivocado y la comprobación cae, a cualquier hora.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v65_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_zona TEXT;
    v_c30 TEXT := gen_random_uuid()::text;
    v_csp TEXT := gen_random_uuid()::text;
    v_c0  TEXT := gen_random_uuid()::text;
    v_cld TEXT := gen_random_uuid()::text;
    v_cin TEXT := gen_random_uuid()::text;
    v_orden UUID;
    v_excede BOOLEAN; v_vence DATE; v_fecha DATE; v_n BIGINT; v_rechazado BOOLEAN;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    v_zona := CASE WHEN (now() AT TIME ZONE 'Etc/GMT-14')::date <> v_hoy THEN 'Etc/GMT-14' ELSE 'Etc/GMT+12' END;
    PERFORM set_config('TimeZone', v_zona, true);
    IF current_date = v_hoy THEN
        RAISE EXCEPTION 'V65: no se logro una sesion con un dia distinto al de Bogota (%)', v_zona;
    END IF;

    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V65', 'basico');
    PERFORM set_config('app.tenant_id', a, true);
    INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por, en_insolvencia_desde) VALUES
        (a, '__c30__', 'Plazo 30',   30,   'v65', NULL),
        (a, '__c0__',  'Plazo 0',    0,    'v65', NULL),
        (a, '__cld__', 'Libro',      15,   'v65', NULL),
        (a, '__cin__', 'Insolvente', 30,   'v65', v_hoy);
    -- '__csp__' no tiene ficha: sin plazo pactado.
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                     status, total_debt, updated_at) VALUES
        (v_c30, a, now(), 100000, '__c30__', 'Plazo 30',   'ACTIVE', 0, now()),
        (v_csp, a, now(), 0,      '__csp__', 'Sin plazo',  'ACTIVE', 0, now()),
        (v_c0,  a, now(), 0,      '__c0__',  'Plazo 0',    'ACTIVE', 0, now()),
        (v_cld, a, now(), 100000, '__cld__', 'Libro',      'ACTIVE', 0, now()),
        (v_cin, a, now(), 100000, '__cin__', 'Insolvente', 'ACTIVE', 0, now());

    -- 1. Plazo 30, dentro del cupo: vence el día de Bogotá + 30, fechado en Bogotá, sin aviso.
    v_orden := gen_random_uuid();
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, a, 'pagado', 'CREDITO', 45000, 45000, true, false, '__c30__', now());
    SELECT o.excede_cupo, d.vence_el, d.transaction_date INTO v_excede, v_vence, v_fecha
      FROM orders o JOIN debt_transactions d ON d.order_uuid = o.uuid_id WHERE o.uuid_id = v_orden;
    IF v_excede IS DISTINCT FROM false OR v_vence IS DISTINCT FROM v_hoy + 30 OR v_fecha IS DISTINCT FROM v_hoy THEN
        RAISE EXCEPTION 'V65: plazo 30 dio excede=% vence=% fecha=% (Bogota hoy %, sesion %)',
            v_excede, v_vence, v_fecha, v_hoy, v_zona;
    END IF;

    -- 2. Sin plazo pactado y cupo 0: vence NULL, avisa, y la vista lo pone en su tramo.
    v_orden := gen_random_uuid();
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, a, 'pagado', 'CREDITO', 8000, 8000, true, false, '__csp__', now());
    SELECT o.excede_cupo, d.vence_el INTO v_excede, v_vence
      FROM orders o JOIN debt_transactions d ON d.order_uuid = o.uuid_id WHERE o.uuid_id = v_orden;
    IF v_excede IS DISTINCT FROM true OR v_vence IS NOT NULL
       OR (SELECT edad FROM v_cartera_por_documento WHERE order_uuid = v_orden) <> 'SIN_PLAZO_PACTADO' THEN
        RAISE EXCEPTION 'V65: sin plazo pactado dio excede=% vence=%', v_excede, v_vence;
    END IF;

    -- 3. Plazo 0 explícito con cupo 0: no mide cupo; vence hoy y HOY NO ESTÁ VENCIDA.
    v_orden := gen_random_uuid();
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, a, 'pagado', 'CREDITO', 9000, 9000, true, false, '__c0__', now());
    SELECT o.excede_cupo, d.vence_el INTO v_excede, v_vence
      FROM orders o JOIN debt_transactions d ON d.order_uuid = o.uuid_id WHERE o.uuid_id = v_orden;
    IF v_excede IS DISTINCT FROM false OR v_vence IS DISTINCT FROM v_hoy
       OR NOT EXISTS (SELECT 1 FROM v_cartera_por_documento
                       WHERE order_uuid = v_orden AND edad = 'CORRIENTE' AND dias_vencido = 0) THEN
        RAISE EXCEPTION 'V65: plazo 0 dio excede=% vence=% o la vista la vio vencida con la sesion en %', v_excede, v_vence, v_zona;
    END IF;
    -- …y la que venció AYER en Bogotá lleva 1 día (V64 cuenta con el día de Bogotá).
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES (gen_random_uuid()::text, a, v_c0, 1000, now(), 'Venta a credito', v_hoy - 1, 'DEBIT', v_hoy - 1);
    UPDATE accounts_receivable SET total_debt = total_debt + 1000 WHERE id = v_c0;
    IF NOT EXISTS (SELECT 1 FROM v_cartera_por_documento
                    WHERE account_id = v_c0 AND monto = 1000 AND edad = '1_30' AND dias_vencido = 1) THEN
        RAISE EXCEPTION 'V65: la factura vencida ayer en Bogota no lleva 1 dia con la sesion en %', v_zona;
    END IF;

    -- 4. El cupo se mide contra el libro, no contra total_debt: con total_debt en 0
    --    y 90.000 en el libro, una venta de 20.000 sobre cupo 100.000 avisa.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES (gen_random_uuid()::text, a, v_cld, 90000, now(), 'Venta a credito', v_hoy, 'DEBIT', v_hoy + 15);
    v_orden := gen_random_uuid();
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, a, 'pagado', 'CREDITO', 20000, 20000, true, false, '__cld__', now());
    IF (SELECT excede_cupo FROM orders WHERE uuid_id = v_orden) IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'V65: el cupo se midio contra total_debt y no contra el libro';
    END IF;
    -- …y un abono del panel viejo (CREDIT sin recibo) sí descuenta: 110.000 − 50.000 + 20.000 ≤ 100.000.
    INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, payment_method)
    VALUES (gen_random_uuid()::text, a, v_cld, 50000, now(), 'Abono', v_hoy, 'CREDIT', 'CASH');
    v_orden := gen_random_uuid();
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, a, 'pagado', 'CREDITO', 20000, 20000, true, false, '__cld__', now());
    IF (SELECT excede_cupo FROM orders WHERE uuid_id = v_orden) IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'V65: el abono del libro no desconto del cupo';
    END IF;

    -- 5. Insolvencia: P0001 con texto, y nada escrito.
    v_rechazado := false;
    BEGIN
        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
        VALUES (gen_random_uuid(), a, 'pagado', 'CREDITO', 5000, 5000, true, false, '__cin__', now());
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM NOT LIKE '%insolvencia%' THEN RAISE; END IF;
        v_rechazado := true;
    END;
    SELECT count(*) INTO v_n FROM debt_transactions WHERE account_id = v_cin;
    IF NOT v_rechazado OR v_n <> 0 OR EXISTS (SELECT 1 FROM orders WHERE tenant_id = a AND cliente_documento = '__cin__') THEN
        RAISE EXCEPTION 'V65: se le vendio a credito a un cliente en insolvencia (rechazado=% debitos=%)', v_rechazado, v_n;
    END IF;

    -- Limpieza y barrido.
    PERFORM set_config('app.tenant_id', '', true);
    DELETE FROM debt_transactions     WHERE tenant_id = a;
    DELETE FROM order_item            WHERE tenant_id = a;
    DELETE FROM orders                WHERE tenant_id = a;
    DELETE FROM tenant_order_counters WHERE tenant_id = a;
    DELETE FROM accounts_receivable   WHERE tenant_id = a;
    DELETE FROM clientes_eventos      WHERE tenant_id = a;
    DELETE FROM clientes              WHERE tenant_id = a;
    DELETE FROM sites                 WHERE tenant_id = a;
    DELETE FROM tenants               WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario', 'pedidos', 'red')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id = $1', t.s, t.tn) INTO n USING a;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V65: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V65: con la sesion en % (dia distinto a Bogota): vence_el = dia de Bogota + plazo, NULL sin plazo, plazo 0 no mide cupo, cupo contra el libro, insolvencia rechazada y edades con el dia de Bogota.', v_zona;
END
$cierre$;

-- =====================================================================
-- DOWN: volver al cuerpo de V45 (md5 a7bbd5bb47da1a8f390f0f25dfe7d55b en staging).
-- =====================================================================
