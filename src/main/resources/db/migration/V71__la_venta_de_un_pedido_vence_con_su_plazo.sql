-- =====================================================================
-- V71 -- La venta que nace de un pedido vence con el plazo que se pactó en
--        el pedido, no con el que tenga el cliente el día del despacho.
--
-- Plan de mayoristas F5.5 (diseño aprobado por ECM, 2026-09-15, decisión 7a).
--
-- El pedido congela `plazo_dias` al tomarse (V2 de la cadena `pedidos`): es lo
-- que se le prometió al tendero. `fn_venta_a_credito` (V65) lee el plazo del
-- CLIENTE al insertar la venta; si el cliente pasó de 8 a 30 días entre la toma
-- y el despacho, la venta del pedido vencería a 30. Ahora:
--
--   1. `orders.plazo_dias`: el plazo de ESA venta, solo para `origen = 'pedido'`
--      (CHECK). Toda otra venta lo deja NULL y sigue leyendo el del cliente.
--   2. `fn_venta_a_credito`: el MISMO cuerpo de V65 más una rama, `origen =
--      'pedido'` → `v_plazo := NEW.plazo_dias`. La insolvencia, la cuenta, el
--      cupo y el débito no cambian.
--
-- 🔴 Es el disparador de TODA venta a crédito del POS. Por eso:
--   · Guarda: el cuerpo vigente tiene que ser el de V65 (md5 de prosrc
--     9f3148f044b7ef025faff681aa1ef14e, medido en staging). Si no, se para.
--   · El cierre comprueba la venta a crédito de la caja, además de las del pedido.
--   · LaVentaDeSiempreTest (prueba de oro) cubre la venta a crédito del POS.
--
-- INVOKER como en V65 (no es SECURITY DEFINER): corre con los permisos de quien
-- inserta la venta, y RLS sigue siendo el suelo.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       IS DISTINCT FROM '9f3148f044b7ef025faff681aa1ef14e' THEN
        RAISE EXCEPTION 'V71: fn_venta_a_credito no es la de V65 (md5 %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'));
    END IF;
END $guarda$;

-- ── 1 · El plazo de la venta del pedido ───────────────────────────────
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS plazo_dias SMALLINT NULL;
ALTER TABLE public.orders DROP CONSTRAINT IF EXISTS ck_orders_plazo_del_pedido;
ALTER TABLE public.orders ADD CONSTRAINT ck_orders_plazo_del_pedido
    CHECK (plazo_dias IS NULL OR (origen = 'pedido' AND plazo_dias BETWEEN 0 AND 365));
COMMENT ON COLUMN public.orders.plazo_dias IS
    'Plazo pactado de la venta que nace de un pedido (congelado en la captura). NULL en toda otra venta, que usa el del cliente. V71.';

-- ── 2 · El disparador, con la rama del pedido ─────────────────────────
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

    -- V71 (F5.5): la venta que nace de despachar un pedido vence con el plazo PACTADO en
    -- el pedido, congelado en la captura; el del cliente pudo cambiar después (decisión
    -- de ECM). NULL = sin plazo pactado, 0 = contraentrega. Toda otra venta, como siempre.
    IF NEW.origen = 'pedido' THEN
        v_plazo := NEW.plazo_dias;
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

-- ── 3 · Cierre, por comportamiento ────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v71_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_caja UUID := gen_random_uuid();
    v_p30 UUID := gen_random_uuid();
    v_p0 UUID := gen_random_uuid();
    v_psin UUID := gen_random_uuid();
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V71', 'basico');
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v71-tienda', 'Tienda', 8, 'v71');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
    VALUES ('v71-cuenta', a, now(), 1000000, 'v71-tienda', 'Tienda', 'ACTIVE', 0, now());

    -- La caja: como siempre, el plazo del cliente (8).
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
    VALUES (v_caja, a, 'pagado', 'CREDITO', 'v71-tienda', 1000, 'caja');
    -- Pedidos: su plazo pactado, aunque el cliente tenga otro.
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias)
    VALUES (v_p30, a, 'pagado', 'CREDITO', 'v71-tienda', 2000, 'pedido', 30),
           (v_p0,  a, 'pagado', 'CREDITO', 'v71-tienda', 3000, 'pedido', 0);
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias)
    VALUES (v_psin, a, 'pagado', 'CREDITO', 'v71-tienda', 4000, 'pedido', NULL);

    IF (SELECT vence_el FROM public.debt_transactions WHERE order_uuid = v_caja) IS DISTINCT FROM v_hoy + 8
       OR (SELECT vence_el FROM public.debt_transactions WHERE order_uuid = v_p30) IS DISTINCT FROM v_hoy + 30
       OR (SELECT vence_el FROM public.debt_transactions WHERE order_uuid = v_p0) IS DISTINCT FROM v_hoy
       OR (SELECT vence_el FROM public.debt_transactions WHERE order_uuid = v_psin) IS NOT NULL
       OR (SELECT excede_cupo FROM public.orders WHERE uuid_id = v_p0) IS DISTINCT FROM false
       OR (SELECT total_debt FROM public.accounts_receivable WHERE id = 'v71-cuenta') <> 10000 THEN
        RAISE EXCEPTION 'V71: los vencimientos no quedaron caja=hoy+8, pedido=hoy+30, contraentrega=hoy, sin plazo=NULL (o la deuda no suma 10000)';
    END IF;

    -- El plazo propio es solo de la venta de un pedido.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias)
        VALUES (gen_random_uuid(), a, 'pagado', 'CREDITO', 'v71-tienda', 1, 'caja', 5);
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V71: una venta de la caja entro con plazo propio'; END IF;

    -- La insolvencia sigue mandando, también en la venta de un pedido.
    UPDATE public.clientes SET en_insolvencia_desde = v_hoy WHERE tenant_id = a AND documento = 'v71-tienda';
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias)
        VALUES (gen_random_uuid(), a, 'pagado', 'CREDITO', 'v71-tienda', 1, 'pedido', 30);
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V71: la venta de un pedido a un cliente en insolvencia entro'; END IF;

    -- Limpieza: lo insertado y lo que provocaron los disparadores (sede, contador, débito, eventos).
    DELETE FROM public.debt_transactions      WHERE tenant_id = a;
    DELETE FROM public.orders                 WHERE tenant_id = a;
    DELETE FROM public.tenant_order_counters  WHERE tenant_id = a;
    DELETE FROM public.sites                  WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos       WHERE tenant_id = a;
    DELETE FROM public.accounts_receivable    WHERE tenant_id = a;
    DELETE FROM public.clientes               WHERE tenant_id = a;
    DELETE FROM public.tenants                WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v71%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v71%') THEN
        RAISE EXCEPTION 'V71: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V71: caja vence con el plazo del cliente (8); pedido con el suyo (30), contraentrega hoy y sin plazo sin fecha; plazo propio solo en pedidos; insolvencia rechazada; 0 restos';
END $cierre$;
