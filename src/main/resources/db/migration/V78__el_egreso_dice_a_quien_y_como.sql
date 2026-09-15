-- =====================================================================
-- V78 -- El egreso dice a quién se entrega y cómo (plan de mayoristas F4.13, corte (d)).
--
-- Diseño: docs/planes/DISENO-F4-13-INSOLVENCIA-COMO-PROCESO.md §7 y §11.7; decisiones de ECM del 2026-09-15 (TEXTOS B4).
--
-- ── La regla ────────────────────────────────────────────────────────
--   · `egresos_de_cartera` gana `beneficiario` (DEUDOR | LIQUIDADOR; sin decirlo, DEUDOR, dentro y fuera de proceso: el
--     contrato de F4.12 no cambia), `beneficiario_nombre` y `beneficiario_documento` (obligatorios con LIQUIDADOR) y
--     `forma` (DINERO | MERCANCIA; DINERO por defecto).
--   · El LIQUIDADOR recibe solo con la etapa vigente en LIQUIDACION; en LIQUIDACION solo él. Lo comprueba un disparador.
--   · MERCANCIA es solo documental: renglones en `egresos_de_cartera_mercancia` (producto del negocio, cantidad, valor
--     pactado por el admin) cuya suma es el monto, comprobada al confirmar. No crea orden, ni DEBIT de venta, ni aplicación
--     a una factura, ni intención de inventario (F4.13c). Su `medio` es 'MERCANCIA' y el cierre de caja, que cuenta
--     EFECTIVO, no la ve.
--   · Una aplicación DEVOLUCION_DE_SALDO_A_FAVOR solo cubre el DEBIT de un egreso: así el saldo a favor no se puede
--     «devolver» descontándolo de una venta. Las demás reglas no hacen ninguna lectura más.
--
-- IMPACTO: cuatro columnas con valor por defecto constante (sin reescribir la tabla) y tres CHECK en egresos_de_cartera
-- (tabla pequeña: valida sus filas); una tabla nueva vacía; tres funciones de disparador. No toca fn_venta_a_credito ni
-- las vistas.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · A quién y cómo ───────────────────────────────────────────────
ALTER TABLE public.egresos_de_cartera
    ADD COLUMN beneficiario           TEXT NOT NULL DEFAULT 'DEUDOR',
    ADD COLUMN beneficiario_nombre    TEXT NULL,
    ADD COLUMN beneficiario_documento TEXT NULL,
    ADD COLUMN forma                  TEXT NOT NULL DEFAULT 'DINERO';
ALTER TABLE public.egresos_de_cartera
    ADD CONSTRAINT ck_egresos_beneficiario CHECK (beneficiario IN ('DEUDOR', 'LIQUIDADOR')),
    ADD CONSTRAINT ck_egresos_liquidador_identificado CHECK (beneficiario <> 'LIQUIDADOR'
        OR (length(btrim(COALESCE(beneficiario_nombre, ''))) > 0 AND length(btrim(COALESCE(beneficiario_documento, ''))) > 0)),
    ADD CONSTRAINT ck_egresos_forma CHECK (forma IN ('DINERO', 'MERCANCIA'));
ALTER TABLE public.egresos_de_cartera DROP CONSTRAINT ck_egresos_medio;
ALTER TABLE public.egresos_de_cartera
    ADD CONSTRAINT ck_egresos_medio CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'BRE_B', 'QR', 'TARJETA', 'CHEQUE', 'MERCANCIA')),
    ADD CONSTRAINT ck_egresos_medio_de_la_forma CHECK ((forma = 'MERCANCIA') = (medio = 'MERCANCIA'));
COMMENT ON COLUMN public.egresos_de_cartera.beneficiario IS
    'F4.13 (d): DEUDOR (el cliente, por defecto) o LIQUIDADOR (solo en liquidacion, con nombre y documento). V78.';
COMMENT ON COLUMN public.egresos_de_cartera.forma IS
    'F4.13 (d): DINERO o MERCANCIA (documental: renglones en egresos_de_cartera_mercancia, medio MERCANCIA). V78.';

-- ── 2 · La mercancía, solo documental ───────────────────────────────
CREATE TABLE public.egresos_de_cartera_mercancia (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       TEXT          NOT NULL,
    egreso_id       UUID          NOT NULL REFERENCES public.egresos_de_cartera(id),
    renglon         SMALLINT      NOT NULL,
    producto_id     VARCHAR(255)  NOT NULL,
    producto_nombre TEXT          NOT NULL,          -- el nombre de ese momento: el documento no cambia si el catálogo sí
    cantidad        NUMERIC(12,3) NOT NULL,
    valor_unitario  NUMERIC(15,2) NOT NULL,
    valor           NUMERIC(15,2) NOT NULL,
    CONSTRAINT pk_egresos_de_cartera_mercancia PRIMARY KEY (id),
    CONSTRAINT ux_egresos_mercancia_renglon UNIQUE (egreso_id, renglon),
    CONSTRAINT ck_egresos_mercancia_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_egresos_mercancia_valor CHECK (valor_unitario > 0 AND valor = round(cantidad * valor_unitario, 2))
);
COMMENT ON TABLE public.egresos_de_cartera_mercancia IS
    'F4.13 (d): la mercancia entregada como pago del saldo a favor. Documental: sin orden, sin venta, sin inventario. '
    'Solo anexa. V78.';

-- ── 3 · Lo que comprueba la base ────────────────────────────────────
-- El beneficiario según la etapa: el liquidador solo en liquidación, y en liquidación solo él.
CREATE FUNCTION public.fn_egreso_beneficiario_de_la_etapa()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_etapa TEXT;
    v_insolvente DATE;
BEGIN
    -- Sin la proyección de V75, el cliente no está en proceso: la vista no se lee.
    SELECT c.en_insolvencia_desde INTO v_insolvente FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL THEN
        SELECT vv.etapa INTO v_etapa FROM public.v_insolvencia_vigente vv
         WHERE vv.tenant_id = NEW.tenant_id AND vv.cliente_documento = NEW.cliente_documento AND vv.en_proceso;
    END IF;
    IF v_etapa IS NOT DISTINCT FROM 'LIQUIDACION' AND NEW.beneficiario <> 'LIQUIDADOR' THEN
        RAISE EXCEPTION 'En liquidacion el saldo a favor se entrega al liquidador, no al cliente. No se registro nada.' USING ERRCODE = 'P0001';
    END IF;
    IF v_etapa IS DISTINCT FROM 'LIQUIDACION' AND NEW.beneficiario = 'LIQUIDADOR' THEN
        RAISE EXCEPTION 'El liquidador solo recibe el saldo a favor cuando el proceso esta en liquidacion.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_egreso_beneficiario_de_la_etapa BEFORE INSERT ON public.egresos_de_cartera
    FOR EACH ROW EXECUTE FUNCTION public.fn_egreso_beneficiario_de_la_etapa();

-- Con MERCANCIA, los renglones suman el monto; con DINERO, no hay renglones. Al confirmar: el egreso va antes que sus renglones.
CREATE FUNCTION public.fn_egreso_mercancia_cuadra()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_suma NUMERIC;
    v_renglones INTEGER;
BEGIN
    SELECT COALESCE(sum(m.valor), 0), count(*) INTO v_suma, v_renglones
      FROM public.egresos_de_cartera_mercancia m WHERE m.egreso_id = NEW.id;
    IF NEW.forma = 'MERCANCIA' AND (v_renglones = 0 OR v_suma <> NEW.monto) THEN
        RAISE EXCEPTION 'Los productos suman % y la devolucion es de %: tienen que ser iguales.', v_suma, NEW.monto USING ERRCODE = 'P0001';
    END IF;
    IF NEW.forma = 'DINERO' AND v_renglones > 0 THEN
        RAISE EXCEPTION 'Una devolucion en dinero no lleva productos.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_egreso_mercancia_cuadra AFTER INSERT ON public.egresos_de_cartera
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.fn_egreso_mercancia_cuadra();

-- Un renglón es de un producto del negocio y de un egreso en mercancía del mismo negocio.
CREATE FUNCTION public.fn_renglon_de_mercancia_del_negocio()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e
                    WHERE e.id = NEW.egreso_id AND e.tenant_id = NEW.tenant_id AND e.forma = 'MERCANCIA') THEN
        RAISE EXCEPTION 'Ese renglon no es de una devolucion en mercancia del negocio.' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.menu_products p WHERE p.id_product = NEW.producto_id AND p.tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'Ese producto no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_renglon_de_mercancia_del_negocio BEFORE INSERT ON public.egresos_de_cartera_mercancia
    FOR EACH ROW EXECUTE FUNCTION public.fn_renglon_de_mercancia_del_negocio();

-- La devolución solo cubre el DEBIT de un egreso: el saldo a favor no se descuenta de una venta.
CREATE FUNCTION public.fn_devolucion_solo_cubre_un_egreso()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.regla <> 'DEVOLUCION_DE_SALDO_A_FAVOR' THEN
        RETURN NEW;                                     -- el abono y la aplicación automática: ninguna lectura más
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e
                    WHERE e.tenant_id = NEW.tenant_id AND e.debito_tx_id = NEW.debito_tx_id) THEN
        RAISE EXCEPTION 'Una devolucion de saldo a favor solo cubre su comprobante de egreso, no una venta. No se aplico nada.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_devolucion_solo_cubre_un_egreso BEFORE INSERT ON public.cartera_aplicaciones
    FOR EACH ROW EXECUTE FUNCTION public.fn_devolucion_solo_cubre_un_egreso();

-- ── 4 · Aislamiento y permisos ──────────────────────────────────────
DO $rls$
BEGIN
    ALTER TABLE public.egresos_de_cartera_mercancia ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.egresos_de_cartera_mercancia FORCE ROW LEVEL SECURITY;
    CREATE POLICY tenant_isolation_egresos_de_cartera_mercancia ON public.egresos_de_cartera_mercancia
        USING (tenant_id = current_setting('app.tenant_id', true))
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT ON public.egresos_de_cartera_mercancia TO app_user;       -- solo anexa
    END IF;
END
$rls$;

-- ── 5 · Cierre, por comportamiento ──────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v78_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_r UUID;
    v_e UUID;
    v_venta UUID := gen_random_uuid();
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V78 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v78@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active) VALUES ('__p78_aceite__', a, 'Aceite', 0, false);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v78-libre', 'Sin proceso', 8, 'v78'), (a, 'v78-liq', 'En liquidacion', 8, 'v78');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v78-c-libre', a, now(), 1000000, 'v78-libre', 'Sin proceso', 'ACTIVE', 0, now()),
        ('v78-c-liq', a, now(), 1000000, 'v78-liq', 'En liquidacion', 'ACTIVE', 0, now());
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);

    -- Sin proceso, 30.000 a favor (recibo sin facturas).
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v78-libre', 30000, 'EFECTIVO', now(), 'v78-r') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id)
    VALUES ('v78-k', a, 'v78-c-libre', 30000, now(), 'x', v_hoy, 'CREDIT', v_r);

    -- Una venta a crédito de 50.000 no se puede «pagar» con DEVOLUCION_DE_SALDO_A_FAVOR: no es un egreso.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES ('v78-venta', a, 'v78-c-libre', 50000, now(), 'x', v_hoy, 'DEBIT', v_hoy + 8);
    v_rechazado := false;
    BEGIN
        INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, usuario_id)
        VALUES (a, v_r, 'v78-k', 'v78-venta', 10000, 'DEVOLUCION_DE_SALDO_A_FAVOR', v_user);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V78: una devolucion de saldo a favor cubrio una venta'; END IF;

    -- En mercancía, cuadrada: 2 × 5.000 = 10.000. Medio MERCANCIA, DEUDOR por defecto, cubierta por DEVOLUCION.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type)
    VALUES ('v78-egreso', a, 'v78-c-libre', 10000, now(), 'Devolucion de saldo a favor', v_hoy, 'DEBIT');
    INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por, ocurrido_en, idempotency_key, forma)
    VALUES (a, 0, 'v78-libre', 10000, 'MERCANCIA', 'CLIENTE_LO_PIDIO', 'v78-egreso', v_user, now(), 'v78-e', 'MERCANCIA') RETURNING id INTO v_e;
    INSERT INTO public.egresos_de_cartera_mercancia (tenant_id, egreso_id, renglon, producto_id, producto_nombre, cantidad, valor_unitario, valor)
    VALUES (a, v_e, 1, '__p78_aceite__', 'Aceite', 2, 5000, 10000);
    INSERT INTO public.cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, usuario_id)
    VALUES (a, v_r, 'v78-k', 'v78-egreso', 10000, 'DEVOLUCION_DE_SALDO_A_FAVOR', v_user);
    SET CONSTRAINTS public.trg_egreso_mercancia_cuadra IMMEDIATE;
    SET CONSTRAINTS public.trg_egreso_mercancia_cuadra DEFERRED;
    IF (SELECT beneficiario FROM public.egresos_de_cartera WHERE id = v_e) <> 'DEUDOR'
       OR (SELECT saldo FROM public.v_cartera_por_documento WHERE tenant_id = a AND debito_tx_id = 'v78-venta') <> 50000
       OR (SELECT sum(saldo_a_favor) FROM public.v_saldo_a_favor_por_recibo WHERE tenant_id = a AND cliente_documento = 'v78-libre') <> 20000 THEN
        RAISE EXCEPTION 'V78: la devolucion en mercancia no quedo como DEUDOR, o toco la venta, o no desconto el saldo a favor';
    END IF;

    -- Mercancía que no cuadra: se detecta al confirmar (aquí, forzado inmediato).
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type)
    VALUES ('v78-egreso-2', a, 'v78-c-libre', 5000, now(), 'x', v_hoy, 'DEBIT');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por, ocurrido_en, idempotency_key, forma)
        VALUES (a, 0, 'v78-libre', 5000, 'MERCANCIA', 'CLIENTE_LO_PIDIO', 'v78-egreso-2', v_user, now(), 'v78-e2', 'MERCANCIA') RETURNING id INTO v_e;
        INSERT INTO public.egresos_de_cartera_mercancia (tenant_id, egreso_id, renglon, producto_id, producto_nombre, cantidad, valor_unitario, valor)
        VALUES (a, v_e, 1, '__p78_aceite__', 'Aceite', 1, 4000, 4000);
        SET CONSTRAINTS public.trg_egreso_mercancia_cuadra IMMEDIATE;
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    SET CONSTRAINTS public.trg_egreso_mercancia_cuadra DEFERRED;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V78: una devolucion en mercancia que no cuadra entro'; END IF;
    -- El medio sigue a la forma; un producto de otro negocio no entra.
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por, ocurrido_en, idempotency_key, forma)
        VALUES (a, 0, 'v78-libre', 5000, 'EFECTIVO', 'CLIENTE_LO_PIDIO', 'v78-egreso-2', v_user, now(), 'v78-e3', 'MERCANCIA');
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V78: una devolucion en mercancia con medio EFECTIVO entro'; END IF;

    -- Liquidación: al cliente no (409 en la aplicación), al liquidador sí; y el liquidador fuera de liquidación, no.
    PERFORM public.fn_insolvencia_informar_etapa('v78-liq', 'INICIO', v_hoy, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v78-liq', 'LIQUIDACION', v_hoy, 'Auto', NULL, 'abogado', NULL, NULL, NULL);
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type) VALUES
        ('v78-egreso-liq', a, 'v78-c-liq', 1000, now(), 'x', v_hoy, 'DEBIT'),
        ('v78-egreso-liq2', a, 'v78-c-liq', 1000, now(), 'x', v_hoy, 'DEBIT'),
        ('v78-egreso-libre', a, 'v78-c-libre', 1000, now(), 'x', v_hoy, 'DEBIT');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, referencia, debito_tx_id, pagado_por, ocurrido_en, idempotency_key)
        VALUES (a, 0, 'v78-liq', 1000, 'EFECTIVO', 'DEVUELTO_AL_PROCESO', 'Oficio', 'v78-egreso-liq', v_user, now(), 'v78-e-liq-deudor');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V78: en liquidacion el saldo a favor se entrego al cliente'; END IF;
    INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, referencia, debito_tx_id, pagado_por,
                                           ocurrido_en, idempotency_key, beneficiario, beneficiario_nombre, beneficiario_documento)
    VALUES (a, 0, 'v78-liq', 1000, 'TRANSFERENCIA', 'DEVUELTO_AL_PROCESO', 'Oficio', 'v78-egreso-liq2', v_user, now(), 'v78-e-liq',
            'LIQUIDADOR', 'Liquidadora S.A.S.', '900123456');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, debito_tx_id, pagado_por,
                                               ocurrido_en, idempotency_key, beneficiario, beneficiario_nombre, beneficiario_documento)
        VALUES (a, 0, 'v78-libre', 1000, 'EFECTIVO', 'CLIENTE_LO_PIDIO', 'v78-egreso-libre', v_user, now(), 'v78-e-libre-liq',
                'LIQUIDADOR', 'Liquidadora', '900');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V78: el liquidador recibio sin liquidacion'; END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.egresos_de_cartera_mercancia', 'UPDATE')
            OR has_table_privilege('app_user', 'public.egresos_de_cartera_mercancia', 'DELETE')) THEN
        RAISE EXCEPTION 'V78: app_user modifica o borra la mercancia de un egreso';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.cartera_aplicaciones          WHERE tenant_id = a;
    DELETE FROM public.egresos_de_cartera_mercancia  WHERE tenant_id = a;
    DELETE FROM public.egresos_de_cartera            WHERE tenant_id = a;
    DELETE FROM public.contadores_de_egresos         WHERE tenant_id = a;
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
    DELETE FROM public.menu_products                 WHERE tenant_id = a;
    DELETE FROM public.users                         WHERE tenant_id = a;
    DELETE FROM public.tenants                       WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v78%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v78%') THEN
        RAISE EXCEPTION 'V78: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V78: la devolucion no cubre una venta; mercancia cuadrada entra como DEUDOR sin tocar la venta; la que no cuadra no; medio sigue a la forma; en liquidacion solo el liquidador y el liquidador solo en liquidacion; solo anexa; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- (antes, decidir qué pasa con los egresos en mercancía y al liquidador: son dinero y mercancía que salieron)
-- DROP TRIGGER trg_devolucion_solo_cubre_un_egreso ON public.cartera_aplicaciones; DROP FUNCTION public.fn_devolucion_solo_cubre_un_egreso();
-- DROP TRIGGER trg_renglon_de_mercancia_del_negocio ON public.egresos_de_cartera_mercancia; DROP FUNCTION public.fn_renglon_de_mercancia_del_negocio();
-- DROP TRIGGER trg_egreso_mercancia_cuadra ON public.egresos_de_cartera; DROP FUNCTION public.fn_egreso_mercancia_cuadra();
-- DROP TRIGGER trg_egreso_beneficiario_de_la_etapa ON public.egresos_de_cartera; DROP FUNCTION public.fn_egreso_beneficiario_de_la_etapa();
-- DROP TABLE public.egresos_de_cartera_mercancia;
-- ALTER TABLE public.egresos_de_cartera DROP CONSTRAINT ck_egresos_medio_de_la_forma, DROP CONSTRAINT ck_egresos_forma,
--   DROP CONSTRAINT ck_egresos_liquidador_identificado, DROP CONSTRAINT ck_egresos_beneficiario, DROP COLUMN forma,
--   DROP COLUMN beneficiario_documento, DROP COLUMN beneficiario_nombre, DROP COLUMN beneficiario; ck_egresos_medio: el de V74.
