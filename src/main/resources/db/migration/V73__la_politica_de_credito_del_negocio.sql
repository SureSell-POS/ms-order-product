-- =====================================================================
-- V73 -- La política de crédito del negocio: avisar, o retener el pedido de
--        un cliente con una factura vencida hace más de N días.
--
-- Plan de mayoristas F5.4 (S21; política decidida por ECM el 2026-09-15: por
-- negocio, no por sede).
--
-- `politica_de_credito`: una fila por negocio. Sin fila = AVISAR con 0 días
-- (el valor por defecto; no se siembra nada). La evalúa la aplicación al crear
-- el pedido (Pedidos.crear), nunca un planificador.
--   · AVISAR:          el pedido sigue su camino; la cartera se ve en el detalle.
--   · RETENER_PEDIDO:  si el cliente tiene una factura con saldo vencida hace más
--                      de `dias_mora_para_retener` días, el pedido pasa a RETENIDO
--                      con motivo FACTURA_VENCIDA en la misma transacción.
--
-- Solo la cambia el admin (la aplicación lo exige) y con autor obligatorio en la
-- fila (`actualizado_por` NOT NULL). El rastro lo escribe un disparador en
-- `politica_de_credito_eventos`, que solo anexa: el valor anterior, el nuevo,
-- quién y cuándo. En el disparador y no en la aplicación, para que ningún camino
-- cambie la política sin dejarlo.
--
-- IMPACTO: dos tablas nuevas vacías y un disparador sobre una de ellas.
-- =====================================================================

SET lock_timeout = '3s';

CREATE TABLE IF NOT EXISTS public.politica_de_credito (
    tenant_id              TEXT        NOT NULL REFERENCES public.tenants(id),
    politica               TEXT        NOT NULL DEFAULT 'AVISAR',
    dias_mora_para_retener INTEGER     NOT NULL DEFAULT 0,
    actualizado_por        BIGINT      NOT NULL REFERENCES public.users(id),
    actualizado_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_politica_de_credito PRIMARY KEY (tenant_id),
    CONSTRAINT ck_politica_de_credito CHECK (politica IN ('AVISAR', 'RETENER_PEDIDO')),
    CONSTRAINT ck_politica_de_credito_dias CHECK (dias_mora_para_retener BETWEEN 0 AND 365)
);
COMMENT ON TABLE public.politica_de_credito IS
    'Politica de credito del negocio (F5.4): AVISAR o RETENER_PEDIDO con dias de mora. Sin fila = AVISAR, 0. '
    'Se evalua al crear el pedido. Rastro en politica_de_credito_eventos. V73.';

CREATE TABLE IF NOT EXISTS public.politica_de_credito_eventos (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           TEXT        NOT NULL,
    politica_anterior   TEXT        NULL,
    politica_nueva      TEXT        NOT NULL,
    dias_anterior       INTEGER     NULL,
    dias_nuevo          INTEGER     NOT NULL,
    usuario_id          BIGINT      NOT NULL REFERENCES public.users(id),
    ocurrido_en         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_politica_de_credito_eventos PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS ix_politica_de_credito_eventos
    ON public.politica_de_credito_eventos (tenant_id, ocurrido_en DESC);
COMMENT ON TABLE public.politica_de_credito_eventos IS
    'Cada cambio de la politica de credito: antes, despues, autor y cuando. Solo anexa; la escribe '
    'trg_politica_de_credito_eventos. V73.';

CREATE OR REPLACE FUNCTION public.fn_politica_de_credito_eventos()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.politica = NEW.politica
       AND OLD.dias_mora_para_retener = NEW.dias_mora_para_retener THEN
        RETURN NULL;
    END IF;
    INSERT INTO public.politica_de_credito_eventos
        (tenant_id, politica_anterior, politica_nueva, dias_anterior, dias_nuevo, usuario_id)
    VALUES (NEW.tenant_id,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.politica END, NEW.politica,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.dias_mora_para_retener END, NEW.dias_mora_para_retener,
            NEW.actualizado_por);
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_politica_de_credito_eventos ON public.politica_de_credito;
CREATE TRIGGER trg_politica_de_credito_eventos AFTER INSERT OR UPDATE ON public.politica_de_credito
    FOR EACH ROW EXECUTE FUNCTION public.fn_politica_de_credito_eventos();

ALTER TABLE public.politica_de_credito ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.politica_de_credito FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_politica_de_credito ON public.politica_de_credito;
CREATE POLICY tenant_isolation_politica_de_credito ON public.politica_de_credito
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE public.politica_de_credito_eventos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.politica_de_credito_eventos FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_politica_de_credito_eventos ON public.politica_de_credito_eventos;
CREATE POLICY tenant_isolation_politica_de_credito_eventos ON public.politica_de_credito_eventos
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        -- La política se crea y se cambia, nunca se borra. El rastro solo anexa.
        GRANT SELECT, INSERT, UPDATE ON public.politica_de_credito TO app_user;
        GRANT SELECT, INSERT ON public.politica_de_credito_eventos TO app_user;
    END IF;
END
$$;

-- ── Cierre, por comportamiento ────────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v73_a__';
    v_user BIGINT;
    v_otro BIGINT;
    v_ev   RECORD;
    v_rechazado BOOLEAN;
    t RECORD; v_filas BIGINT; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V73', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v73@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v73b@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_otro;

    -- 1. Nace con los valores por defecto y deja su evento con autor.
    INSERT INTO public.politica_de_credito (tenant_id, actualizado_por) VALUES (a, v_user);
    SELECT * INTO v_ev FROM public.politica_de_credito_eventos WHERE tenant_id = a;
    IF (SELECT politica FROM public.politica_de_credito WHERE tenant_id = a) <> 'AVISAR'
       OR (SELECT dias_mora_para_retener FROM public.politica_de_credito WHERE tenant_id = a) <> 0
       OR v_ev.politica_anterior IS NOT NULL OR v_ev.politica_nueva <> 'AVISAR' OR v_ev.usuario_id <> v_user THEN
        RAISE EXCEPTION 'V73: la politica no nacio AVISAR/0 con su evento y autor';
    END IF;

    -- 2. Un cambio deja antes y después con el autor nuevo; tocar sin cambiar no escribe.
    UPDATE public.politica_de_credito SET politica = 'RETENER_PEDIDO', dias_mora_para_retener = 15,
           actualizado_por = v_otro, actualizado_en = now() WHERE tenant_id = a;
    UPDATE public.politica_de_credito SET actualizado_en = now() WHERE tenant_id = a;
    IF (SELECT count(*) FROM public.politica_de_credito_eventos WHERE tenant_id = a) <> 2
       OR NOT EXISTS (SELECT 1 FROM public.politica_de_credito_eventos
                       WHERE tenant_id = a AND politica_anterior = 'AVISAR' AND politica_nueva = 'RETENER_PEDIDO'
                         AND dias_anterior = 0 AND dias_nuevo = 15 AND usuario_id = v_otro) THEN
        RAISE EXCEPTION 'V73: el cambio no dejo exactamente un evento con antes, despues y autor';
    END IF;

    -- 3. Fuera del catálogo, días negativos y sin autor: rechazados.
    v_rechazado := false;
    BEGIN
        UPDATE public.politica_de_credito SET politica = 'BLOQUEAR' WHERE tenant_id = a;
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V73: una politica fuera del catalogo entro'; END IF;
    v_rechazado := false;
    BEGIN
        UPDATE public.politica_de_credito SET dias_mora_para_retener = -1 WHERE tenant_id = a;
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V73: dias de mora negativos entraron'; END IF;
    v_rechazado := false;
    BEGIN
        UPDATE public.politica_de_credito SET actualizado_por = NULL WHERE tenant_id = a;
    EXCEPTION WHEN not_null_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V73: la politica se cambio sin autor'; END IF;

    -- 4. Permisos: la política no se borra y el rastro solo anexa.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user')
       AND (has_table_privilege('app_user', 'public.politica_de_credito', 'DELETE')
            OR has_table_privilege('app_user', 'public.politica_de_credito_eventos', 'UPDATE')
            OR has_table_privilege('app_user', 'public.politica_de_credito_eventos', 'DELETE')) THEN
        RAISE EXCEPTION 'V73: app_user puede borrar la politica o reescribir su rastro';
    END IF;

    DELETE FROM public.politica_de_credito_eventos WHERE tenant_id = a;
    DELETE FROM public.politica_de_credito         WHERE tenant_id = a;
    DELETE FROM public.users                       WHERE tenant_id = a;
    DELETE FROM public.tenants                     WHERE id = a;

    FOR t IN SELECT col.table_schema s, col.table_name n
               FROM information_schema.columns col
               JOIN information_schema.tables x
                 ON x.table_schema = col.table_schema AND x.table_name = col.table_name AND x.table_type = 'BASE TABLE'
              WHERE col.column_name = 'tenant_id' AND col.table_schema IN ('public', 'inventario', 'pedidos')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text LIKE %L', t.s, t.n, '\_\_prueba\_v73%') INTO v_filas;
        IF v_filas > 0 THEN
            v_quedan := v_quedan + v_filas;
            v_donde := v_donde || format(' %s.%s=%s', t.s, t.n, v_filas);
        END IF;
    END LOOP;
    IF v_quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id LIKE '\_\_prueba\_v73%') THEN
        RAISE EXCEPTION 'V73: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;

    RAISE NOTICE 'V73: nace AVISAR/0 con evento; el cambio deja antes, despues y autor; catalogo, dias y autor exigidos; no se borra; 0 restos';
END $cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TABLE public.politica_de_credito_eventos; DROP TABLE public.politica_de_credito;
-- DROP FUNCTION public.fn_politica_de_credito_eventos();
