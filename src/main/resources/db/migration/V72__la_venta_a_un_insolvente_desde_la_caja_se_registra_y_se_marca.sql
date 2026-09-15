-- =====================================================================
-- V72 -- La venta a crédito a un cliente en insolvencia que ya hizo la caja
--        se registra con su deuda y queda marcada para revisar. La que no
--        salió de una caja se sigue rechazando.
--
-- Plan de mayoristas F4.11 (opción A, decidida por ECM el 2026-09-15).
--
-- ── Lo que había ──────────────────────────────────────────────────────
--
-- `fn_venta_a_credito` (V65, con la rama del pedido de V71) rechaza con P0001
-- toda venta a crédito a un cliente con `en_insolvencia_desde <= hoy`. El POS
-- guarda cada venta en su outbox antes de mandarla: si el admin marca la
-- insolvencia mientras una caja ya vendió (sin red, o segundos antes), el
-- servidor rechaza una venta que ya ocurrió. La mercancía salió y la deuda no
-- queda en ninguna parte.
--
-- ── Lo que se añade ───────────────────────────────────────────────────
--
-- El criterio es DE DÓNDE VIENE la venta, no un reloj (decisión de ECM):
--
--   · Con `terminal_id` (una caja) y que no nazca de un pedido: entra con su
--     DEBIT, como cualquier venta a crédito, y deja una fila en
--     `ventas_a_insolvente` (la marca VENTA_A_INSOLVENTE_POR_REVISAR).
--   · Sin terminal (API directa, panel) o nacida de un pedido (despacho de F5.5):
--     P0001 como hasta hoy → 409 CLIENTE_EN_INSOLVENCIA.
--
-- `ventas_a_insolvente`: la venta, el cliente, desde cuándo estaba en
-- insolvencia, el total, la caja, quién la operó y quién vendió (pueden ser
-- distintos, V60) y cuándo (el reloj de la caja y el del servidor). La escribe
-- SOLO este disparador.
--
-- `ventas_a_insolvente_resoluciones`: la decisión del admin sobre una marca
-- (DEJAR_COMO_DEUDA | COBRAR_DE_CONTADO), con autor obligatorio. Una por marca.
-- Anular la venta espera a D7; aquí no se anula nada.
--
-- Las dos solo anexan: app_user tiene SELECT e INSERT, sin UPDATE ni DELETE.
--
-- Sin FK de la marca a `orders`: el disparador es BEFORE INSERT y la venta
-- todavía no existe cuando se escribe la marca (igual que el DEBIT, que tampoco
-- la tiene). UNIQUE (tenant_id, order_uuid): una marca por venta.
--
-- 🔴 Es el disparador de TODA venta a crédito. Por eso:
--   · Guarda: el cuerpo vigente tiene que ser el de V71 (md5 de prosrc
--     0913c97c21404eb88a530bc84c655aa5, medido en staging). Si no, se para.
--   · Cuerpo = V71 + la rama de la caja en la insolvencia + el INSERT de la marca.
--     Cuenta, cupo, plazo y débito no cambian.
--   · LaVentaDeSiempreTest (prueba de oro) vigila la venta a crédito del POS.
--
-- INVOKER como V65 y V71: corre con los permisos de quien inserta la venta.
-- IMPACTO: dos tablas nuevas vacías y la función redefinida; ningún dato se toca.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       IS DISTINCT FROM '0913c97c21404eb88a530bc84c655aa5' THEN
        RAISE EXCEPTION 'V72: fn_venta_a_credito no es la de V71 (md5 %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'));
    END IF;
END $guarda$;

-- ── 1 · La marca ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.ventas_a_insolvente (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            TEXT        NOT NULL,
    order_uuid           UUID        NOT NULL,
    cliente_documento    TEXT        NOT NULL,
    en_insolvencia_desde DATE        NOT NULL,
    total                NUMERIC     NOT NULL,
    terminal_id          UUID        NOT NULL,
    operado_por          BIGINT      NULL,
    vendedor_id          BIGINT      NULL,
    ocurrido_en          TIMESTAMPTZ NULL,
    registrado_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ventas_a_insolvente PRIMARY KEY (id),
    CONSTRAINT ux_ventas_a_insolvente_id UNIQUE (tenant_id, id),
    CONSTRAINT ux_ventas_a_insolvente_venta UNIQUE (tenant_id, order_uuid)
);
CREATE INDEX IF NOT EXISTS ix_ventas_a_insolvente_recientes
    ON public.ventas_a_insolvente (tenant_id, registrado_en DESC);
COMMENT ON TABLE public.ventas_a_insolvente IS
    'VENTA_A_INSOLVENTE_POR_REVISAR: venta a credito hecha por una caja a un cliente ya en insolvencia. '
    'Entro con su DEBIT. Solo anexa; la escribe fn_venta_a_credito y nadie mas. V72 (F4.11).';

-- ── 2 · La resolución ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.ventas_a_insolvente_resoluciones (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id              TEXT        NOT NULL,
    venta_a_insolvente_id  UUID        NOT NULL,
    decision               TEXT        NOT NULL,
    nota                   TEXT        NULL,
    usuario_id             BIGINT      NOT NULL REFERENCES public.users(id),
    resuelto_en            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ventas_a_insolvente_resoluciones PRIMARY KEY (id),
    CONSTRAINT fk_resolucion_de_la_marca FOREIGN KEY (tenant_id, venta_a_insolvente_id)
        REFERENCES public.ventas_a_insolvente (tenant_id, id),
    CONSTRAINT ux_una_resolucion_por_marca UNIQUE (tenant_id, venta_a_insolvente_id),
    CONSTRAINT ck_resolucion_decision CHECK (decision IN ('DEJAR_COMO_DEUDA', 'COBRAR_DE_CONTADO')),
    CONSTRAINT ck_resolucion_nota CHECK (nota IS NULL OR length(nota) BETWEEN 1 AND 500)
);
COMMENT ON TABLE public.ventas_a_insolvente_resoluciones IS
    'Lo que decidio el admin sobre una venta a insolvente: DEJAR_COMO_DEUDA o COBRAR_DE_CONTADO. '
    'Una por marca, con autor. Solo anexa. Anular la venta espera a D7. V72 (F4.11).';

-- ── 3 · Aislamiento y permisos ────────────────────────────────────────
ALTER TABLE public.ventas_a_insolvente ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ventas_a_insolvente FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_ventas_a_insolvente ON public.ventas_a_insolvente;
CREATE POLICY tenant_isolation_ventas_a_insolvente ON public.ventas_a_insolvente
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE public.ventas_a_insolvente_resoluciones ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ventas_a_insolvente_resoluciones FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_ventas_a_insolvente_resoluciones ON public.ventas_a_insolvente_resoluciones;
CREATE POLICY tenant_isolation_ventas_a_insolvente_resoluciones ON public.ventas_a_insolvente_resoluciones
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        -- Solo anexan: sin UPDATE ni DELETE. El disparador corre como quien inserta la venta.
        GRANT SELECT, INSERT ON public.ventas_a_insolvente TO app_user;
        GRANT SELECT, INSERT ON public.ventas_a_insolvente_resoluciones TO app_user;
    END IF;
END
$$;

-- ── 4 · El disparador, con la rama de la caja ─────────────────────────
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
    v_marcar     BOOLEAN := false;
BEGIN
    IF NEW.cliente_documento IS NULL OR btrim(NEW.cliente_documento) = '' THEN
        RAISE EXCEPTION 'Una venta a credito necesita el documento del cliente.';
    END IF;

    SELECT c.plazo_dias, c.en_insolvencia_desde INTO v_plazo, v_insolvente
      FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id
       AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL AND v_insolvente <= v_hoy THEN
        -- V72 (F4.11): la venta que ya hizo una caja entra y se marca; la que no salió
        -- de una caja (API directa, panel, despacho de un pedido) se sigue rechazando.
        IF NEW.terminal_id IS NULL OR NEW.origen IS NOT DISTINCT FROM 'pedido' THEN
            RAISE EXCEPTION 'El cliente % esta en proceso de insolvencia desde el %: no se le vende a credito.',
                NEW.cliente_documento, to_char(v_insolvente, 'DD/MM/YYYY');
        END IF;
        v_marcar := true;
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

    IF v_marcar THEN
        INSERT INTO public.ventas_a_insolvente
            (tenant_id, order_uuid, cliente_documento, en_insolvencia_desde, total, terminal_id, operado_por, vendedor_id, ocurrido_en)
        VALUES (NEW.tenant_id, NEW.uuid_id, NEW.cliente_documento, v_insolvente, COALESCE(NEW.total, 0),
                NEW.terminal_id, NEW.created_by, NEW.vendedor_id, NEW.ocurrido_en);
    END IF;

    NEW.excede_cupo := v_excede;
    RETURN NEW;
END $function$;

-- ── 5 · Cierre, por comportamiento ────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v72_a__';
    b CONSTANT TEXT := '__prueba_v72_b__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_caja  UUID := gen_random_uuid();
    v_term  UUID := gen_random_uuid();
    v_solv  UUID := gen_random_uuid();
    v_fut   UUID := gen_random_uuid();
    v_api   UUID := gen_random_uuid();
    v_ped   UUID := gen_random_uuid();
    v_user  BIGINT;
    v_marca UUID;
    v_m     RECORD;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V72 A', 'basico'), (b, 'Prueba V72 B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v72@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.terminals (id, tenant_id) VALUES (v_term, a);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por, en_insolvencia_desde) VALUES
        (a, 'v72-insolvente', 'Insolvente', 8, 'v72', v_hoy),
        (a, 'v72-solvente',   'Solvente',   8, 'v72', NULL),
        (a, 'v72-futuro',     'Futuro',     8, 'v72', v_hoy + 3);
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v72-c-ins', a, now(), 1000000, 'v72-insolvente', 'Insolvente', 'ACTIVE', 0, now()),
        ('v72-c-sol', a, now(), 1000000, 'v72-solvente',   'Solvente',   'ACTIVE', 0, now()),
        ('v72-c-fut', a, now(), 1000000, 'v72-futuro',     'Futuro',     'ACTIVE', 0, now());

    -- 1. La caja le vende al insolvente: entra con su DEBIT y queda marcada, con todo lo que la describe.
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, terminal_id, ocurrido_en, created_by)
    VALUES (v_caja, a, 'pagado', 'CREDITO', 'v72-insolvente', 5000, 'caja', v_term, now() - interval '2 hours', v_user);
    SELECT * INTO v_m FROM public.ventas_a_insolvente WHERE tenant_id = a AND order_uuid = v_caja;
    IF NOT FOUND
       OR v_m.cliente_documento <> 'v72-insolvente' OR v_m.en_insolvencia_desde <> v_hoy OR v_m.total <> 5000
       OR v_m.terminal_id <> v_term OR v_m.ocurrido_en IS NULL OR v_m.operado_por IS DISTINCT FROM v_user
       OR (SELECT count(*) FROM public.debt_transactions WHERE order_uuid = v_caja AND type = 'DEBIT' AND amount = 5000) <> 1
       OR (SELECT total_debt FROM public.accounts_receivable WHERE id = 'v72-c-ins') <> 5000 THEN
        RAISE EXCEPTION 'V72: la venta de la caja al insolvente no quedo con DEBIT de 5000 y su marca completa';
    END IF;
    v_marca := v_m.id;

    -- 2. Control: la caja le vende a un solvente y a uno cuya insolvencia empieza en 3 días: sin marca.
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, terminal_id) VALUES
        (v_solv, a, 'pagado', 'CREDITO', 'v72-solvente', 100, 'caja', v_term),
        (v_fut,  a, 'pagado', 'CREDITO', 'v72-futuro',   100, 'caja', v_term);
    IF (SELECT count(*) FROM public.ventas_a_insolvente WHERE tenant_id = a) <> 1 THEN
        RAISE EXCEPTION 'V72: una venta a un cliente que no esta en insolvencia dejo marca';
    END IF;

    -- 3. Sin terminal: rechazada como siempre, sin deuda ni marca.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
        VALUES (v_api, a, 'pagado', 'CREDITO', 'v72-insolvente', 700, 'caja');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V72: una venta a credito al insolvente SIN terminal entro'; END IF;

    -- 4. La venta de un pedido, aunque traiga terminal: rechazada.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias, terminal_id)
        VALUES (v_ped, a, 'pagado', 'CREDITO', 'v72-insolvente', 700, 'pedido', 30, v_term);
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V72: la venta de un pedido al insolvente entro por tener terminal'; END IF;
    IF EXISTS (SELECT 1 FROM public.debt_transactions WHERE order_uuid IN (v_api, v_ped))
       OR EXISTS (SELECT 1 FROM public.ventas_a_insolvente WHERE order_uuid IN (v_api, v_ped)) THEN
        RAISE EXCEPTION 'V72: una venta rechazada dejo deuda o marca';
    END IF;

    -- 5. La resolución: una por marca, con decisión del catálogo, de la marca del mismo negocio.
    INSERT INTO public.ventas_a_insolvente_resoluciones (tenant_id, venta_a_insolvente_id, decision, usuario_id)
    VALUES (a, v_marca, 'DEJAR_COMO_DEUDA', v_user);
    v_rechazado := false;
    BEGIN
        INSERT INTO public.ventas_a_insolvente_resoluciones (tenant_id, venta_a_insolvente_id, decision, usuario_id)
        VALUES (a, v_marca, 'COBRAR_DE_CONTADO', v_user);
    EXCEPTION WHEN unique_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V72: una marca admitio dos resoluciones'; END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO public.ventas_a_insolvente_resoluciones (tenant_id, venta_a_insolvente_id, decision, usuario_id)
        VALUES (b, v_marca, 'DEJAR_COMO_DEUDA', v_user);
    EXCEPTION WHEN foreign_key_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V72: otro negocio resolvio la marca de A'; END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO public.ventas_a_insolvente_resoluciones (tenant_id, venta_a_insolvente_id, decision, usuario_id)
        VALUES (a, gen_random_uuid(), 'ANULAR', v_user);
    EXCEPTION WHEN check_violation OR foreign_key_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V72: una resolucion fuera del catalogo entro'; END IF;

    -- 6. Solo anexan y el disparador sigue siendo INVOKER.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.ventas_a_insolvente', 'UPDATE')
            OR has_table_privilege('app_user', 'public.ventas_a_insolvente', 'DELETE')
            OR has_table_privilege('app_user', 'public.ventas_a_insolvente_resoluciones', 'UPDATE')
            OR has_table_privilege('app_user', 'public.ventas_a_insolvente_resoluciones', 'DELETE')) THEN
        RAISE EXCEPTION 'V72: app_user puede modificar o borrar marcas o resoluciones';
    END IF;
    IF (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()')) THEN
        RAISE EXCEPTION 'V72: fn_venta_a_credito quedo SECURITY DEFINER';
    END IF;

    -- Limpieza: lo insertado y lo que provocaron los disparadores (sede, contador, débito, eventos).
    DELETE FROM public.ventas_a_insolvente_resoluciones WHERE tenant_id IN (a, b);
    DELETE FROM public.ventas_a_insolvente    WHERE tenant_id IN (a, b);
    DELETE FROM public.debt_transactions      WHERE tenant_id IN (a, b);
    DELETE FROM public.orders                 WHERE tenant_id IN (a, b);
    DELETE FROM public.tenant_order_counters  WHERE tenant_id IN (a, b);
    DELETE FROM public.terminals              WHERE tenant_id IN (a, b);
    DELETE FROM public.sites                  WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes_eventos       WHERE tenant_id IN (a, b);
    DELETE FROM public.accounts_receivable    WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes               WHERE tenant_id IN (a, b);
    DELETE FROM public.users                  WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants                WHERE id IN (a, b);

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v72%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v72%') THEN
        RAISE EXCEPTION 'V72: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V72: la caja le vende al insolvente con DEBIT y marca; solvente y futuro sin marca; sin terminal y pedido rechazados sin rastro; una resolucion por marca, del negocio y del catalogo; solo anexan; INVOKER; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- Restaurar fn_venta_a_credito con el cuerpo de V71 (md5 0913c97c21404eb88a530bc84c655aa5);
-- DROP TABLE public.ventas_a_insolvente_resoluciones; DROP TABLE public.ventas_a_insolvente;
-- (antes, decidir qué pasa con las ventas marcadas: su DEBIT es real y se queda).
