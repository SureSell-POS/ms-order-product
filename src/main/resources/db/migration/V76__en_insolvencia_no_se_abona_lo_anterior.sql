-- =====================================================================
-- V76 -- En insolvencia no se abona lo anterior al inicio (plan de mayoristas F4.13, corte (b)).
--
-- Diseño: docs/planes/DISENO-F4-13-INSOLVENCIA-COMO-PROCESO.md §5, APROBADO por ECM el 2026-09-15.
--
-- ── La regla ────────────────────────────────────────────────────────
--   Con el cliente en proceso (etapa vigente INICIO, ACUERDO_CONFIRMADO o LIQUIDACION, V75):
--   · Un abono NO se aplica a una factura ANTERIOR al corte: pagarla por fuera del proceso es ineficaz (Ley 1116 de 2006,
--     Ley 2445 de 2025). A las POSTERIORES sí (obligaciones nuevas). La aplicación aborta con P0001.
--   · El saldo a favor NO se cruza con nada (regla SALDO_A_FAVOR_AUTOMATICO): V74 ya devuelve 0 por la vía automática;
--     esto es el suelo por si otra vía lo intenta.
--   · DEVOLUCION_DE_SALDO_A_FAVOR (el DEBIT de un egreso, F4.12) sigue permitido.
--   La aplicación (Cartera.registrarRecibo) responde antes con 409 ABONO_A_DEUDA_ANTERIOR; esto es la base, como F4.11.
--
-- ── Costo ───────────────────────────────────────────────────────────
--   Un disparador BEFORE INSERT por aplicación (por evento: un abono son pocas). Cliente sin proceso (el 99 %): el recibo
--   por pk_recibos_de_caja y el cliente por ux_clientes_documento, y fuera. Solo con `en_insolvencia_desde` puesto lee v_insolvencia_clasificacion por
--   el DEBIT. Sin temporizadores.
--
-- IMPACTO: una función y un disparador en cartera_aplicaciones. No toca datos, vistas ni fn_venta_a_credito.
-- =====================================================================

SET lock_timeout = '3s';

CREATE FUNCTION public.fn_aplicacion_respeta_la_insolvencia()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_documento TEXT;
    v_inicio DATE;
BEGIN
    -- El 99 %: el cliente del recibo no está en proceso. El recibo por su clave y el cliente por (negocio, documento), dos
    -- búsquedas por índice separadas (juntas, el planificador elige recorrer los clientes del negocio), y fuera. La
    -- proyección de V75 (`en_insolvencia_desde`) solo tiene fecha con el proceso en INICIO, ACUERDO o LIQUIDACION.
    SELECT r.cliente_documento INTO v_documento FROM public.recibos_de_caja r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.recibo_id;
    SELECT c.en_insolvencia_desde INTO v_inicio FROM public.clientes c WHERE c.tenant_id = NEW.tenant_id AND c.documento = v_documento;
    IF v_inicio IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.regla IN ('MAS_ANTIGUA_PRIMERO', 'ELEGIDA_POR_USUARIO') THEN
        SELECT vv.inicio INTO v_inicio
          FROM public.v_insolvencia_clasificacion k
          JOIN public.v_insolvencia_vigente vv ON vv.tenant_id = k.tenant_id AND vv.proceso_id = k.proceso_id
         WHERE k.tenant_id = NEW.tenant_id AND k.debito_tx_id = NEW.debito_tx_id AND k.clasificacion = 'ANTERIOR';
        IF FOUND THEN
            RAISE EXCEPTION 'Esa factura es anterior al inicio del proceso de insolvencia (%): se reclama dentro del proceso. No se registro el abono.',
                v_inicio USING ERRCODE = 'P0001';
        END IF;
    ELSIF NEW.regla = 'SALDO_A_FAVOR_AUTOMATICO' THEN
        SELECT vv.inicio INTO v_inicio
          FROM public.recibos_de_caja r
          JOIN public.v_insolvencia_vigente vv
            ON vv.tenant_id = r.tenant_id AND vv.cliente_documento = r.cliente_documento AND vv.en_proceso
         WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.recibo_id;
        IF FOUND THEN
            RAISE EXCEPTION 'El cliente esta en proceso de insolvencia desde el %: su saldo a favor no se puede cruzar con la deuda. No se aplico nada.',
                v_inicio USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
COMMENT ON FUNCTION public.fn_aplicacion_respeta_la_insolvencia() IS
    'F4.13 (b): con el cliente en proceso de insolvencia, un abono no se aplica a una factura ANTERIOR al corte y el saldo a '
    'favor no se cruza. INVOKER. V76.';

CREATE TRIGGER trg_aplicacion_respeta_la_insolvencia BEFORE INSERT ON public.cartera_aplicaciones
    FOR EACH ROW EXECUTE FUNCTION public.fn_aplicacion_respeta_la_insolvencia();

-- ── Cierre, por comportamiento ──────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v76_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_r UUID; v_r2 UUID;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V76 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v76@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v76-ins', 'En proceso', 8, 'v76'), (a, 'v76-libre', 'Sin proceso', 8, 'v76');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v76-c-ins', a, now(), 1000000, 'v76-ins', 'En proceso', 'ACTIVE', 0, now()),
        ('v76-c-libre', a, now(), 1000000, 'v76-libre', 'Sin proceso', 'ACTIVE', 0, now());
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) VALUES
        ('v76-anterior', a, 'v76-c-ins', 100000, now(), 'x', v_hoy - 20, 'DEBIT', v_hoy - 12),
        ('v76-posterior', a, 'v76-c-ins', 50000, now(), 'x', v_hoy, 'DEBIT', v_hoy + 8),
        ('v76-libre-d', a, 'v76-c-libre', 70000, now(), 'x', v_hoy - 20, 'DEBIT', v_hoy - 12);
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);
    PERFORM public.fn_insolvencia_informar_etapa('v76-ins', 'INICIO', v_hoy - 5, 'Auto 76', NULL, 'abogado', NULL, NULL, NULL);

    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v76-ins', 60000, 'EFECTIVO', now(), 'v76-r-ins') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v76-k-ins', a, 'v76-c-ins', 60000, now(), 'x', v_hoy, 'CREDIT', v_r);

    -- A lo POSTERIOR, sí.
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r, 'v76-k-ins', 'v76-posterior', 50000, 'MAS_ANTIGUA_PRIMERO');
    -- A lo ANTERIOR, no: ni elegida ni la más antigua.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
        VALUES (a, v_r, 'v76-k-ins', 'v76-anterior', 10000, 'ELEGIDA_POR_USUARIO');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V76: un abono se aplico a una factura anterior al inicio'; END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
        VALUES (a, v_r, 'v76-k-ins', 'v76-anterior', 10000, 'MAS_ANTIGUA_PRIMERO');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V76: la mas antigua primero se aplico a lo anterior al inicio'; END IF;
    -- El saldo a favor no se cruza, ni con lo posterior.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
        VALUES (a, v_r, 'v76-k-ins', 'v76-posterior', 1, 'SALDO_A_FAVOR_AUTOMATICO');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V76: el saldo a favor de un cliente en proceso se cruzo'; END IF;

    -- Control: el cliente sin proceso abona su factura vieja como siempre.
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v76-libre', 70000, 'EFECTIVO', now(), 'v76-r-libre') RETURNING id INTO v_r2;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v76-k-libre', a, 'v76-c-libre', 70000, now(), 'x', v_hoy, 'CREDIT', v_r2);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r2, 'v76-k-libre', 'v76-libre-d', 70000, 'MAS_ANTIGUA_PRIMERO');
    -- Y el proceso cerrado devuelve la regla normal para lo nuevo (CUMPLIDO desde ACUERDO).
    PERFORM public.fn_insolvencia_informar_etapa('v76-ins', 'ACUERDO_CONFIRMADO', v_hoy - 1, 'Auto 77', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v76-ins', 'CUMPLIDO_TERMINADO', v_hoy, 'Auto 78', NULL, 'abogado', NULL, NULL, NULL);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
    VALUES (a, v_r, 'v76-k-ins', 'v76-anterior', 10000, 'ELEGIDA_POR_USUARIO');
    IF (SELECT count(*) FROM public.cartera_aplicaciones WHERE tenant_id = a) <> 3 THEN
        RAISE EXCEPTION 'V76: el control fuera de proceso no aplico lo esperado';
    END IF;

    IF (SELECT prosecdef FROM pg_proc WHERE oid = 'public.fn_aplicacion_respeta_la_insolvencia()'::regprocedure)
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_aplicacion_respeta_la_insolvencia'
                       AND tgrelid = 'public.cartera_aplicaciones'::regclass AND tgenabled = 'O') THEN
        RAISE EXCEPTION 'V76: la funcion no es INVOKER o el disparador no esta activo';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.cartera_aplicaciones      WHERE tenant_id = a;
    DELETE FROM public.insolvencia_foto_facturas WHERE tenant_id = a;
    DELETE FROM public.insolvencia_fotos         WHERE tenant_id = a;
    DELETE FROM public.insolvencia_etapas        WHERE tenant_id = a;
    DELETE FROM public.insolvencia_procesos      WHERE tenant_id = a;
    DELETE FROM public.debt_transactions         WHERE tenant_id = a;
    DELETE FROM public.recibos_de_caja           WHERE tenant_id = a;
    DELETE FROM public.contadores_de_recibos     WHERE tenant_id = a;
    DELETE FROM public.accounts_receivable       WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos          WHERE tenant_id = a;
    DELETE FROM public.clientes                  WHERE tenant_id = a;
    DELETE FROM public.users                     WHERE tenant_id = a;
    DELETE FROM public.tenants                   WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v76%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v76%') THEN
        RAISE EXCEPTION 'V76: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V76: % clientes con en_insolvencia_desde y sin proceso en curso (esperado 0; conservan el 409 viejo de cobros)',
        (SELECT count(*) FROM public.clientes c
          WHERE c.en_insolvencia_desde IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM public.v_insolvencia_vigente vv
                             WHERE vv.tenant_id = c.tenant_id AND vv.cliente_documento = c.documento AND vv.en_proceso));
    RAISE NOTICE 'V76: en proceso, abono a lo posterior si y a lo anterior no (elegida y mas antigua); saldo a favor sin cruce; control sin proceso y tras CUMPLIDO aplica; INVOKER; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TRIGGER trg_aplicacion_respeta_la_insolvencia ON public.cartera_aplicaciones;
-- DROP FUNCTION public.fn_aplicacion_respeta_la_insolvencia();
