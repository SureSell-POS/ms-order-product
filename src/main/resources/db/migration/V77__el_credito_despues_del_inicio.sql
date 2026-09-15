-- =====================================================================
-- V77 -- El crédito después del inicio del proceso de insolvencia (plan de mayoristas F4.13, corte (c)).
--
-- Diseño: docs/planes/DISENO-F4-13-INSOLVENCIA-COMO-PROCESO.md §6 y §11.4; decisiones de ECM del 2026-09-15 (TEXTOS B10).
--
-- ── La regla ────────────────────────────────────────────────────────
--   · El admin habilita o deshabilita, por proceso, la venta a crédito a un cliente en proceso, con un plazo máximo de
--     0 a 30 días (8 si no se dice) y un motivo. Solo anexa; lo vigente es la última fila del proceso.
--   · Vale SOLO con la etapa vigente en INICIO o ACUERDO_CONFIRMADO. En LIQUIDACION queda deshabilitado por derivación,
--     sin escribir nada; y no se puede habilitar. Un proceso nuevo empieza deshabilitado. Antes del inicio (SOLICITUD o
--     sin proceso) no hay nada que habilitar: se le vende como a cualquier cliente.
--   · `fn_venta_a_credito`: con el crédito vigente, la venta a crédito entra (caja, API o despacho de un pedido) SIN la
--     marca de F4.11 y con vencimiento acotado: hoy + LEAST(plazo, máximo); sin plazo pactado, el máximo; contraentrega
--     (0), 0. No se cruza saldo a favor (V74 ya devuelve 0 en proceso). Es POSTERIOR por construcción (V75).
--
-- ── fn_venta_a_credito ──────────────────────────────────────────────
--   · Guarda: el cuerpo vigente tiene que ser el de V74 (md5 de prosrc 90f68e52c5c431e9c29cbbb4c4edeacc, medido por C en
--     staging). Cuerpo = V74 + la lectura del crédito vigente SOLO con el cliente en proceso (la rama del insolvente).
--   · El cliente sin proceso (el 99 %) no hace ninguna lectura más que hoy.
--
-- IMPACTO: una tabla nueva vacía, una vista, una función; fn_venta_a_credito redefinida. No toca datos.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       IS DISTINCT FROM '90f68e52c5c431e9c29cbbb4c4edeacc' THEN
        RAISE EXCEPTION 'V77: fn_venta_a_credito no es la de V74 (md5 de prosrc %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'));
    END IF;
END $guarda$;

-- ── 1 · Lo habilitado, por proceso ──────────────────────────────────
CREATE TABLE public.insolvencia_credito_posterior (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    orden             BIGINT      GENERATED ALWAYS AS IDENTITY,
    tenant_id         TEXT        NOT NULL,
    proceso_id        UUID        NOT NULL,
    habilitado        BOOLEAN     NOT NULL,
    plazo_maximo_dias SMALLINT    NOT NULL,
    motivo            TEXT        NOT NULL,
    registrado_por    BIGINT      NOT NULL REFERENCES public.users(id),
    registrado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_insolvencia_credito_posterior PRIMARY KEY (id),
    CONSTRAINT fk_insolvencia_credito_posterior_proceso FOREIGN KEY (tenant_id, proceso_id) REFERENCES public.insolvencia_procesos (tenant_id, id),
    CONSTRAINT ck_insolvencia_credito_posterior_plazo CHECK (plazo_maximo_dias BETWEEN 0 AND 30),
    CONSTRAINT ck_insolvencia_credito_posterior_motivo CHECK (length(btrim(motivo)) > 0)
);
CREATE INDEX ix_insolvencia_credito_posterior_proceso ON public.insolvencia_credito_posterior (tenant_id, proceso_id, orden);
COMMENT ON TABLE public.insolvencia_credito_posterior IS
    'F4.13 (c): crédito después del inicio, por proceso. Solo anexa; lo vigente es la última fila. La escribe solo '
    'fn_insolvencia_credito_posterior. V77.';

-- ── 2 · Lo vigente, derivado ─────────────────────────────────────────
CREATE VIEW public.v_insolvencia_credito_posterior WITH (security_invoker = true) AS
SELECT vv.tenant_id, vv.proceso_id, vv.cliente_documento, vv.etapa,
       COALESCE(u.habilitado, false) AS habilitado, u.plazo_maximo_dias, u.motivo, u.registrado_por, u.registrado_en,
       (COALESCE(u.habilitado, false) AND vv.etapa IN ('INICIO', 'ACUERDO_CONFIRMADO')) AS vigente
  FROM public.v_insolvencia_vigente vv
  LEFT JOIN LATERAL (SELECT cp.habilitado, cp.plazo_maximo_dias, cp.motivo, cp.registrado_por, cp.registrado_en
                       FROM public.insolvencia_credito_posterior cp
                      WHERE cp.tenant_id = vv.tenant_id AND cp.proceso_id = vv.proceso_id
                      ORDER BY cp.orden DESC LIMIT 1) u ON true
 WHERE vv.en_proceso;
COMMENT ON VIEW public.v_insolvencia_credito_posterior IS
    'F4.13 (c): por proceso en curso, lo último habilitado y si vale (vigente: habilitado y etapa INICIO o ACUERDO). V77.';

-- ── 3 · La única forma de habilitar o deshabilitar ──────────────────
CREATE FUNCTION public.fn_insolvencia_credito_posterior(p_documento_cliente TEXT, p_habilitado BOOLEAN,
                                                        p_plazo_maximo_dias INTEGER, p_motivo TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_negocio TEXT := NULLIF(current_setting('app.tenant_id', true), '');
    v_usuario_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_usuario BIGINT;
    v_proceso UUID;
    v_etapa TEXT;
    v_id UUID;
BEGIN
    IF v_negocio IS NULL THEN
        RAISE EXCEPTION 'Credito posterior sin negocio en la sesion.' USING ERRCODE = 'P0001';
    END IF;
    IF v_usuario_txt IS NULL OR v_usuario_txt !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'Credito posterior sin usuario en la sesion: lleva autor.' USING ERRCODE = 'P0001';
    END IF;
    v_usuario := v_usuario_txt::BIGINT;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = v_usuario AND u.tenant_id = v_negocio) THEN
        RAISE EXCEPTION 'El usuario % no es de este negocio.', v_usuario USING ERRCODE = 'P0001';
    END IF;
    IF p_habilitado IS NULL THEN
        RAISE EXCEPTION 'Indica si se habilita o no.' USING ERRCODE = 'P0001';
    END IF;
    IF p_plazo_maximo_dias IS NULL OR p_plazo_maximo_dias NOT BETWEEN 0 AND 30 THEN
        RAISE EXCEPTION 'El plazo maximo es de 0 a 30 dias.' USING ERRCODE = 'P0001';
    END IF;
    IF p_motivo IS NULL OR btrim(p_motivo) = '' THEN
        RAISE EXCEPTION 'Falta el motivo.' USING ERRCODE = 'P0001';
    END IF;
    PERFORM 1 FROM public.clientes c WHERE c.tenant_id = v_negocio AND c.documento = p_documento_cliente FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Ese cliente no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    SELECT vv.proceso_id, vv.etapa INTO v_proceso, v_etapa FROM public.v_insolvencia_vigente vv
     WHERE vv.tenant_id = v_negocio AND vv.cliente_documento = p_documento_cliente AND vv.en_proceso
     ORDER BY vv.abierto_en DESC LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Solo se habilita el credito cuando el proceso ya inicio.' USING ERRCODE = 'P0001';
    END IF;
    IF v_etapa = 'LIQUIDACION' AND p_habilitado THEN
        RAISE EXCEPTION 'En liquidacion no se habilita el credito.' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.insolvencia_credito_posterior (tenant_id, proceso_id, habilitado, plazo_maximo_dias, motivo, registrado_por)
    VALUES (v_negocio, v_proceso, p_habilitado, p_plazo_maximo_dias, btrim(p_motivo), v_usuario)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
COMMENT ON FUNCTION public.fn_insolvencia_credito_posterior(TEXT, BOOLEAN, INTEGER, TEXT) IS
    'F4.13 (c): habilita o deshabilita el crédito después del inicio para el proceso en curso del cliente del negocio de la '
    'sesion. Plazo 0..30, motivo y autor. En liquidacion no se habilita. V77.';

-- ── 4 · La venta a crédito lo respeta ────────────────────────────────
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
    v_habilitada BOOLEAN := false;
    v_maximo     INTEGER;
BEGIN
    IF NEW.cliente_documento IS NULL OR btrim(NEW.cliente_documento) = '' THEN
        RAISE EXCEPTION 'Una venta a credito necesita el documento del cliente.';
    END IF;

    SELECT c.plazo_dias, c.en_insolvencia_desde INTO v_plazo, v_insolvente
      FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id
       AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL AND v_insolvente <= v_hoy THEN
        -- V77 (F4.13 c): con el crédito después del inicio vigente, la venta entra sin marca y con plazo acotado.
        SELECT cp.plazo_maximo_dias INTO v_maximo
          FROM public.v_insolvencia_credito_posterior cp
         WHERE cp.tenant_id = NEW.tenant_id AND cp.cliente_documento = NEW.cliente_documento AND cp.vigente;
        v_habilitada := FOUND;
        IF NOT v_habilitada THEN
            -- V72 (F4.11): la venta que ya hizo una caja entra y se marca; la que no salió
            -- de una caja (API directa, panel, despacho de un pedido) se sigue rechazando.
            IF NEW.terminal_id IS NULL OR NEW.origen IS NOT DISTINCT FROM 'pedido' THEN
                RAISE EXCEPTION 'El cliente % esta en proceso de insolvencia desde el %: no se le vende a credito.',
                    NEW.cliente_documento, to_char(v_insolvente, 'DD/MM/YYYY');
            END IF;
            v_marcar := true;
        END IF;
    END IF;

    -- V71 (F5.5): la venta que nace de despachar un pedido vence con el plazo PACTADO en
    -- el pedido, congelado en la captura; el del cliente pudo cambiar después (decisión
    -- de ECM). NULL = sin plazo pactado, 0 = contraentrega. Toda otra venta, como siempre.
    IF NEW.origen = 'pedido' THEN
        v_plazo := NEW.plazo_dias;
    END IF;
    -- V77: acotado al máximo habilitado; sin plazo pactado, el máximo; contraentrega sigue en 0.
    IF v_habilitada THEN
        v_plazo := LEAST(COALESCE(v_plazo, v_maximo), v_maximo);
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
    ELSIF NOT v_habilitada THEN
        -- V74 (F4.12): si el cliente tiene saldo a favor, se aplica solo a esta factura (y a las
        -- vivas más viejas). A un cliente en insolvencia no: la función no cruza nada. Vuelve
        -- enseguida si no hay saldo a favor. V77: la venta habilitada en proceso tampoco lo llama.
        PERFORM public.fn_aplicar_saldo_a_favor(NEW.tenant_id, NEW.cliente_documento);
    END IF;

    NEW.excede_cupo := v_excede;
    RETURN NEW;
END $function$;

-- ── 5 · Aislamiento y permisos ──────────────────────────────────────
DO $rls$
BEGIN
    ALTER TABLE public.insolvencia_credito_posterior ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.insolvencia_credito_posterior FORCE ROW LEVEL SECURITY;
    CREATE POLICY tenant_isolation_insolvencia_credito_posterior ON public.insolvencia_credito_posterior
        USING (tenant_id = current_setting('app.tenant_id', true))
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
    REVOKE ALL ON FUNCTION public.fn_insolvencia_credito_posterior(TEXT, BOOLEAN, INTEGER, TEXT) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.insolvencia_credito_posterior, public.v_insolvencia_credito_posterior TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_insolvencia_credito_posterior(TEXT, BOOLEAN, INTEGER, TEXT) TO app_user;
    END IF;
END
$rls$;

-- ── 6 · Cierre, por comportamiento ──────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v77_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_term UUID := gen_random_uuid();
    v_v1 UUID := gen_random_uuid(); v_v2 UUID := gen_random_uuid(); v_v3 UUID := gen_random_uuid();
    v_v4 UUID := gen_random_uuid(); v_v5 UUID := gen_random_uuid(); v_v6 UUID := gen_random_uuid();
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V77 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v77@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.terminals (id, tenant_id) VALUES (v_term, a);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v77-ins', 'En proceso', 20, 'v77'), (a, 'v77-sol', 'En solicitud', 8, 'v77');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v77-c-ins', a, now(), 1000000, 'v77-ins', 'En proceso', 'ACTIVE', 0, now()),
        ('v77-c-sol', a, now(), 1000000, 'v77-sol', 'En solicitud', 'ACTIVE', 0, now());
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);

    -- Sin proceso en curso no hay nada que habilitar.
    PERFORM public.fn_insolvencia_informar_etapa('v77-sol', 'SOLICITUD', v_hoy, 'Radicado', NULL, 'cliente', NULL, NULL, NULL);
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_credito_posterior('v77-sol', true, 8, 'Prueba');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V77: se habilito credito posterior en SOLICITUD'; END IF;

    PERFORM public.fn_insolvencia_informar_etapa('v77-ins', 'INICIO', v_hoy - 5, 'Auto 77', NULL, 'abogado', NULL, NULL, NULL);
    -- Sin habilitar: la API se rechaza y la caja se marca (V72, sin cambio).
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
        VALUES (v_v1, a, 'pagado', 'CREDITO', 'v77-ins', 10000, 'caja');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V77: sin habilitar, una venta a credito sin caja entro'; END IF;
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, terminal_id)
    VALUES (v_v2, a, 'pagado', 'CREDITO', 'v77-ins', 10000, 'caja', v_term);
    IF NOT EXISTS (SELECT 1 FROM public.ventas_a_insolvente WHERE tenant_id = a AND order_uuid = v_v2) THEN
        RAISE EXCEPTION 'V77: sin habilitar, la venta de caja no quedo marcada';
    END IF;

    -- Habilitado con máximo 8: sin caja entra, sin marca, vence a 8 (el cliente pacta 20).
    PERFORM public.fn_insolvencia_credito_posterior('v77-ins', true, 8, 'Acuerdo con el promotor');
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
    VALUES (v_v3, a, 'pagado', 'CREDITO', 'v77-ins', 30000, 'caja');
    IF EXISTS (SELECT 1 FROM public.ventas_a_insolvente WHERE tenant_id = a AND order_uuid = v_v3)
       OR (SELECT vence_el FROM public.debt_transactions WHERE tenant_id = a AND order_uuid = v_v3) <> v_hoy + 8
       OR (SELECT clasificacion FROM public.v_insolvencia_clasificacion k JOIN public.debt_transactions d ON d.id = k.debito_tx_id
            WHERE d.tenant_id = a AND d.order_uuid = v_v3) <> 'POSTERIOR' THEN
        RAISE EXCEPTION 'V77: la venta habilitada no entro sin marca, a 8 dias y POSTERIOR';
    END IF;
    -- Pedido sin plazo pactado → el máximo; pedido contraentrega → 0.
    INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen, plazo_dias)
    VALUES (v_v4, a, 'pagado', 'CREDITO', 'v77-ins', 5000, 'pedido', NULL),
           (v_v5, a, 'pagado', 'CREDITO', 'v77-ins', 5000, 'pedido', 0);
    IF (SELECT vence_el FROM public.debt_transactions WHERE tenant_id = a AND order_uuid = v_v4) <> v_hoy + 8
       OR (SELECT vence_el FROM public.debt_transactions WHERE tenant_id = a AND order_uuid = v_v5) <> v_hoy THEN
        RAISE EXCEPTION 'V77: el pedido sin plazo no vencio al maximo o la contraentrega no quedo en 0';
    END IF;
    -- En LIQUIDACION deja de valer sin escribir nada, y no se puede volver a habilitar.
    PERFORM public.fn_insolvencia_informar_etapa('v77-ins', 'LIQUIDACION', v_hoy, 'Auto 78', NULL, 'abogado', NULL, NULL, NULL);
    IF (SELECT vigente FROM public.v_insolvencia_credito_posterior WHERE tenant_id = a AND cliente_documento = 'v77-ins') THEN
        RAISE EXCEPTION 'V77: en liquidacion el credito posterior sigue vigente';
    END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO public.orders (uuid_id, tenant_id, status, payment_method, cliente_documento, total, origen)
        VALUES (v_v6, a, 'pagado', 'CREDITO', 'v77-ins', 10000, 'caja');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V77: en liquidacion entro una venta a credito sin caja'; END IF;
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_credito_posterior('v77-ins', true, 8, 'Otra vez');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V77: se habilito credito posterior en liquidacion'; END IF;
    -- Plazo fuera de 0..30.
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_credito_posterior('v77-ins', false, 31, 'Plazo largo');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V77: entro un plazo maximo de 31 dias'; END IF;

    -- Permisos: solo anexa; DEFINER sin PUBLIC; la vista con security_invoker; la venta sigue INVOKER.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.insolvencia_credito_posterior', 'INSERT')
            OR has_table_privilege('app_user', 'public.insolvencia_credito_posterior', 'UPDATE')
            OR has_table_privilege('app_user', 'public.insolvencia_credito_posterior', 'DELETE')) THEN
        RAISE EXCEPTION 'V77: app_user escribe credito posterior sin la funcion';
    END IF;
    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_insolvencia_credito_posterior(text,boolean,integer,text)'))
       OR has_function_privilege('public', 'public.fn_insolvencia_credito_posterior(text,boolean,integer,text)', 'EXECUTE')
       OR (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()'))
       OR NOT COALESCE((SELECT reloptions @> ARRAY['security_invoker=true'] FROM pg_class WHERE oid = 'public.v_insolvencia_credito_posterior'::regclass), false) THEN
        RAISE EXCEPTION 'V77: permisos de las funciones o de la vista mal puestos';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.cartera_aplicaciones          WHERE tenant_id = a;
    DELETE FROM public.ventas_a_insolvente           WHERE tenant_id = a;
    DELETE FROM public.debt_transactions             WHERE tenant_id = a;
    DELETE FROM public.orders                        WHERE tenant_id = a;
    DELETE FROM public.tenant_order_counters         WHERE tenant_id = a;
    DELETE FROM public.terminals                     WHERE tenant_id = a;
    DELETE FROM public.sites                         WHERE tenant_id = a;
    DELETE FROM public.insolvencia_credito_posterior WHERE tenant_id = a;
    DELETE FROM public.insolvencia_foto_facturas     WHERE tenant_id = a;
    DELETE FROM public.insolvencia_fotos             WHERE tenant_id = a;
    DELETE FROM public.insolvencia_etapas            WHERE tenant_id = a;
    DELETE FROM public.insolvencia_procesos          WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos              WHERE tenant_id = a;
    DELETE FROM public.accounts_receivable           WHERE tenant_id = a;
    DELETE FROM public.clientes                      WHERE tenant_id = a;
    DELETE FROM public.users                         WHERE tenant_id = a;
    DELETE FROM public.tenants                       WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v77%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v77%') THEN
        RAISE EXCEPTION 'V77: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V77: sin proceso no se habilita; sin habilitar la API se rechaza y la caja se marca; habilitado entra sin marca, acotado a 8 y POSTERIOR; pedido sin plazo al maximo y contraentrega en 0; en liquidacion deja de valer y no se habilita; plazo 0..30; permisos; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- Restaurar fn_venta_a_credito con el cuerpo de V74 (md5 de prosrc 90f68e52c5c431e9c29cbbb4c4edeacc);
-- DROP FUNCTION public.fn_insolvencia_credito_posterior(TEXT, BOOLEAN, INTEGER, TEXT);
-- DROP VIEW public.v_insolvencia_credito_posterior; DROP TABLE public.insolvencia_credito_posterior;
