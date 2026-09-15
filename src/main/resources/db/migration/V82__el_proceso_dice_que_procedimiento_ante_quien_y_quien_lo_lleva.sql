-- =====================================================================
-- V82 -- El proceso dice qué procedimiento es, ante quién y quién lo lleva (plan de mayoristas F4.13f).
--
-- Concepto IV del abogado (TEXTOS §E) y decisiones de ECM del 2026-09-15 (TEXTOS §B16); concepto V (CGP y notarías,
-- TEXTOS §B18, F4.13f+), que entra en esta misma migración antes de sellarla.
--
-- ── Lo que entra ────────────────────────────────────────────────────
--   · Por etapa: `procedimiento` (catálogo de B16 + OTRO_PROCEDIMIENTO con texto), `autoridad_tipo` (Superintendencia de
--     Sociedades · juez civil del circuito · juez civil municipal · centro de conciliación · notaría · OTRA con texto; B18)
--     y quién lleva el trámite (nombre y papel: PROMOTOR · CONCILIADOR · LIQUIDADOR). B18: con centro de conciliación o
--     notaría, el papel es CONCILIADOR y, desde INICIO (INICIO, ACUERDO_CONFIRMADO, CUMPLIDO_TERMINADO, LIQUIDACION), el nombre
--     es obligatorio: lo exigen la aplicación (400) y la base (CHECK). La columna `autoridad` (texto) se queda: el panel servido la
--     manda así (R10). Lo vigente del proceso es lo último informado, como el régimen: lo deriva la aplicación de las etapas.
--   · `fn_insolvencia_informar_etapa` gana esos seis parámetros con DEFAULT NULL: una llamada de nueve argumentos sigue
--     resolviendo. Guarda por md5 del cuerpo de V75 (65bb14998a89fbcc7fd284168023cfc7).
--   · Egreso en LIQUIDACION: el auto que designa al liquidador (número y fecha) es obligatorio, y el egreso guarda la etapa y
--     el régimen vigentes el día en que se hizo. Lo pone `fn_egreso_beneficiario_de_la_etapa` (V78), redefinida con guarda por
--     md5 (2f8fa4d3ae3180e0e20853267355d65b). Los egresos de antes quedan con etapa NULL (no se sabe; no se inventa).
--   · El número del proceso obligatorio en INICIO lo exige la aplicación (400 `numeroProceso`), no la base: lo migrado y el
--     endpoint viejo quedan «pendiente de informar», como el régimen.
--
-- IMPACTO: columnas nulas y CHECK en insolvencia_etapas y egresos_de_cartera (tablas pequeñas); dos funciones redefinidas.
-- Sin vistas nuevas ni cambios en las de V68–V80; no toca fn_venta_a_credito, fn_aplicar_saldo_a_favor ni fn_deuda_a_fecha.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guardas ───────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid)'))
       IS DISTINCT FROM '65bb14998a89fbcc7fd284168023cfc7' THEN
        RAISE EXCEPTION 'V82: fn_insolvencia_informar_etapa no es la de V75 (md5 %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid)'));
    END IF;
    IF (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_egreso_beneficiario_de_la_etapa()'))
       IS DISTINCT FROM '2f8fa4d3ae3180e0e20853267355d65b' THEN
        RAISE EXCEPTION 'V82: fn_egreso_beneficiario_de_la_etapa no es la de V78 (md5 %). No se cambia nada: hay que mirarlo antes',
            (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_egreso_beneficiario_de_la_etapa()'));
    END IF;
END $guarda$;

-- ── 1 · La etapa: procedimiento, autoridad y quién lleva el trámite ─
ALTER TABLE public.insolvencia_etapas
    ADD COLUMN procedimiento      TEXT NULL,
    ADD COLUMN procedimiento_otro TEXT NULL,
    ADD COLUMN autoridad_tipo     TEXT NULL,
    ADD COLUMN autoridad_otra     TEXT NULL,
    ADD COLUMN tramitador_nombre  TEXT NULL,
    ADD COLUMN tramitador_papel   TEXT NULL;
ALTER TABLE public.insolvencia_etapas
    ADD CONSTRAINT ck_insolvencia_etapas_procedimiento CHECK (procedimiento IS NULL OR procedimiento IN (
        'REORGANIZACION', 'REORGANIZACION_ABREVIADA', 'LIQUIDACION_JUDICIAL', 'LIQUIDACION_SIMPLIFICADA',
        'NEGOCIACION_DE_DEUDAS', 'CONVALIDACION_DE_ACUERDO_PRIVADO', 'LIQUIDACION_PATRIMONIAL', 'OTRO_PROCEDIMIENTO')),
    -- Sin NULL en la comparación: con NULL un CHECK pasa.
    ADD CONSTRAINT ck_insolvencia_etapas_procedimiento_otro CHECK (
        (COALESCE(procedimiento, '') = 'OTRO_PROCEDIMIENTO') = (COALESCE(length(btrim(procedimiento_otro)), 0) > 0)),
    ADD CONSTRAINT ck_insolvencia_etapas_autoridad_tipo CHECK (autoridad_tipo IS NULL OR autoridad_tipo IN (
        'SUPERINTENDENCIA_DE_SOCIEDADES', 'JUEZ_CIVIL_DEL_CIRCUITO', 'JUEZ_CIVIL_MUNICIPAL', 'CENTRO_DE_CONCILIACION', 'NOTARIA',
        'OTRA')),
    ADD CONSTRAINT ck_insolvencia_etapas_autoridad_otra CHECK (
        (COALESCE(autoridad_tipo, '') = 'OTRA') = (COALESCE(length(btrim(autoridad_otra)), 0) > 0)),
    ADD CONSTRAINT ck_insolvencia_etapas_tramitador CHECK ((tramitador_papel IS NULL) = (tramitador_nombre IS NULL)
        AND (tramitador_papel IS NULL OR tramitador_papel IN ('PROMOTOR', 'CONCILIADOR', 'LIQUIDADOR'))),
    -- B18: con centro de conciliación o notaría lo lleva el conciliador inscrito, y desde INICIO hay que nombrarlo.
    ADD CONSTRAINT ck_insolvencia_etapas_conciliador CHECK (
        COALESCE(autoridad_tipo, '') NOT IN ('CENTRO_DE_CONCILIACION', 'NOTARIA')
        OR (COALESCE(tramitador_papel, 'CONCILIADOR') = 'CONCILIADOR'
            AND (etapa NOT IN ('INICIO', 'ACUERDO_CONFIRMADO', 'CUMPLIDO_TERMINADO', 'LIQUIDACION') OR tramitador_nombre IS NOT NULL)));

-- ── 2 · La función de etapas, con los campos nuevos ─────────────────
DROP FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID);
CREATE FUNCTION public.fn_insolvencia_informar_etapa(p_documento_cliente TEXT, p_etapa TEXT, p_fecha DATE, p_documento TEXT,
                                                     p_autoridad TEXT, p_informado_por TEXT, p_regimen TEXT,
                                                     p_numero_proceso TEXT, p_corrige_etapa_id UUID,
                                                     p_procedimiento TEXT DEFAULT NULL, p_procedimiento_otro TEXT DEFAULT NULL,
                                                     p_autoridad_tipo TEXT DEFAULT NULL, p_autoridad_otra TEXT DEFAULT NULL,
                                                     p_tramitador_nombre TEXT DEFAULT NULL, p_tramitador_papel TEXT DEFAULT NULL)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_negocio TEXT := NULLIF(current_setting('app.tenant_id', true), '');
    v_usuario_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_usuario BIGINT;
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_vigente RECORD;
    v_proceso UUID;
    v_etapa_id UUID;
    v_secuencia INTEGER;
    v_etapa_corregida TEXT;
    v_corte TIMESTAMPTZ;
    v_foto UUID;
    v_foto_vieja UUID;
    v_inicio DATE;
BEGIN
    IF v_negocio IS NULL THEN
        RAISE EXCEPTION 'Insolvencia sin negocio en la sesion.' USING ERRCODE = 'P0001';
    END IF;
    IF v_usuario_txt IS NULL OR v_usuario_txt !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'Insolvencia sin usuario en la sesion: una etapa lleva autor.' USING ERRCODE = 'P0001';
    END IF;
    v_usuario := v_usuario_txt::BIGINT;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = v_usuario AND u.tenant_id = v_negocio) THEN
        RAISE EXCEPTION 'El usuario % no es de este negocio.', v_usuario USING ERRCODE = 'P0001';
    END IF;
    IF p_etapa IS NULL OR p_etapa NOT IN ('SOLICITUD', 'SOLICITUD_NO_ADMITIDA', 'INICIO', 'ACUERDO_CONFIRMADO', 'CUMPLIDO_TERMINADO',
                                          'LIQUIDACION', 'CORRECCION_DE_ERROR') THEN
        RAISE EXCEPTION 'La etapa % no existe.', p_etapa USING ERRCODE = 'P0001';
    END IF;
    IF p_fecha IS NULL OR p_fecha > v_hoy THEN
        RAISE EXCEPTION 'La fecha de la etapa es la del documento y no puede ser futura.' USING ERRCODE = 'P0001';
    END IF;
    IF p_regimen IS NOT NULL AND p_regimen NOT IN ('LEY_1116', 'CGP') THEN
        RAISE EXCEPTION 'El regimen es LEY_1116 o CGP.' USING ERRCODE = 'P0001';
    END IF;
    -- El cliente, bloqueado: dos etapas del mismo cliente no se cruzan.
    PERFORM 1 FROM public.clientes c WHERE c.tenant_id = v_negocio AND c.documento = p_documento_cliente FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Ese cliente no existe en el negocio.' USING ERRCODE = 'P0001';
    END IF;

    SELECT vv.proceso_id, vv.etapa INTO v_vigente FROM public.v_insolvencia_vigente vv
     WHERE vv.tenant_id = v_negocio AND vv.cliente_documento = p_documento_cliente AND vv.abierto
     ORDER BY vv.abierto_en DESC LIMIT 1;

    IF NOT FOUND THEN
        IF p_etapa NOT IN ('SOLICITUD', 'INICIO') THEN
            RAISE EXCEPTION 'Un proceso de insolvencia empieza con SOLICITUD o INICIO, no con %.', p_etapa USING ERRCODE = 'P0001';
        END IF;
        INSERT INTO public.insolvencia_procesos (tenant_id, cliente_documento, abierto_por)
        VALUES (v_negocio, p_documento_cliente, v_usuario) RETURNING id INTO v_proceso;
    ELSE
        v_proceso := v_vigente.proceso_id;
        IF p_etapa = 'CORRECCION_DE_ERROR' AND p_corrige_etapa_id IS NOT NULL THEN
            SELECT e.etapa INTO v_etapa_corregida FROM public.insolvencia_etapas e
             WHERE e.tenant_id = v_negocio AND e.proceso_id = v_proceso AND e.id = p_corrige_etapa_id
               AND e.etapa <> 'CORRECCION_DE_ERROR';
            IF NOT FOUND THEN
                RAISE EXCEPTION 'La etapa a corregir no es de este proceso.' USING ERRCODE = 'P0001';
            END IF;
        ELSIF p_etapa = 'CORRECCION_DE_ERROR' THEN
            IF v_vigente.etapa = 'LIQUIDACION' THEN
                RAISE EXCEPTION 'En liquidacion la insolvencia no se levanta.' USING ERRCODE = 'P0001';
            END IF;
        ELSIF p_etapa = 'CUMPLIDO_TERMINADO' AND v_vigente.etapa = 'LIQUIDACION' THEN
            RAISE EXCEPTION 'En liquidacion la insolvencia no se levanta.' USING ERRCODE = 'P0001';
        ELSIF NOT ((v_vigente.etapa = 'SOLICITUD' AND p_etapa IN ('INICIO', 'SOLICITUD_NO_ADMITIDA'))
                OR (v_vigente.etapa = 'INICIO' AND p_etapa IN ('ACUERDO_CONFIRMADO', 'LIQUIDACION'))
                OR (v_vigente.etapa = 'ACUERDO_CONFIRMADO' AND p_etapa IN ('CUMPLIDO_TERMINADO', 'LIQUIDACION'))) THEN
            RAISE EXCEPTION 'Un proceso en % no pasa a %.', v_vigente.etapa, p_etapa USING ERRCODE = 'P0001';
        END IF;
    END IF;

    SELECT COALESCE(max(e.secuencia), 0) + 1 INTO v_secuencia FROM public.insolvencia_etapas e WHERE e.proceso_id = v_proceso;
    -- V82 (F4.13f): procedimiento, autoridad del catálogo y quién lleva el trámite; los CHECK de la tabla los validan.
    INSERT INTO public.insolvencia_etapas (tenant_id, proceso_id, secuencia, etapa, fecha, documento, autoridad, informado_por,
                                           regimen, numero_proceso, corrige_etapa_id, registrado_por,
                                           procedimiento, procedimiento_otro, autoridad_tipo, autoridad_otra,
                                           tramitador_nombre, tramitador_papel)
    VALUES (v_negocio, v_proceso, v_secuencia, p_etapa, p_fecha, btrim(p_documento), NULLIF(btrim(p_autoridad), ''),
            btrim(p_informado_por), p_regimen, NULLIF(btrim(p_numero_proceso), ''), p_corrige_etapa_id, v_usuario,
            p_procedimiento, NULLIF(btrim(p_procedimiento_otro), ''), p_autoridad_tipo, NULLIF(btrim(p_autoridad_otra), ''),
            NULLIF(btrim(p_tramitador_nombre), ''), p_tramitador_papel)
    RETURNING id INTO v_etapa_id;

    -- La foto: al informar INICIO, o al corregir la fecha del INICIO (anexa otra que reemplaza la vieja).
    IF p_etapa = 'INICIO' OR v_etapa_corregida = 'INICIO' THEN
        v_corte := CASE WHEN p_fecha = v_hoy THEN now()
                        ELSE (p_fecha::timestamp AT TIME ZONE 'America/Bogota') END;
        SELECT fo.id INTO v_foto_vieja FROM public.insolvencia_fotos fo
         WHERE fo.tenant_id = v_negocio AND fo.proceso_id = v_proceso
           AND NOT EXISTS (SELECT 1 FROM public.insolvencia_fotos r WHERE r.reemplaza_foto_id = fo.id)
         ORDER BY fo.tomada_en DESC LIMIT 1;
        INSERT INTO public.insolvencia_fotos (tenant_id, proceso_id, etapa_id, corte, facturas, total, huella, reemplaza_foto_id, tomada_por)
        SELECT v_negocio, v_proceso, v_etapa_id, v_corte, count(*), COALESCE(sum(f.saldo_al_corte), 0),
               public.fn_huella_de_deuda(v_negocio, p_documento_cliente, v_corte), v_foto_vieja, v_usuario
          FROM public.fn_deuda_a_fecha(v_negocio, p_documento_cliente, v_corte) f
        RETURNING id INTO v_foto;
        INSERT INTO public.insolvencia_foto_facturas (foto_id, tenant_id, debito_tx_id, order_uuid, fecha, vence_el, monto, aplicado_al_corte, saldo_al_corte)
        SELECT v_foto, v_negocio, f.debito_tx_id, f.order_uuid, f.fecha, f.vence_el, f.monto, f.aplicado_al_corte, f.saldo_al_corte
          FROM public.fn_deuda_a_fecha(v_negocio, p_documento_cliente, v_corte) f;
    END IF;

    -- La proyección: la fecha de inicio si el proceso está en INICIO, ACUERDO o LIQUIDACION; si no, NULL.
    SELECT CASE WHEN vv.en_proceso THEN vv.inicio END INTO v_inicio
      FROM public.v_insolvencia_vigente vv WHERE vv.tenant_id = v_negocio AND vv.proceso_id = v_proceso;
    UPDATE public.clientes c SET en_insolvencia_desde = v_inicio, actualizado_en = now()
     WHERE c.tenant_id = v_negocio AND c.documento = p_documento_cliente
       AND c.en_insolvencia_desde IS DISTINCT FROM v_inicio;
    RETURN v_etapa_id;
END $$;
COMMENT ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) IS
    'F4.13: informa una etapa del proceso de insolvencia de un cliente del negocio de la sesion. Catalogo, autor, foto al '
    'INICIO y proyeccion en clientes.en_insolvencia_desde. En liquidacion no se levanta. V75; V82: procedimiento, autoridad '
    'del catalogo y quien lleva el tramite.';

-- ── 3 · El egreso: auto de designación y la etapa de ese día ────────
ALTER TABLE public.egresos_de_cartera
    ADD COLUMN auto_designacion_numero TEXT NULL,
    ADD COLUMN auto_designacion_fecha  DATE NULL,
    ADD COLUMN etapa_al_devolver       TEXT NULL,
    ADD COLUMN regimen_al_devolver     TEXT NULL;
ALTER TABLE public.egresos_de_cartera
    ADD CONSTRAINT ck_egresos_auto_designacion CHECK ((auto_designacion_numero IS NULL) = (auto_designacion_fecha IS NULL)
        AND (auto_designacion_fecha IS NULL OR auto_designacion_fecha <= (registrado_en AT TIME ZONE 'America/Bogota')::date));
CREATE OR REPLACE FUNCTION public.fn_egreso_beneficiario_de_la_etapa()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_etapa TEXT;
    v_regimen TEXT;
    v_insolvente DATE;
BEGIN
    -- Sin la proyección de V75, el cliente no está en proceso: la vista no se lee.
    SELECT c.en_insolvencia_desde INTO v_insolvente FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id AND c.documento = NEW.cliente_documento;
    IF v_insolvente IS NOT NULL THEN
        SELECT vv.etapa, vv.regimen INTO v_etapa, v_regimen FROM public.v_insolvencia_vigente vv
         WHERE vv.tenant_id = NEW.tenant_id AND vv.cliente_documento = NEW.cliente_documento AND vv.en_proceso;
    END IF;
    IF v_etapa IS NOT DISTINCT FROM 'LIQUIDACION' AND NEW.beneficiario <> 'LIQUIDADOR' THEN
        RAISE EXCEPTION 'En liquidacion el saldo a favor se entrega al liquidador, no al cliente. No se registro nada.' USING ERRCODE = 'P0001';
    END IF;
    IF v_etapa IS DISTINCT FROM 'LIQUIDACION' AND NEW.beneficiario = 'LIQUIDADOR' THEN
        RAISE EXCEPTION 'El liquidador solo recibe el saldo a favor cuando el proceso esta en liquidacion.' USING ERRCODE = 'P0001';
    END IF;
    -- V82 (F4.13f): en liquidación, el auto que designa al liquidador; y la etapa y el régimen de ese día quedan en el egreso.
    IF v_etapa = 'LIQUIDACION' AND (NULLIF(btrim(NEW.auto_designacion_numero), '') IS NULL OR NEW.auto_designacion_fecha IS NULL) THEN
        RAISE EXCEPTION 'Falta el auto que designa al liquidador. No se registro nada.' USING ERRCODE = 'P0001';
    END IF;
    NEW.etapa_al_devolver := v_etapa;
    NEW.regimen_al_devolver := v_regimen;
    RETURN NEW;
END $$;

DO $permisos$
BEGIN
    REVOKE ALL ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT EXECUTE ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO app_user;
    END IF;
END
$permisos$;

-- ── 4 · Cierre, por comportamiento ──────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v82_a__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT;
    v_e UUID;
    v_r UUID;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V82 A', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v82@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    -- El autor antes de sembrar (sin WARNING «clientes_eventos sin autor»).
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES (a, 'v82-c', 'Cliente', 8, 'v82');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
    VALUES ('v82-cuenta', a, now(), 1000000, 'v82-c', 'Cliente', 'ACTIVE', 0, now());

    -- Una llamada de nueve argumentos sigue resolviendo (lo que ya existía).
    PERFORM public.fn_insolvencia_informar_etapa('v82-c', 'SOLICITUD', v_hoy, 'Radicado', NULL, 'cliente', 'LEY_1116', NULL, NULL);
    -- Con los campos nuevos.
    v_e := public.fn_insolvencia_informar_etapa('v82-c', 'INICIO', v_hoy, 'Auto 81', NULL, 'abogado', NULL, '2026-081', NULL,
                                                'REORGANIZACION_ABREVIADA', NULL, 'JUEZ_CIVIL_DEL_CIRCUITO', NULL, 'Ana Promotora', 'PROMOTOR');
    IF NOT EXISTS (SELECT 1 FROM public.insolvencia_etapas WHERE id = v_e AND procedimiento = 'REORGANIZACION_ABREVIADA'
                     AND autoridad_tipo = 'JUEZ_CIVIL_DEL_CIRCUITO' AND tramitador_papel = 'PROMOTOR' AND tramitador_nombre = 'Ana Promotora') THEN
        RAISE EXCEPTION 'V82: la etapa no guardo procedimiento, autoridad o quien lleva el tramite';
    END IF;
    -- Catálogos: OTRO sin texto, OTRA sin texto, papel sin nombre y valores fuera, rechazados.
    FOR t IN SELECT * FROM (VALUES ('OTRO_PROCEDIMIENTO', NULL, NULL, NULL, NULL), ('NOTARIAL', NULL, NULL, NULL, NULL),
                                   (NULL, 'OTRA', NULL, NULL, NULL), (NULL, 'JUZGADO', NULL, NULL, NULL),
                                   (NULL, 'NOTARIA', NULL, NULL, NULL), (NULL, 'CENTRO_DE_CONCILIACION', 'Pedro', 'PROMOTOR', NULL),
                                   (NULL, NULL, NULL, 'PROMOTOR', NULL), (NULL, NULL, 'Luis', 'SINDICO', NULL),
                                   (NULL, NULL, NULL, NULL, 'con-otro')) x(pr, au, tn, tp, z) LOOP
        v_rechazado := false;
        BEGIN
            PERFORM public.fn_insolvencia_informar_etapa('v82-c', 'ACUERDO_CONFIRMADO', v_hoy, 'Auto', NULL, 'abogado', NULL, NULL, NULL,
                                                         t.pr, t.z, t.au, t.z, t.tn, t.tp);
        EXCEPTION WHEN check_violation THEN v_rechazado := true;
        END;
        IF NOT v_rechazado THEN RAISE EXCEPTION 'V82: entro una etapa con catalogo invalido (%, %, %, %)', t.pr, t.au, t.tn, t.tp; END IF;
    END LOOP;
    -- B18, lo que sí entra: notaría con su conciliador y juez civil municipal. Se deshace con su propio error.
    BEGIN
        v_e := public.fn_insolvencia_informar_etapa('v82-c', 'ACUERDO_CONFIRMADO', v_hoy, 'Acta 7', NULL, 'abogado', 'CGP', NULL, NULL,
                                                    'CONVALIDACION_DE_ACUERDO_PRIVADO', NULL, 'NOTARIA', NULL, 'Rosa Conciliadora', 'CONCILIADOR');
        IF NOT EXISTS (SELECT 1 FROM public.insolvencia_etapas WHERE id = v_e AND autoridad_tipo = 'NOTARIA'
                         AND tramitador_papel = 'CONCILIADOR' AND tramitador_nombre = 'Rosa Conciliadora') THEN
            RAISE EXCEPTION 'V82: la notaria con su conciliador no se guardo';
        END IF;
        RAISE EXCEPTION 'deshacer' USING ERRCODE = 'P0V82';
    EXCEPTION WHEN SQLSTATE 'P0V82' THEN NULL;
    END;
    BEGIN
        v_e := public.fn_insolvencia_informar_etapa('v82-c', 'ACUERDO_CONFIRMADO', v_hoy, 'Auto 9', NULL, 'abogado', 'CGP', NULL, NULL,
                                                    'LIQUIDACION_PATRIMONIAL', NULL, 'JUEZ_CIVIL_MUNICIPAL', NULL, NULL, NULL);
        RAISE EXCEPTION 'deshacer' USING ERRCODE = 'P0V82';
    EXCEPTION WHEN SQLSTATE 'P0V82' THEN NULL;
    END;

    -- Liquidación: devolver sin el auto de designación no entra; con él, guarda la etapa y el régimen de ese día.
    PERFORM public.fn_insolvencia_informar_etapa('v82-c', 'LIQUIDACION', v_hoy, 'Auto liq', NULL, 'abogado', NULL, NULL, NULL);
    INSERT INTO public.recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
    VALUES (a, 0, 'v82-c', 5000, 'EFECTIVO', now(), 'v82-r') RETURNING id INTO v_r;
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id) VALUES
        ('v82-k', a, 'v82-cuenta', 5000, now(), 'x', v_hoy, 'CREDIT', v_r);
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type) VALUES
        ('v82-e1', a, 'v82-cuenta', 1000, now(), 'x', v_hoy, 'DEBIT'), ('v82-e2', a, 'v82-cuenta', 1000, now(), 'x', v_hoy, 'DEBIT');
    v_rechazado := false;
    BEGIN
        INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, referencia, debito_tx_id, pagado_por,
                                               ocurrido_en, idempotency_key, beneficiario, beneficiario_nombre, beneficiario_documento)
        VALUES (a, 0, 'v82-c', 1000, 'TRANSFERENCIA', 'DEVUELTO_AL_PROCESO', 'Oficio', 'v82-e1', v_user, now(), 'v82-sin-auto',
                'LIQUIDADOR', 'Liquidadora', '900');
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V82: en liquidacion se devolvio sin el auto que designa al liquidador'; END IF;
    INSERT INTO public.egresos_de_cartera (tenant_id, numero, cliente_documento, monto, medio, motivo, referencia, debito_tx_id, pagado_por,
                                           ocurrido_en, idempotency_key, beneficiario, beneficiario_nombre, beneficiario_documento,
                                           auto_designacion_numero, auto_designacion_fecha)
    VALUES (a, 0, 'v82-c', 1000, 'TRANSFERENCIA', 'DEVUELTO_AL_PROCESO', 'Oficio', 'v82-e2', v_user, now(), 'v82-con-auto',
            'LIQUIDADOR', 'Liquidadora', '900', 'Auto 555', v_hoy - 1);
    IF NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera WHERE tenant_id = a AND idempotency_key = 'v82-con-auto'
                     AND etapa_al_devolver = 'LIQUIDACION' AND regimen_al_devolver = 'LEY_1116') THEN
        RAISE EXCEPTION 'V82: el egreso no guardo la etapa y el regimen de ese dia';
    END IF;

    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid,text,text,text,text,text,text)'))
       OR has_function_privilege('public', 'public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid,text,text,text,text,text,text)', 'EXECUTE')
       OR to_regprocedure('public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid)') IS NOT NULL
       OR (SELECT md5(prosrc) FROM pg_proc WHERE oid = to_regprocedure('public.fn_venta_a_credito()')) <> '0107b1c3bcd8711455abba4b82d4e985' THEN
        RAISE EXCEPTION 'V82: la funcion de etapas no es DEFINER sin PUBLIC, quedo la firma vieja, o se toco fn_venta_a_credito';
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
    DELETE FROM public.cartera_aplicaciones_revertidas WHERE tenant_id = a;
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
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v82%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v82%') THEN
        RAISE EXCEPTION 'V82: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V82: etapa con procedimiento, autoridad y quien lleva el tramite; llamada de nueve argumentos resuelve; catalogos rechazan; en liquidacion sin auto no se devuelve y con auto guarda etapa y regimen; firma vieja fuera; fn_venta_a_credito intacta; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- fn_insolvencia_informar_etapa: DROP la de 15 parámetros y restaurar la de V75 (md5 65bb1499…) con sus permisos;
-- fn_egreso_beneficiario_de_la_etapa: restaurar la de V78 (md5 2f8fa4d3…);
-- ALTER TABLE public.egresos_de_cartera DROP CONSTRAINT ck_egresos_auto_designacion, DROP COLUMN regimen_al_devolver,
--   DROP COLUMN etapa_al_devolver, DROP COLUMN auto_designacion_fecha, DROP COLUMN auto_designacion_numero;
-- ALTER TABLE public.insolvencia_etapas DROP CONSTRAINT … (los cinco CHECK), DROP COLUMN … (las seis columnas);
