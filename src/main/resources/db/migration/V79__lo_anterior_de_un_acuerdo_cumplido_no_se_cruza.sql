-- =====================================================================
-- V79 -- Lo anterior de un acuerdo cumplido no se cruza (plan de mayoristas F4.13e).
--
-- Hallado al diseñar F4.13b (docs/planes/DISENO-F4-13B-REVERSION-CON-RASTRO.md §6 D6); orden de ECM del 2026-09-15.
--
-- ── El defecto ──────────────────────────────────────────────────────
--   Cerrado un proceso con CUMPLIDO_TERMINADO, la proyección `clientes.en_insolvencia_desde` vuelve a NULL (V75) y
--   `fn_aplicar_saldo_a_favor` (V74) volvía a la regla normal ENTERA: cruzaba el saldo a favor con las facturas
--   ANTERIORES al corte que siguieran vivas. Tras cumplir el acuerdo, lo anterior se extinguió según el acuerdo (concepto
--   del abogado) y B8 promete «no se cruza nada».
--
-- ── La regla ────────────────────────────────────────────────────────
--   · Con un proceso del cliente cerrado por CUMPLIDO_TERMINADO, la aplicación automática ignora las obligaciones nacidas
--     antes de su corte (la misma línea que la foto y la clasificación, §11.2 A) y solo cruza con lo nacido después.
--     Con varios procesos cumplidos, cuenta el corte más reciente (nacer antes de alguno = nacer antes del último).
--   · Un proceso cerrado por CORRECCION_DE_ERROR (se marcó por error) no cuenta: la regla normal vuelve entera.
--   · Qué hacer con lo anterior que sigue «vivo» tras el acuerdo (darlo por extinguido con rastro) es OTRA decisión,
--     pendiente: aquí no se toca.
--   · El suelo en la base: un disparador rechaza una aplicación SALDO_A_FAVOR_AUTOMATICO sobre esa obligación. Las demás
--     reglas no hacen ninguna lectura más.
--
-- ── fn_aplicar_saldo_a_favor ────────────────────────────────────────
--   · Guarda: el cuerpo vigente tiene que ser el de V74 (md5 de prosrc 6bf5abf2421cd4dbeb10a0b74185232c, calculado sobre el
--     fichero con el método que da 0107b1c3… para fn_venta_a_credito de V77, el que C midió en staging).
--   · Cuerpo = V74 + el corte del acuerdo cumplido, leído UNA vez y solo si hay saldo a favor (una lectura indexada de
--     procesos del cliente; sin procesos, nada más), y un filtro en el cursor de facturas.
--
-- IMPACTO: una función nueva, una redefinida, un disparador. No toca datos, vistas ni fn_venta_a_credito.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guarda ────────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_aplicar_saldo_a_favor(text,text)'))
       IS DISTINCT FROM '6bf5abf2421cd4dbeb10a0b74185232c' THEN
        RAISE EXCEPTION 'V79: fn_aplicar_saldo_a_favor no es la de V74 (md5 de prosrc %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_aplicar_saldo_a_favor(text,text)'));
    END IF;
END $guarda$;

-- ── 1 · El corte del acuerdo cumplido del cliente ───────────────────
CREATE FUNCTION public.fn_corte_de_acuerdo_cumplido(p_tenant TEXT, p_documento TEXT)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_corte TIMESTAMPTZ;
BEGIN
    -- Lo normal: el cliente nunca tuvo un proceso. Una lectura por ix_insolvencia_procesos_cliente y fuera.
    IF NOT EXISTS (SELECT 1 FROM public.insolvencia_procesos p WHERE p.tenant_id = p_tenant AND p.cliente_documento = p_documento) THEN
        RETURN NULL;
    END IF;
    SELECT max(vv.corte) INTO v_corte FROM public.v_insolvencia_vigente vv
     WHERE vv.tenant_id = p_tenant AND vv.cliente_documento = p_documento AND vv.etapa = 'CUMPLIDO_TERMINADO';
    RETURN v_corte;
END $$;
COMMENT ON FUNCTION public.fn_corte_de_acuerdo_cumplido(TEXT, TEXT) IS
    'F4.13e: el corte mas reciente de los procesos del cliente cerrados por CUMPLIDO_TERMINADO, o NULL. INVOKER. V79.';

-- ── 2 · La aplicación automática lo respeta ─────────────────────────
CREATE OR REPLACE FUNCTION public.fn_aplicar_saldo_a_favor(p_tenant TEXT, p_documento TEXT)
RETURNS NUMERIC LANGUAGE plpgsql AS $$
DECLARE
    v_hoy        DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_total      NUMERIC := 0;
    v_queda_favor NUMERIC := 0;
    v_queda_fact NUMERIC;
    v_parte      NUMERIC;
    v_sin_favor  BOOLEAN := false;
    v_corte      TIMESTAMPTZ;
    f RECORD;
    d RECORD;
    c_favor CURSOR FOR
        SELECT s.recibo_id, s.credito_tx_id, s.saldo_a_favor
          FROM public.v_saldo_a_favor_por_recibo s
         WHERE s.tenant_id = p_tenant AND s.cliente_documento = p_documento
         ORDER BY s.ocurrido_en, s.numero;
    -- V79 (F4.13e): sin las obligaciones nacidas antes del corte de un acuerdo cumplido (p_corte NULL = todas).
    c_facturas CURSOR (p_corte TIMESTAMPTZ) FOR
        SELECT v.debito_tx_id, v.saldo
          FROM public.v_cartera_por_documento v
          JOIN public.debt_transactions t ON t.tenant_id = v.tenant_id AND t.id = v.debito_tx_id
          LEFT JOIN public.orders o ON o.tenant_id = t.tenant_id AND o.uuid_id = t.order_uuid
         WHERE v.tenant_id = p_tenant AND v.cliente_documento = p_documento AND v.saldo > 0
           AND (p_corte IS NULL
                OR NOT (t.transaction_date < (p_corte AT TIME ZONE 'America/Bogota')::date
                        OR (t.transaction_date = (p_corte AT TIME ZONE 'America/Bogota')::date
                            AND COALESCE(o.ocurrido_en, t.created_at) < p_corte)))
         ORDER BY v.fecha, t.created_at, v.debito_tx_id;
BEGIN
    -- En insolvencia compensar es ineficaz de pleno derecho: desde la fecha de inicio no se cruza nada.
    IF EXISTS (
           SELECT 1 FROM public.clientes c
            WHERE c.tenant_id = p_tenant AND c.documento = p_documento AND c.en_insolvencia_desde <= v_hoy) THEN
        RETURN 0;
    END IF;

    OPEN c_favor;
    FETCH c_favor INTO f;
    IF NOT FOUND THEN
        CLOSE c_favor;
        RETURN 0;          -- lo normal: sin saldo a favor, una consulta indexada y fuera
    END IF;
    v_queda_favor := f.saldo_a_favor;

    -- La cuenta bloqueada: dos ventas o cobros del mismo cliente no aplican el mismo saldo dos veces.
    PERFORM 1 FROM public.accounts_receivable ar
      WHERE ar.tenant_id = p_tenant AND ar.customer_document = p_documento FOR UPDATE;

    -- V79 (F4.13e): lo anterior de un acuerdo cumplido se extinguió según el acuerdo; no se cruza.
    v_corte := public.fn_corte_de_acuerdo_cumplido(p_tenant, p_documento);

    OPEN c_facturas(v_corte);
    LOOP
        FETCH c_facturas INTO d;
        EXIT WHEN NOT FOUND;
        v_queda_fact := d.saldo;
        WHILE v_queda_fact > 0 LOOP
            IF v_queda_favor <= 0 THEN
                FETCH c_favor INTO f;
                IF NOT FOUND THEN
                    v_sin_favor := true;
                    EXIT;
                END IF;
                v_queda_favor := f.saldo_a_favor;
            END IF;
            v_parte := LEAST(v_queda_favor, v_queda_fact);
            INSERT INTO public.cartera_aplicaciones
                (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
            VALUES (p_tenant, f.recibo_id, f.credito_tx_id, d.debito_tx_id, v_parte, 'SALDO_A_FAVOR_AUTOMATICO');
            v_total := v_total + v_parte;
            v_queda_favor := v_queda_favor - v_parte;
            v_queda_fact := v_queda_fact - v_parte;
        END LOOP;
        EXIT WHEN v_sin_favor;
    END LOOP;
    CLOSE c_facturas;
    CLOSE c_favor;
    RETURN v_total;
END $$;
COMMENT ON FUNCTION public.fn_aplicar_saldo_a_favor(TEXT, TEXT) IS
    'Aplica el saldo a favor del cliente a sus facturas vivas (recibo mas viejo sobre factura mas vieja), regla '
    'SALDO_A_FAVOR_AUTOMATICO. INVOKER: RLS aplica. Desde en_insolvencia_desde no cruza nada (compensar es ineficaz). '
    'V74; V79: tampoco con lo nacido antes del corte de un acuerdo cumplido.';

-- ── 3 · El suelo en la base ─────────────────────────────────────────
CREATE FUNCTION public.fn_saldo_a_favor_no_cruza_lo_extinguido()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_documento TEXT;
    v_corte TIMESTAMPTZ;
BEGIN
    IF NEW.regla <> 'SALDO_A_FAVOR_AUTOMATICO' THEN
        RETURN NEW;                                     -- el abono y la devolución: ninguna lectura más
    END IF;
    SELECT r.cliente_documento INTO v_documento FROM public.recibos_de_caja r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.recibo_id;
    v_corte := public.fn_corte_de_acuerdo_cumplido(NEW.tenant_id, v_documento);
    IF v_corte IS NOT NULL AND EXISTS (
           SELECT 1 FROM public.debt_transactions t
             LEFT JOIN public.orders o ON o.tenant_id = t.tenant_id AND o.uuid_id = t.order_uuid
            WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.debito_tx_id
              AND (t.transaction_date < (v_corte AT TIME ZONE 'America/Bogota')::date
                   OR (t.transaction_date = (v_corte AT TIME ZONE 'America/Bogota')::date
                       AND COALESCE(o.ocurrido_en, t.created_at) < v_corte))) THEN
        RAISE EXCEPTION 'Esa factura es anterior a un acuerdo de insolvencia cumplido: se extinguio segun el acuerdo y el saldo a favor no se cruza con ella. No se aplico nada.'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_saldo_a_favor_no_cruza_lo_extinguido BEFORE INSERT ON public.cartera_aplicaciones
    FOR EACH ROW EXECUTE FUNCTION public.fn_saldo_a_favor_no_cruza_lo_extinguido();

DO $permisos$
BEGIN
    REVOKE ALL ON FUNCTION public.fn_corte_de_acuerdo_cumplido(TEXT, TEXT) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT EXECUTE ON FUNCTION public.fn_corte_de_acuerdo_cumplido(TEXT, TEXT) TO app_user;
    END IF;
END
$permisos$;

-- ── 4 · Cierre, por comportamiento ──────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v79_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_r UUID; v_r2 UUID;
    v_aplicado NUMERIC;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V79 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v79@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v79-cumplido', 'Acuerdo cumplido', 8, 'v79'), (a, 'v79-error', 'Marcado por error', 8, 'v79');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v79-c-cumplido', a, now(), 1000000, 'v79-cumplido', 'Acuerdo cumplido', 'ACTIVE', 0, now()),
        ('v79-c-error', a, now(), 1000000, 'v79-error', 'Marcado por error', 'ACTIVE', 0, now());
    -- Cada uno debe 100.000 de una factura vieja (anterior al corte).
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) VALUES
        ('v79-vieja-cumplido', a, 'v79-c-cumplido', 100000, now() - interval '30 days', 'x', v_hoy - 30, 'DEBIT', v_hoy - 22),
        ('v79-vieja-error', a, 'v79-c-error', 100000, now() - interval '30 days', 'x', v_hoy - 30, 'DEBIT', v_hoy - 22);
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);

    -- Uno cumple el acuerdo; al otro se le marcó por error.
    PERFORM public.fn_insolvencia_informar_etapa('v79-cumplido', 'INICIO', v_hoy - 10, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v79-cumplido', 'ACUERDO_CONFIRMADO', v_hoy - 5, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v79-cumplido', 'CUMPLIDO_TERMINADO', v_hoy, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v79-error', 'INICIO', v_hoy - 10, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v79-error', 'CORRECCION_DE_ERROR', v_hoy, 'Se marco por error', NULL, 'admin', NULL, NULL, NULL);
    IF public.fn_corte_de_acuerdo_cumplido(a, 'v79-cumplido') IS NULL OR public.fn_corte_de_acuerdo_cumplido(a, 'v79-error') IS NOT NULL THEN
        RAISE EXCEPTION 'V79: el corte del acuerdo cumplido no es el esperado';
    END IF;

    -- 30.000 a favor para cada uno y una venta nueva de 20.000 (nacida después del corte).
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v79-cumplido', 30000, 'EFECTIVO', now(), 'v79-r1') RETURNING id INTO v_r;
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v79-error', 30000, 'EFECTIVO', now(), 'v79-r2') RETURNING id INTO v_r2;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id) VALUES
        ('v79-k1', a, 'v79-c-cumplido', 30000, now(), 'x', v_hoy, 'CREDIT', v_r),
        ('v79-k2', a, 'v79-c-error', 30000, now(), 'x', v_hoy, 'CREDIT', v_r2);
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) VALUES
        ('v79-nueva-cumplido', a, 'v79-c-cumplido', 20000, now() + interval '1 second', 'x', v_hoy, 'DEBIT', v_hoy + 8),
        ('v79-nueva-error', a, 'v79-c-error', 20000, now() + interval '1 second', 'x', v_hoy, 'DEBIT', v_hoy + 8);

    -- Acuerdo cumplido: el saldo solo cubre la venta nueva (20.000); la vieja sigue en 100.000 y quedan 10.000 a favor.
    v_aplicado := public.fn_aplicar_saldo_a_favor(a, 'v79-cumplido');
    IF v_aplicado <> 20000
       OR (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v79-vieja-cumplido') <> 100000
       OR (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v79-nueva-cumplido') <> 0 THEN
        RAISE EXCEPTION 'V79: con el acuerdo cumplido el saldo a favor se cruzo con lo anterior (aplicado %)', v_aplicado;
    END IF;
    -- Control, marcado por error: la regla normal entera (la vieja primero, 30.000).
    v_aplicado := public.fn_aplicar_saldo_a_favor(a, 'v79-error');
    IF v_aplicado <> 30000
       OR (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v79-vieja-error') <> 70000 THEN
        RAISE EXCEPTION 'V79: con el proceso marcado por error no volvio la regla normal (aplicado %)', v_aplicado;
    END IF;
    -- El suelo: una aplicación automática escrita directo sobre lo extinguido aborta.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
        VALUES (a, v_r, 'v79-k1', 'v79-vieja-cumplido', 1000, 'SALDO_A_FAVOR_AUTOMATICO');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V79: una aplicacion automatica cruzo lo extinguido por un acuerdo cumplido'; END IF;

    IF (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_aplicar_saldo_a_favor(text,text)'))
       OR (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_corte_de_acuerdo_cumplido(text,text)'))
       OR has_function_privilege('public', 'public.fn_corte_de_acuerdo_cumplido(text,text)', 'EXECUTE')
       OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()')) <> '0107b1c3bcd8711455abba4b82d4e985' THEN
        RAISE EXCEPTION 'V79: una funcion quedo DEFINER o con PUBLIC, o se toco fn_venta_a_credito';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.cartera_aplicaciones          WHERE tenant_id = a;
    DELETE FROM public.debt_transactions             WHERE tenant_id = a;
    DELETE FROM public.recibos_de_caja               WHERE tenant_id = a;
    DELETE FROM public.contadores_de_recibos         WHERE tenant_id = a;
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
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v79%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v79%') THEN
        RAISE EXCEPTION 'V79: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V79: acuerdo cumplido: el saldo a favor solo cubre lo nacido despues del corte; marcado por error: regla normal entera; el suelo rechaza lo extinguido; INVOKER; fn_venta_a_credito intacta; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- Restaurar fn_aplicar_saldo_a_favor con el cuerpo de V74 (md5 de prosrc 6bf5abf2421cd4dbeb10a0b74185232c);
-- DROP TRIGGER trg_saldo_a_favor_no_cruza_lo_extinguido ON public.cartera_aplicaciones;
-- DROP FUNCTION public.fn_saldo_a_favor_no_cruza_lo_extinguido(); DROP FUNCTION public.fn_corte_de_acuerdo_cumplido(TEXT, TEXT);
