-- =====================================================================
-- V75 -- La insolvencia es un proceso, no una fecha (plan de mayoristas F4.13, corte (a)).
--
-- Diseño: docs/planes/DISENO-F4-13-INSOLVENCIA-COMO-PROCESO.md, APROBADO por ECM el 2026-09-15 con las decisiones del §11.
--
-- ── Qué entra en este corte (a) ────────────────────────────────────────
--   · `insolvencia_procesos` e `insolvencia_etapas`: SOLO ANEXAN. Cada etapa con la fecha del documento, el documento,
--     la autoridad, quién la informó, el régimen si se informa (lo elige el deudor: nunca se calcula) y su autor.
--   · `insolvencia_fotos` e `insolvencia_foto_facturas`: la deuda del cliente al CORTE, factura por factura, con una
--     huella md5. Inmutable: una corrección de la fecha de inicio ANEXA una foto nueva que reemplaza a la vieja.
--   · `v_insolvencia_vigente`: la etapa vigente, derivada. `v_insolvencia_clasificacion`: ANTERIOR o POSTERIOR por
--     obligación, con la misma línea que la foto. Nada de estado guardado salvo la foto.
--   · `fn_deuda_a_fecha`: la deuda a un instante con SOLO hechos anteriores a él (reproducible: recalcular == foto).
--   · `fn_insolvencia_informar_etapa`: la ÚNICA forma de escribir una etapa (SECURITY DEFINER, negocio de la SESIÓN).
--     Valida el catálogo, toma la foto al informar INICIO y escribe la PROYECCIÓN `clientes.en_insolvencia_desde`.
--   · Los clientes que ya tenían la fecha pasan a un proceso con INICIO «Migrado sin documento» y su foto.
--   Los bloqueos (§5), el crédito posterior (§6) y el egreso con beneficiario (§7) son los cortes (b), (c) y (d).
--
-- ── El corte (§11.2, opción A, PROVISIONAL hasta el concepto del abogado) ──
--   INICIO registrado el MISMO día del auto → el instante del registro. INICIO RETROACTIVO → el final del día anterior
--   a la fecha del auto. Una obligación es ANTERIOR si nació antes del corte: un DEBIT de un día anterior al del corte,
--   o del mismo día con su venta (`orders.ocurrido_en`, o `created_at`) antes del instante. Foto y clasificación usan
--   la misma línea.
--
-- ── La proyección ──────────────────────────────────────────────────────
--   `clientes.en_insolvencia_desde` = fecha de inicio del proceso abierto en INICIO, ACUERDO_CONFIRMADO o LIQUIDACION;
--   NULL en SOLICITUD (entre solicitud e inicio no se bloquea nada) y al cerrarse. La escribe solo la función: así
--   `fn_venta_a_credito` (V74), `fn_aplicar_saldo_a_favor` (V74) y los lectores de la aplicación no cambian.
--
-- IMPACTO: cuatro tablas nuevas, dos vistas, tres funciones. Datos: un proceso por cliente con fecha (staging: 0 el
-- 2026-09-15). No toca `debt_transactions`, las vistas de V68 ni `fn_venta_a_credito`.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · El proceso y sus etapas ─────────────────────────────────────
CREATE TABLE public.insolvencia_procesos (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         TEXT        NOT NULL,
    cliente_documento TEXT        NOT NULL,
    abierto_por       BIGINT      NULL REFERENCES public.users(id),     -- NULL solo en lo migrado
    abierto_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_insolvencia_procesos PRIMARY KEY (id),
    CONSTRAINT ux_insolvencia_procesos_negocio UNIQUE (tenant_id, id)
);
CREATE INDEX ix_insolvencia_procesos_cliente ON public.insolvencia_procesos (tenant_id, cliente_documento, abierto_en);
COMMENT ON TABLE public.insolvencia_procesos IS
    'F4.13: un proceso de insolvencia de un cliente (Ley 1116 o CGP). Solo anexa. Etapas en insolvencia_etapas. V75.';

CREATE TABLE public.insolvencia_etapas (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        TEXT        NOT NULL,
    proceso_id       UUID        NOT NULL,
    secuencia        INTEGER     NOT NULL,
    etapa            TEXT        NOT NULL,
    fecha            DATE        NOT NULL,          -- la del documento (auto, acta), no la de registro
    documento        TEXT        NOT NULL,          -- «Auto N.º …»; en CORRECCION_DE_ERROR, el motivo
    autoridad        TEXT        NULL,
    informado_por    TEXT        NOT NULL,          -- quién lo trajo: cliente, abogado, promotor
    regimen          TEXT        NULL,              -- informado cuando se sabe; el vigente es el último no nulo
    numero_proceso   TEXT        NULL,
    corrige_etapa_id UUID        NULL,              -- CORRECCION_DE_ERROR que corrige la FECHA de esa etapa
    registrado_por   BIGINT      NULL REFERENCES public.users(id),   -- NULL solo en lo migrado
    registrado_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_insolvencia_etapas PRIMARY KEY (id),
    CONSTRAINT fk_insolvencia_etapas_proceso FOREIGN KEY (tenant_id, proceso_id) REFERENCES public.insolvencia_procesos (tenant_id, id),
    CONSTRAINT ux_insolvencia_etapas_secuencia UNIQUE (proceso_id, secuencia),
    CONSTRAINT ck_insolvencia_etapas_etapa CHECK (etapa IN ('SOLICITUD', 'SOLICITUD_NO_ADMITIDA', 'INICIO', 'ACUERDO_CONFIRMADO',
                                                             'CUMPLIDO_TERMINADO', 'LIQUIDACION', 'CORRECCION_DE_ERROR')),
    CONSTRAINT ck_insolvencia_etapas_regimen CHECK (regimen IS NULL OR regimen IN ('LEY_1116', 'CGP')),
    CONSTRAINT ck_insolvencia_etapas_documento CHECK (length(btrim(documento)) > 0 AND length(btrim(informado_por)) > 0),
    CONSTRAINT ck_insolvencia_etapas_corrige CHECK (corrige_etapa_id IS NULL OR etapa = 'CORRECCION_DE_ERROR')
);
CREATE INDEX ix_insolvencia_etapas_proceso ON public.insolvencia_etapas (tenant_id, proceso_id, secuencia);
COMMENT ON TABLE public.insolvencia_etapas IS
    'F4.13: etapas de un proceso de insolvencia, solo anexa. La escribe solo fn_insolvencia_informar_etapa. V75.';

-- ── 2 · La foto de la deuda al corte ────────────────────────────────
CREATE TABLE public.insolvencia_fotos (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         TEXT          NOT NULL,
    proceso_id        UUID          NOT NULL,
    etapa_id          UUID          NOT NULL REFERENCES public.insolvencia_etapas(id),   -- el INICIO o la corrección de su fecha
    corte             TIMESTAMPTZ   NOT NULL,
    facturas          INTEGER       NOT NULL,
    total             NUMERIC(15,2) NOT NULL,
    huella            TEXT          NOT NULL,
    reemplaza_foto_id UUID          NULL REFERENCES public.insolvencia_fotos(id),
    tomada_por        BIGINT        NULL REFERENCES public.users(id),
    tomada_en         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_insolvencia_fotos PRIMARY KEY (id),
    CONSTRAINT fk_insolvencia_fotos_proceso FOREIGN KEY (tenant_id, proceso_id) REFERENCES public.insolvencia_procesos (tenant_id, id),
    CONSTRAINT ux_insolvencia_fotos_reemplazo UNIQUE (reemplaza_foto_id)
);
CREATE INDEX ix_insolvencia_fotos_proceso ON public.insolvencia_fotos (tenant_id, proceso_id, tomada_en);
COMMENT ON TABLE public.insolvencia_fotos IS
    'F4.13: la deuda del cliente al corte del inicio (lo que se presenta al proceso). Inmutable; una corrección anexa otra. V75.';

CREATE TABLE public.insolvencia_foto_facturas (
    foto_id           UUID          NOT NULL REFERENCES public.insolvencia_fotos(id),
    tenant_id         TEXT          NOT NULL,
    debito_tx_id      VARCHAR(36)   NOT NULL,
    order_uuid        UUID          NULL,
    fecha             DATE          NOT NULL,
    vence_el          DATE          NULL,
    monto             NUMERIC(15,2) NOT NULL,
    aplicado_al_corte NUMERIC(15,2) NOT NULL,
    saldo_al_corte    NUMERIC(15,2) NOT NULL,
    CONSTRAINT pk_insolvencia_foto_facturas PRIMARY KEY (foto_id, debito_tx_id)
);
COMMENT ON TABLE public.insolvencia_foto_facturas IS 'F4.13: factura por factura de una foto de insolvencia. Inmutable. V75.';

-- ── 3 · La deuda a un instante: solo hechos anteriores a él ─────────
CREATE FUNCTION public.fn_deuda_a_fecha(p_tenant TEXT, p_documento TEXT, p_corte TIMESTAMPTZ)
RETURNS TABLE (debito_tx_id VARCHAR(36), order_uuid UUID, fecha DATE, vence_el DATE, monto NUMERIC, aplicado_al_corte NUMERIC, saldo_al_corte NUMERIC)
LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount,
           COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0),
           d.amount - COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0)
      FROM public.debt_transactions d
      JOIN public.accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
      LEFT JOIN public.orders o ON o.uuid_id = d.order_uuid AND o.tenant_id = d.tenant_id
      LEFT JOIN public.cartera_aplicaciones a
             ON a.tenant_id = d.tenant_id AND a.debito_tx_id = d.id AND a.ocurrido_en < p_corte
      LEFT JOIN public.recibos_de_caja x
             ON x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id AND x.ocurrido_en < p_corte
     WHERE d.tenant_id = p_tenant AND ar.customer_document = p_documento
       AND d.type = 'DEBIT' AND d.recibo_id IS NULL
       AND (d.transaction_date < (p_corte AT TIME ZONE 'America/Bogota')::date
            OR (d.transaction_date = (p_corte AT TIME ZONE 'America/Bogota')::date
                AND COALESCE(o.ocurrido_en, d.created_at) < p_corte))
       AND NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e WHERE e.tenant_id = d.tenant_id AND e.debito_tx_id = d.id)
     GROUP BY d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount
    HAVING d.amount - COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0) > 0
     ORDER BY d.transaction_date, d.id
$$;
COMMENT ON FUNCTION public.fn_deuda_a_fecha(TEXT, TEXT, TIMESTAMPTZ) IS
    'F4.13: facturas con saldo del cliente en un instante, contando solo DEBIT, aplicaciones y anulaciones anteriores a el. '
    'Lo nacido el mismo dia cuenta por su hora. Reproducible. INVOKER. V75.';

-- La huella de una foto: md5 de sus filas en orden. La misma expresión en la función y en las pruebas.
CREATE FUNCTION public.fn_huella_de_deuda(p_tenant TEXT, p_documento TEXT, p_corte TIMESTAMPTZ)
RETURNS TEXT LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT md5(COALESCE(string_agg(f.debito_tx_id || '|' || f.monto || '|' || f.aplicado_al_corte || '|' || f.saldo_al_corte, ';'
                                   ORDER BY f.fecha, f.debito_tx_id), ''))
      FROM public.fn_deuda_a_fecha(p_tenant, p_documento, p_corte) f
$$;

-- ── 4 · Lo vigente, derivado ─────────────────────────────────────────
CREATE VIEW public.v_insolvencia_vigente WITH (security_invoker = true) AS
WITH etapas AS (
    SELECT e.*,
           -- La fecha efectiva de una etapa: la de su última corrección, o la suya.
           COALESCE((SELECT c.fecha FROM public.insolvencia_etapas c
                      WHERE c.tenant_id = e.tenant_id AND c.corrige_etapa_id = e.id
                      ORDER BY c.secuencia DESC LIMIT 1), e.fecha) AS fecha_efectiva
      FROM public.insolvencia_etapas e
), ultima AS (
    -- La etapa vigente: la de mayor secuencia que no sea una corrección de fecha.
    SELECT DISTINCT ON (et.tenant_id, et.proceso_id) et.*
      FROM etapas et
     WHERE NOT (et.etapa = 'CORRECCION_DE_ERROR' AND et.corrige_etapa_id IS NOT NULL)
     ORDER BY et.tenant_id, et.proceso_id, et.secuencia DESC
)
SELECT p.tenant_id, p.id AS proceso_id, p.cliente_documento, p.abierto_en,
       u.etapa, u.id AS etapa_id, u.fecha_efectiva AS fecha_etapa,
       u.etapa IN ('SOLICITUD', 'INICIO', 'ACUERDO_CONFIRMADO', 'LIQUIDACION') AS abierto,
       u.etapa IN ('INICIO', 'ACUERDO_CONFIRMADO', 'LIQUIDACION') AS en_proceso,
       (SELECT et.fecha_efectiva FROM etapas et WHERE et.tenant_id = p.tenant_id AND et.proceso_id = p.id AND et.etapa = 'INICIO'
         ORDER BY et.secuencia DESC LIMIT 1) AS inicio,
       (SELECT et.regimen FROM etapas et WHERE et.tenant_id = p.tenant_id AND et.proceso_id = p.id AND et.regimen IS NOT NULL
         ORDER BY et.secuencia DESC LIMIT 1) AS regimen,
       (SELECT et.numero_proceso FROM etapas et WHERE et.tenant_id = p.tenant_id AND et.proceso_id = p.id AND et.numero_proceso IS NOT NULL
         ORDER BY et.secuencia DESC LIMIT 1) AS numero_proceso,
       f.id AS foto_id, f.corte, f.total AS foto_total, f.facturas AS foto_facturas, f.huella AS foto_huella
  FROM public.insolvencia_procesos p
  JOIN ultima u ON u.tenant_id = p.tenant_id AND u.proceso_id = p.id
  LEFT JOIN LATERAL (SELECT fo.* FROM public.insolvencia_fotos fo
                      WHERE fo.tenant_id = p.tenant_id AND fo.proceso_id = p.id
                        AND NOT EXISTS (SELECT 1 FROM public.insolvencia_fotos r WHERE r.reemplaza_foto_id = fo.id)
                      ORDER BY fo.tomada_en DESC LIMIT 1) f ON true;
COMMENT ON VIEW public.v_insolvencia_vigente IS
    'F4.13: por proceso, la etapa vigente, si esta abierto y en proceso (INICIO, ACUERDO, LIQUIDACION), la fecha de inicio, '
    'regimen y numero vigentes y la foto vigente. Derivada. V75.';

-- La clasificación de cada obligación del cliente en proceso: ANTERIOR si nació antes del corte vigente, POSTERIOR si no.
-- La misma línea que fn_deuda_a_fecha (§11.2): un día anterior al del corte, o el mismo día con la venta antes del instante.
CREATE VIEW public.v_insolvencia_clasificacion WITH (security_invoker = true) AS
SELECT d.tenant_id, d.id AS debito_tx_id, vv.cliente_documento, vv.proceso_id,
       CASE WHEN d.transaction_date < (vv.corte AT TIME ZONE 'America/Bogota')::date
              OR (d.transaction_date = (vv.corte AT TIME ZONE 'America/Bogota')::date
                  AND COALESCE(o.ocurrido_en, d.created_at) < vv.corte)
            THEN 'ANTERIOR' ELSE 'POSTERIOR' END AS clasificacion
  FROM public.v_insolvencia_vigente vv
  JOIN public.accounts_receivable ar ON ar.tenant_id = vv.tenant_id AND ar.customer_document = vv.cliente_documento
  JOIN public.debt_transactions d ON d.tenant_id = ar.tenant_id AND d.account_id = ar.id AND d.type = 'DEBIT' AND d.recibo_id IS NULL
  LEFT JOIN public.orders o ON o.tenant_id = d.tenant_id AND o.uuid_id = d.order_uuid
 WHERE vv.en_proceso AND vv.corte IS NOT NULL
   -- F4.12: el DEBIT de una devolución de saldo a favor no es una obligación del cliente.
   AND NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e WHERE e.tenant_id = d.tenant_id AND e.debito_tx_id = d.id);
COMMENT ON VIEW public.v_insolvencia_clasificacion IS
    'F4.13: ANTERIOR o POSTERIOR al corte del proceso vigente, por DEBIT de venta del cliente en proceso. Derivada. V75.';

-- ── 5 · La única forma de informar una etapa ────────────────────────
CREATE FUNCTION public.fn_insolvencia_informar_etapa(p_documento_cliente TEXT, p_etapa TEXT, p_fecha DATE, p_documento TEXT,
                                                     p_autoridad TEXT, p_informado_por TEXT, p_regimen TEXT,
                                                     p_numero_proceso TEXT, p_corrige_etapa_id UUID)
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
    INSERT INTO public.insolvencia_etapas (tenant_id, proceso_id, secuencia, etapa, fecha, documento, autoridad, informado_por,
                                           regimen, numero_proceso, corrige_etapa_id, registrado_por)
    VALUES (v_negocio, v_proceso, v_secuencia, p_etapa, p_fecha, btrim(p_documento), NULLIF(btrim(p_autoridad), ''),
            btrim(p_informado_por), p_regimen, NULLIF(btrim(p_numero_proceso), ''), p_corrige_etapa_id, v_usuario)
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
COMMENT ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID) IS
    'F4.13: informa una etapa del proceso de insolvencia de un cliente del negocio de la sesion. Catalogo, autor, foto al '
    'INICIO y proyeccion en clientes.en_insolvencia_desde. En liquidacion no se levanta. V75.';

-- ── 6 · Aislamiento y permisos ──────────────────────────────────────
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['insolvencia_procesos', 'insolvencia_etapas', 'insolvencia_fotos', 'insolvencia_foto_facturas'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON public.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
    REVOKE ALL ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID) FROM PUBLIC;
    REVOKE ALL ON FUNCTION public.fn_deuda_a_fecha(TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
    REVOKE ALL ON FUNCTION public.fn_huella_de_deuda(TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.insolvencia_procesos, public.insolvencia_etapas, public.insolvencia_fotos,
                        public.insolvencia_foto_facturas, public.v_insolvencia_vigente,
                        public.v_insolvencia_clasificacion TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID) TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_deuda_a_fecha(TEXT, TEXT, TIMESTAMPTZ) TO app_user;
        GRANT EXECUTE ON FUNCTION public.fn_huella_de_deuda(TEXT, TEXT, TIMESTAMPTZ) TO app_user;
    END IF;
END
$rls$;

-- ── 7 · Lo que ya existía: la fecha pasa a un proceso ───────────────
DO $migrar$
DECLARE
    c RECORD;
    v_proceso UUID;
    v_etapa UUID;
    v_corte TIMESTAMPTZ;
    v_foto UUID;
    v_n INTEGER := 0;
    v_futuras INTEGER := 0;
BEGIN
    FOR c IN SELECT cl.tenant_id, cl.documento, cl.en_insolvencia_desde FROM public.clientes cl
              WHERE cl.en_insolvencia_desde IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM public.insolvencia_procesos p WHERE p.tenant_id = cl.tenant_id AND p.cliente_documento = cl.documento)
    LOOP
        INSERT INTO public.insolvencia_procesos (tenant_id, cliente_documento, abierto_por) VALUES (c.tenant_id, c.documento, NULL)
        RETURNING id INTO v_proceso;
        INSERT INTO public.insolvencia_etapas (tenant_id, proceso_id, secuencia, etapa, fecha, documento, informado_por, registrado_por)
        VALUES (c.tenant_id, v_proceso, 1, 'INICIO', c.en_insolvencia_desde, 'Migrado sin documento (V75)', 'marca anterior', NULL)
        RETURNING id INTO v_etapa;
        v_corte := LEAST(now(), c.en_insolvencia_desde::timestamp AT TIME ZONE 'America/Bogota');
        INSERT INTO public.insolvencia_fotos (tenant_id, proceso_id, etapa_id, corte, facturas, total, huella, tomada_por)
        SELECT c.tenant_id, v_proceso, v_etapa, v_corte, count(*), COALESCE(sum(f.saldo_al_corte), 0),
               public.fn_huella_de_deuda(c.tenant_id, c.documento, v_corte), NULL
          FROM public.fn_deuda_a_fecha(c.tenant_id, c.documento, v_corte) f
        RETURNING id INTO v_foto;
        INSERT INTO public.insolvencia_foto_facturas (foto_id, tenant_id, debito_tx_id, order_uuid, fecha, vence_el, monto, aplicado_al_corte, saldo_al_corte)
        SELECT v_foto, c.tenant_id, f.debito_tx_id, f.order_uuid, f.fecha, f.vence_el, f.monto, f.aplicado_al_corte, f.saldo_al_corte
          FROM public.fn_deuda_a_fecha(c.tenant_id, c.documento, v_corte) f;
        v_n := v_n + 1;
        IF c.en_insolvencia_desde > (now() AT TIME ZONE 'America/Bogota')::date THEN v_futuras := v_futuras + 1; END IF;
    END LOOP;
    -- Una fecha futura (el endpoint viejo la guardaba) migra igual, con la foto al momento de migrar: se cuenta aparte.
    RAISE NOTICE 'V75: % clientes con fecha de insolvencia pasados a un proceso con INICIO migrado y su foto (% con fecha futura)', v_n, v_futuras;
END
$migrar$;

-- ── 8 · Cierre, por comportamiento ──────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v75_a__';
    b CONSTANT TEXT := '__prueba_v75_b__';
    v_hoy DATE := (now() AT TIME ZONE 'America/Bogota')::date;
    v_user BIGINT; v_user_b BIGINT;
    v_e UUID; v_inicio UUID; v_huella TEXT; v_foto RECORD; v_n INTEGER;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V75 A', 'basico'), (b, 'Prueba V75 B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status) VALUES ('v75@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.users (email, password_hash, tenant_id, role, status) VALUES ('v75b@prueba.invalid', '!', b, 'admin', 'disabled') RETURNING id INTO v_user_b;
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v75-uno', 'Uno', 8, 'v75'), (a, 'v75-dos', 'Dos', 8, 'v75'), (a, 'v75-liq', 'Liquidacion', 8, 'v75');
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) VALUES
        ('v75-c-uno', a, now(), 1000000, 'v75-uno', 'Uno', 'ACTIVE', 0, now());
    -- Dos facturas viejas y una abonada en parte antes del corte.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) VALUES
        ('v75-d1', a, 'v75-c-uno', 100000, now(), 'x', v_hoy - 40, 'DEBIT', v_hoy - 32),
        ('v75-d2', a, 'v75-c-uno', 50000, now(), 'x', v_hoy - 20, 'DEBIT', v_hoy - 12);
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);

    -- SOLICITUD: abre, no proyecta (no bloquea nada).
    PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'SOLICITUD', v_hoy - 10, 'Radicado 1', NULL, 'el cliente', NULL, NULL, NULL);
    IF (SELECT en_insolvencia_desde FROM public.clientes WHERE tenant_id = a AND documento = 'v75-uno') IS NOT NULL THEN
        RAISE EXCEPTION 'V75: una SOLICITUD puso la fecha de insolvencia';
    END IF;
    -- INICIO retroactivo: proyecta la fecha del auto y toma la foto al final del día anterior.
    v_inicio := public.fn_insolvencia_informar_etapa('v75-uno', 'INICIO', v_hoy - 5, 'Auto 123', 'Supersociedades', 'abogado', 'LEY_1116', '2026-001', NULL);
    SELECT * INTO v_foto FROM public.insolvencia_fotos WHERE tenant_id = a AND etapa_id = v_inicio;
    IF (SELECT en_insolvencia_desde FROM public.clientes WHERE tenant_id = a AND documento = 'v75-uno') <> v_hoy - 5
       OR v_foto.total <> 150000 OR v_foto.facturas <> 2
       OR v_foto.corte <> ((v_hoy - 5)::timestamp AT TIME ZONE 'America/Bogota') THEN
        RAISE EXCEPTION 'V75: el INICIO no proyecto su fecha o la foto no es la deuda al corte (total %, facturas %)', v_foto.total, v_foto.facturas;
    END IF;
    -- Movimientos después del corte: una factura nueva. La foto no cambia y se reproduce.
    INSERT INTO public.debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el)
    VALUES ('v75-d3', a, 'v75-c-uno', 30000, now(), 'x', v_hoy, 'DEBIT', v_hoy + 8);
    IF public.fn_huella_de_deuda(a, 'v75-uno', v_foto.corte) <> v_foto.huella
       OR (SELECT count(*) FROM public.insolvencia_foto_facturas WHERE foto_id = v_foto.id) <> 2 THEN
        RAISE EXCEPTION 'V75: la foto no se reproduce despues de un movimiento posterior';
    END IF;
    -- Catálogo: INICIO → CUMPLIDO no; INICIO → ACUERDO sí.
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'CUMPLIDO_TERMINADO', v_hoy, 'Acta', NULL, 'abogado', NULL, NULL, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V75: INICIO paso a CUMPLIDO_TERMINADO sin acuerdo'; END IF;
    -- Corregir la fecha del INICIO: foto nueva que reemplaza la vieja; proyección a la fecha nueva.
    PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'CORRECCION_DE_ERROR', v_hoy - 30, 'La fecha del auto era otra', NULL, 'abogado', NULL, NULL, v_inicio);
    IF (SELECT en_insolvencia_desde FROM public.clientes WHERE tenant_id = a AND documento = 'v75-uno') <> v_hoy - 30
       OR (SELECT count(*) FROM public.insolvencia_fotos WHERE tenant_id = a AND reemplaza_foto_id = v_foto.id) <> 1
       OR (SELECT foto_total FROM public.v_insolvencia_vigente WHERE tenant_id = a AND cliente_documento = 'v75-uno') <> 100000 THEN
        RAISE EXCEPTION 'V75: corregir la fecha del INICIO no rehizo la foto (esperado 100000, solo la factura de hace 40 dias)';
    END IF;
    -- Clasificación con la misma línea que la foto: lo de la foto es ANTERIOR; lo nacido después, POSTERIOR.
    IF (SELECT string_agg(debito_tx_id || '=' || clasificacion, ',' ORDER BY debito_tx_id) FROM public.v_insolvencia_clasificacion
         WHERE tenant_id = a AND cliente_documento = 'v75-uno') IS DISTINCT FROM 'v75-d1=ANTERIOR,v75-d2=POSTERIOR,v75-d3=POSTERIOR'
       OR EXISTS (SELECT 1 FROM public.insolvencia_foto_facturas ff
                   JOIN public.insolvencia_fotos fo ON fo.id = ff.foto_id
                   JOIN public.v_insolvencia_vigente vv ON vv.foto_id = fo.id
                   LEFT JOIN public.v_insolvencia_clasificacion k ON k.tenant_id = ff.tenant_id AND k.debito_tx_id = ff.debito_tx_id
                  WHERE ff.tenant_id = a AND k.clasificacion IS DISTINCT FROM 'ANTERIOR') THEN
        RAISE EXCEPTION 'V75: la clasificacion no usa la misma linea que la foto';
    END IF;
    PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'ACUERDO_CONFIRMADO', v_hoy - 1, 'Auto 456', NULL, 'abogado', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'CUMPLIDO_TERMINADO', v_hoy, 'Auto 789', NULL, 'abogado', NULL, NULL, NULL);
    IF (SELECT en_insolvencia_desde FROM public.clientes WHERE tenant_id = a AND documento = 'v75-uno') IS NOT NULL
       OR EXISTS (SELECT 1 FROM public.v_insolvencia_vigente WHERE tenant_id = a AND cliente_documento = 'v75-uno' AND abierto) THEN
        RAISE EXCEPTION 'V75: CUMPLIDO_TERMINADO no cerro el proceso ni quito la proyeccion';
    END IF;
    -- Liquidación: no se levanta ni por corrección ni por cumplido.
    PERFORM public.fn_insolvencia_informar_etapa('v75-liq', 'INICIO', v_hoy, 'Auto 1', NULL, 'cliente', NULL, NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v75-liq', 'LIQUIDACION', v_hoy, 'Auto 2', NULL, 'cliente', NULL, NULL, NULL);
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_informar_etapa('v75-liq', 'CORRECCION_DE_ERROR', v_hoy, 'Se marco por error', NULL, 'admin', NULL, NULL, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado OR (SELECT en_insolvencia_desde FROM public.clientes WHERE tenant_id = a AND documento = 'v75-liq') IS NULL THEN
        RAISE EXCEPTION 'V75: una liquidacion se levanto';
    END IF;
    -- SOLICITUD_NO_ADMITIDA es terminal.
    PERFORM public.fn_insolvencia_informar_etapa('v75-dos', 'SOLICITUD', v_hoy - 3, 'Radicado 2', NULL, 'cliente', 'CGP', NULL, NULL);
    PERFORM public.fn_insolvencia_informar_etapa('v75-dos', 'SOLICITUD_NO_ADMITIDA', v_hoy, 'Auto de rechazo', NULL, 'cliente', NULL, NULL, NULL);
    IF EXISTS (SELECT 1 FROM public.v_insolvencia_vigente WHERE tenant_id = a AND cliente_documento = 'v75-dos' AND abierto) THEN
        RAISE EXCEPTION 'V75: SOLICITUD_NO_ADMITIDA no cerro el proceso';
    END IF;
    -- Otro negocio: su sesión no ve el cliente.
    PERFORM set_config('app.tenant_id', b, true);
    PERFORM set_config('app.user_id', v_user_b::text, true);
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_informar_etapa('v75-uno', 'INICIO', v_hoy, 'Auto', NULL, 'x', NULL, NULL, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V75: otro negocio informo una etapa de un cliente ajeno'; END IF;
    -- Una fecha futura no entra.
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);
    v_rechazado := false;
    BEGIN
        PERFORM public.fn_insolvencia_informar_etapa('v75-dos', 'INICIO', v_hoy + 1, 'Auto', NULL, 'x', NULL, NULL, NULL);
    EXCEPTION WHEN raise_exception THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V75: una etapa con fecha futura entro'; END IF;

    -- Permisos: solo anexa; DEFINER con search_path fijo; vista con security_invoker.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') AND EXISTS (
           SELECT 1 FROM unnest(ARRAY['insolvencia_procesos', 'insolvencia_etapas', 'insolvencia_fotos', 'insolvencia_foto_facturas']) tb
            WHERE has_table_privilege('app_user', 'public.' || tb, 'INSERT') OR has_table_privilege('app_user', 'public.' || tb, 'UPDATE')
               OR has_table_privilege('app_user', 'public.' || tb, 'DELETE')) THEN
        RAISE EXCEPTION 'V75: app_user escribe en las tablas de insolvencia sin la funcion';
    END IF;
    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = to_regprocedure('public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid)'))
       OR has_function_privilege('public', 'public.fn_insolvencia_informar_etapa(text,text,date,text,text,text,text,text,uuid)', 'EXECUTE')
       OR NOT COALESCE((SELECT reloptions @> ARRAY['security_invoker=true'] FROM pg_class WHERE oid = 'public.v_insolvencia_vigente'::regclass), false)
       OR NOT COALESCE((SELECT reloptions @> ARRAY['security_invoker=true'] FROM pg_class WHERE oid = 'public.v_insolvencia_clasificacion'::regclass), false) THEN
        RAISE EXCEPTION 'V75: la funcion de etapas no es DEFINER sin PUBLIC, o la vista no tiene security_invoker';
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM public.insolvencia_foto_facturas WHERE tenant_id IN (a, b);
    DELETE FROM public.insolvencia_fotos         WHERE tenant_id IN (a, b);
    DELETE FROM public.insolvencia_etapas        WHERE tenant_id IN (a, b);
    DELETE FROM public.insolvencia_procesos      WHERE tenant_id IN (a, b);
    DELETE FROM public.debt_transactions         WHERE tenant_id IN (a, b);
    DELETE FROM public.accounts_receivable       WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes_eventos          WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes                  WHERE tenant_id IN (a, b);
    DELETE FROM public.users                     WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants                   WHERE id IN (a, b);

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v75%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v75%') THEN
        RAISE EXCEPTION 'V75: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V75: SOLICITUD sin proyeccion; INICIO retroactivo con foto al corte que se reproduce; correccion de fecha con foto nueva; catalogo; CUMPLIDO cierra; liquidacion no se levanta; NO_ADMITIDA terminal; otro negocio y fecha futura rechazados; solo anexa; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- (antes, decidir qué pasa con las fotos: son lo que se presentó a un proceso)
-- DROP FUNCTION public.fn_insolvencia_informar_etapa(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT, TEXT, UUID);
-- DROP VIEW public.v_insolvencia_clasificacion; DROP VIEW public.v_insolvencia_vigente; DROP FUNCTION public.fn_huella_de_deuda(TEXT, TEXT, TIMESTAMPTZ);
-- DROP FUNCTION public.fn_deuda_a_fecha(TEXT, TEXT, TIMESTAMPTZ);
-- DROP TABLE public.insolvencia_foto_facturas, public.insolvencia_fotos, public.insolvencia_etapas, public.insolvencia_procesos;
-- (clientes.en_insolvencia_desde conserva su valor: la columna no se toca)
