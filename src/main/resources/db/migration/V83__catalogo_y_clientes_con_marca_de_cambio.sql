-- =====================================================================
-- V83 -- Catálogo y clientes con marca de cambio (plan de mayoristas F6.0b).
--
-- Diseño: docs/planes/DISENO-F6-RUTA.md §10, decisiones de ECM del 2026-09-15 (D-b1..D-b4 y sus condiciones).
-- El paquete de la ruta (F6.2) baja catálogo y clientes con `desde`, con la regla de F1.8b (servidoEn = now() - 60 s).
-- La marca la pone la BASE, no el código: core e inventario también escriben menu_products.
--
-- ── Lo que entra ────────────────────────────────────────────────────
--   · menu_products.actualizado_en: BEFORE INSERT, y BEFORE UPDATE solo si cambia una columna que viaja en el paquete
--     (name_product, price, active, category_id; la lista es MarcasDeCambio.CATALOGO_QUE_VIAJA y una prueba las compara).
--     Ninguna ruta de venta ni de inventario hace UPDATE de menu_products (medido en todas las ramas de -mt, core e inventario).
--   · menu_products_borrados: lápidas de lo que core e inventario borran de verdad, por un disparador AFTER DELETE por
--     SENTENCIA (una inserción por borrado, no por fila) que purga en el mismo acto las del negocio de más de 14 días. Un
--     `desde` más viejo que eso recibe el paquete completo. Solo lectura para app_user; la escribe la función DEFINER.
--   · clientes.actualizado_en (ya existía, lo ponía el código a mano): BEFORE INSERT OR UPDATE la hace verdad para todo escritor.
--   · La ficha también cambia cuando cambia algo de otra tabla que viaja con ella: el cupo (accounts_receivable.credit_limit),
--     una etapa de insolvencia o el crédito después del inicio. Tres disparadores que solo tocan clientes.actualizado_en;
--     el del cupo solo actúa si el cupo cambia (WHEN), no con cada venta que mueve total_debt.
--   · La deuda NO mueve la marca (D-b4): viaja entera y compacta en cada paquete.
--
-- IMPACTO: una columna con DEFAULT constante (sin reescribir la tabla) y dos índices sobre menu_products y clientes; una tabla
-- nueva vacía; disparadores de una fila en memoria salvo los tres de la ficha, que actúan en escrituras raras.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 0 · Guardas ───────────────────────────────────────────────────────
DO $guarda$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'menu_products' AND column_name = 'actualizado_en') THEN
        RAISE EXCEPTION 'V83: menu_products ya tiene actualizado_en. No se cambia nada: hay que mirarlo antes';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'clientes' AND column_name = 'actualizado_en'
                      AND data_type = 'timestamp with time zone' AND is_nullable = 'NO') THEN
        RAISE EXCEPTION 'V83: clientes.actualizado_en no es la de V45';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_trigger
                WHERE tgrelid IN ('public.menu_products'::regclass, 'public.clientes'::regclass) AND NOT tgisinternal
                  AND tgname NOT IN ('trg_clientes_autor', 'trg_clientes_vendedor', 'trg_clientes_eventos')) THEN
        RAISE EXCEPTION 'V83: menu_products o clientes tienen disparadores no medidos';
    END IF;
    -- La función de lápidas es DEFINER sobre una tabla con FORCE RLS: su dueño (quien migra) tiene que saltar RLS.
    IF NOT EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname = current_user AND (r.rolbypassrls OR r.rolsuper)) THEN
        RAISE EXCEPTION 'V83: % no salta RLS: la funcion de lapidas no podria anexar', current_user;
    END IF;
END $guarda$;

-- ── 1 · Catálogo: la marca ────────────────────────────────────────────
ALTER TABLE public.menu_products ADD COLUMN actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX ix_menu_products_marca ON public.menu_products (tenant_id, actualizado_en);
COMMENT ON COLUMN public.menu_products.actualizado_en IS
    'F6.0b: cuándo cambió por última vez lo que viaja en el paquete de la ruta. La pone la base (V83), no el código.';

CREATE FUNCTION public.fn_marca_de_cambio()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    NEW.actualizado_en := now();
    RETURN NEW;
END $$;
COMMENT ON FUNCTION public.fn_marca_de_cambio() IS
    'F6.0b: pone actualizado_en = now() (reloj de la transacción, la regla de F1.8b). INVOKER, en memoria. V83.';
REVOKE EXECUTE ON FUNCTION public.fn_marca_de_cambio() FROM PUBLIC;

CREATE TRIGGER trg_menu_products_marca_insert BEFORE INSERT ON public.menu_products
    FOR EACH ROW EXECUTE FUNCTION public.fn_marca_de_cambio();
-- Las columnas del WHEN son exactamente MarcasDeCambio.CATALOGO_QUE_VIAJA (MarcaDeCambioTest las compara).
CREATE TRIGGER trg_menu_products_marca_update BEFORE UPDATE OF name_product, price, active, category_id ON public.menu_products
    FOR EACH ROW
    WHEN (OLD.name_product IS DISTINCT FROM NEW.name_product OR OLD.price IS DISTINCT FROM NEW.price
          OR OLD.active IS DISTINCT FROM NEW.active OR OLD.category_id IS DISTINCT FROM NEW.category_id)
    EXECUTE FUNCTION public.fn_marca_de_cambio();

-- ── 2 · Catálogo: lo borrado de verdad ────────────────────────────────
CREATE TABLE public.menu_products_borrados (
    id          BIGSERIAL    NOT NULL,
    tenant_id   TEXT         NOT NULL,
    id_product  VARCHAR(255) NOT NULL,
    borrado_en  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_menu_products_borrados PRIMARY KEY (id),
    -- Borrar un negocio se lleva sus lápidas: los cierres de migración que borran productos de prueba (V2–V5 de pedidos, V45…)
    -- borran después su negocio, y su comprobación de rastro cero no puede encontrar lápidas huérfanas.
    CONSTRAINT fk_menu_products_borrados_negocio FOREIGN KEY (tenant_id) REFERENCES public.tenants (id) ON DELETE CASCADE
);
CREATE INDEX ix_menu_products_borrados_negocio ON public.menu_products_borrados (tenant_id, borrado_en);
COMMENT ON TABLE public.menu_products_borrados IS
    'F6.0b: lápidas de productos borrados, para productosQueSalen. Las anexa y purga (más de 14 días) un disparador por '
    'sentencia; nadie más escribe. V83.';

ALTER TABLE public.menu_products_borrados ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.menu_products_borrados FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_menu_products_borrados ON public.menu_products_borrados
    USING (tenant_id = current_setting('app.tenant_id', true));

CREATE FUNCTION public.fn_lapidas_del_catalogo()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    -- Como dueño: con FORCE RLS, ni app_user ni un dueño sin BYPASSRLS anexarían. Quien migra salta RLS (guarda del §0).
    INSERT INTO public.menu_products_borrados (tenant_id, id_product)
    SELECT b.tenant_id, b.id_product FROM borrados b;
    -- F6.0b: la purga va con el evento, nunca a intervalo: las del negocio de más de 14 días (MarcasDeCambio.RETENCION_DE_LAPIDAS_DIAS).
    DELETE FROM public.menu_products_borrados l
     WHERE l.tenant_id IN (SELECT DISTINCT b.tenant_id FROM borrados b)
       AND l.borrado_en < now() - interval '14 days';
    RETURN NULL;
END $$;
COMMENT ON FUNCTION public.fn_lapidas_del_catalogo() IS
    'F6.0b: anexa una lápida por producto borrado y purga las del negocio de más de 14 días. Por sentencia. DEFINER. V83.';
REVOKE EXECUTE ON FUNCTION public.fn_lapidas_del_catalogo() FROM PUBLIC;

CREATE TRIGGER trg_menu_products_lapidas AFTER DELETE ON public.menu_products
    REFERENCING OLD TABLE AS borrados
    FOR EACH STATEMENT EXECUTE FUNCTION public.fn_lapidas_del_catalogo();

-- ── 3 · Clientes: la marca, y lo que viaja con la ficha desde otras tablas ─
CREATE INDEX ix_clientes_vendedor_marca ON public.clientes (tenant_id, vendedor_id, actualizado_en);

CREATE TRIGGER trg_clientes_marca BEFORE INSERT OR UPDATE ON public.clientes
    FOR EACH ROW EXECUTE FUNCTION public.fn_marca_de_cambio();

CREATE FUNCTION public.fn_marca_cliente_por_cupo()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE public.clientes c SET actualizado_en = now()
     WHERE c.tenant_id = NEW.tenant_id AND c.documento = NEW.customer_document;
    RETURN NULL;
END $$;
COMMENT ON FUNCTION public.fn_marca_cliente_por_cupo() IS
    'F6.0b: el cupo viaja en la ficha del cliente; al abrir la cuenta o cambiar el cupo, la ficha cambia. INVOKER. V83.';
REVOKE EXECUTE ON FUNCTION public.fn_marca_cliente_por_cupo() FROM PUBLIC;
CREATE TRIGGER trg_ar_marca_cliente_insert AFTER INSERT ON public.accounts_receivable
    FOR EACH ROW EXECUTE FUNCTION public.fn_marca_cliente_por_cupo();
-- Solo si el cupo cambia: cada venta y cada abono actualizan total_debt y no deben marcar la ficha.
CREATE TRIGGER trg_ar_marca_cliente_cupo AFTER UPDATE OF credit_limit ON public.accounts_receivable
    FOR EACH ROW WHEN (OLD.credit_limit IS DISTINCT FROM NEW.credit_limit)
    EXECUTE FUNCTION public.fn_marca_cliente_por_cupo();

CREATE FUNCTION public.fn_marca_cliente_por_proceso()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE public.clientes c SET actualizado_en = now()
      FROM public.insolvencia_procesos p
     WHERE p.tenant_id = NEW.tenant_id AND p.id = NEW.proceso_id
       AND c.tenant_id = p.tenant_id AND c.documento = p.cliente_documento;
    RETURN NULL;
END $$;
COMMENT ON FUNCTION public.fn_marca_cliente_por_proceso() IS
    'F6.0b: la insolvencia (etapa y crédito después del inicio) viaja en la ficha; una etapa o un cambio de crédito la marca. '
    'Escrituras raras, dentro de las funciones DEFINER de V75/V77/V82. INVOKER. V83.';
REVOKE EXECUTE ON FUNCTION public.fn_marca_cliente_por_proceso() FROM PUBLIC;
CREATE TRIGGER trg_insolvencia_etapas_marca_cliente AFTER INSERT ON public.insolvencia_etapas
    FOR EACH ROW EXECUTE FUNCTION public.fn_marca_cliente_por_proceso();
CREATE TRIGGER trg_insolvencia_credito_marca_cliente AFTER INSERT ON public.insolvencia_credito_posterior
    FOR EACH ROW EXECUTE FUNCTION public.fn_marca_cliente_por_proceso();

-- ── 4 · clientesQueSalen: reasignados o desactivados, desde el rastro que ya existe ─
CREATE INDEX ix_clientes_eventos_salen ON public.clientes_eventos (tenant_id, ocurrido_en)
    WHERE campo IN ('vendedor_id', 'activo');

-- ── 5 · Permisos ──────────────────────────────────────────────────────
DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT ON public.menu_products_borrados TO app_user;
    END IF;
END $permisos$;

-- ── 6 · Cierre ────────────────────────────────────────────────────────
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v83_a__';
    b CONSTANT TEXT := '__prueba_v83_b__';
    v_viejo CONSTANT TIMESTAMPTZ := now() - interval '1 day';
    v_user BIGINT;
    v_ctid TEXT;
    v_n BIGINT;
    t RECORD; v_quedan BIGINT := 0; v_donde TEXT := '';
BEGIN
    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V83 A', 'basico'), (b, 'Prueba V83 B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role, status)
    VALUES ('v83@prueba.invalid', '!', a, 'admin', 'disabled') RETURNING id INTO v_user;
    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_user::text, true);

    -- Catálogo: nace con marca; lo que no viaja no la mueve; el precio sí.
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('v83-p1', a, 'Uno', 1000, true), ('v83-p2', a, 'Dos', 2000, true), ('v83-p3', a, 'Tres', 3000, true),
           ('v83-pb', b, 'De B', 500, true);
    IF (SELECT actualizado_en FROM public.menu_products WHERE id_product = 'v83-p1') IS DISTINCT FROM now() THEN
        RAISE EXCEPTION 'V83: el producto nacio sin marca';
    END IF;
    UPDATE public.menu_products SET actualizado_en = v_viejo WHERE id_product = 'v83-p1';
    UPDATE public.menu_products SET creado_en_caja_por = 'caja' WHERE id_product = 'v83-p1';
    UPDATE public.menu_products SET price = price WHERE id_product = 'v83-p1';
    IF (SELECT actualizado_en FROM public.menu_products WHERE id_product = 'v83-p1') <> v_viejo THEN
        RAISE EXCEPTION 'V83: una columna que no viaja (o un UPDATE sin cambio) movio la marca';
    END IF;
    UPDATE public.menu_products SET price = 1100 WHERE id_product = 'v83-p1';
    IF (SELECT actualizado_en FROM public.menu_products WHERE id_product = 'v83-p1') <> now() THEN
        RAISE EXCEPTION 'V83: el precio cambio y la marca no';
    END IF;

    -- Borrado de dos en una sentencia: dos lápidas y una purga de la vieja del mismo negocio, no la del otro.
    INSERT INTO public.menu_products_borrados (tenant_id, id_product, borrado_en)
    VALUES (a, 'v83-vieja', now() - interval '15 days'), (b, 'v83-vieja-b', now() - interval '15 days');
    DELETE FROM public.menu_products WHERE id_product IN ('v83-p2', 'v83-p3');
    SELECT count(*) INTO v_n FROM public.menu_products_borrados WHERE tenant_id = a AND id_product IN ('v83-p2', 'v83-p3');
    IF v_n <> 2 THEN RAISE EXCEPTION 'V83: se esperaban 2 lapidas y hay %', v_n; END IF;
    IF EXISTS (SELECT 1 FROM public.menu_products_borrados WHERE id_product = 'v83-vieja') THEN
        RAISE EXCEPTION 'V83: la lapida de mas de 14 dias del negocio no se purgo al anexar';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.menu_products_borrados WHERE id_product = 'v83-vieja-b') THEN
        RAISE EXCEPTION 'V83: la purga toco la lapida de otro negocio';
    END IF;

    -- Clientes: nace con marca (aunque el INSERT traiga otra). Dentro de la transacción now() no cambia, así que lo que se
    -- comprueba es si la fila del cliente se reescribió (su ctid), sin desactivar disparadores ni bloquear la tabla.
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por, actualizado_en)
    VALUES (a, 'v83-c', 'Cliente', 8, 'v83', v_viejo);
    IF (SELECT actualizado_en FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') <> now() THEN
        RAISE EXCEPTION 'V83: el cliente nacio sin marca';
    END IF;
    UPDATE public.clientes SET actualizado_en = v_viejo WHERE tenant_id = a AND documento = 'v83-c';
    IF (SELECT actualizado_en FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') <> now() THEN
        RAISE EXCEPTION 'V83: un UPDATE del cliente no dejo la marca en now()';
    END IF;
    SELECT ctid::text INTO v_ctid FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c';
    INSERT INTO public.accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
    VALUES ('v83-cuenta', a, now(), 1000, 'v83-c', 'Cliente', 'ACTIVE', 0, now());
    IF (SELECT ctid::text FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') = v_ctid THEN
        RAISE EXCEPTION 'V83: abrir la cuenta no marco la ficha';
    END IF;
    SELECT ctid::text INTO v_ctid FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c';
    UPDATE public.accounts_receivable SET total_debt = total_debt + 500 WHERE id = 'v83-cuenta';
    IF (SELECT ctid::text FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') <> v_ctid THEN
        RAISE EXCEPTION 'V83: mover total_debt (una venta) marco la ficha';
    END IF;
    UPDATE public.accounts_receivable SET credit_limit = 2000 WHERE id = 'v83-cuenta';
    IF (SELECT ctid::text FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') = v_ctid THEN
        RAISE EXCEPTION 'V83: cambiar el cupo no marco la ficha';
    END IF;
    SELECT ctid::text INTO v_ctid FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c';
    PERFORM public.fn_insolvencia_informar_etapa('v83-c', 'SOLICITUD', (now() AT TIME ZONE 'America/Bogota')::date, 'Radicado', NULL,
                                                 'abogado', 'CGP', NULL, NULL);
    IF (SELECT ctid::text FROM public.clientes WHERE tenant_id = a AND documento = 'v83-c') = v_ctid THEN
        RAISE EXCEPTION 'V83: una etapa de insolvencia no marco la ficha';
    END IF;

    -- Permisos: app_user solo lee las lápidas; las dos funciones sin EXECUTE para PUBLIC; la de lápidas es DEFINER.
    IF has_table_privilege('app_user', 'public.menu_products_borrados', 'INSERT')
       OR has_table_privilege('app_user', 'public.menu_products_borrados', 'DELETE')
       OR NOT has_table_privilege('app_user', 'public.menu_products_borrados', 'SELECT') THEN
        RAISE EXCEPTION 'V83: app_user no tiene solo SELECT sobre menu_products_borrados';
    END IF;
    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = 'public.fn_lapidas_del_catalogo()'::regprocedure) THEN
        RAISE EXCEPTION 'V83: fn_lapidas_del_catalogo no es DEFINER';
    END IF;

    -- Rastro cero.
    DELETE FROM public.insolvencia_credito_posterior WHERE tenant_id = a;
    DELETE FROM public.insolvencia_foto_facturas WHERE tenant_id = a;
    DELETE FROM public.insolvencia_fotos WHERE tenant_id = a;
    DELETE FROM public.insolvencia_etapas WHERE tenant_id = a;
    DELETE FROM public.insolvencia_procesos WHERE tenant_id = a;
    DELETE FROM public.accounts_receivable WHERE tenant_id = a;
    DELETE FROM public.clientes_eventos WHERE tenant_id = a;
    DELETE FROM public.clientes WHERE tenant_id = a;
    DELETE FROM public.menu_products WHERE tenant_id IN (a, b);
    DELETE FROM public.menu_products_borrados WHERE tenant_id IN (a, b);
    DELETE FROM public.users WHERE tenant_id = a;
    DELETE FROM public.tenants WHERE id IN (a, b);
    FOR t IN SELECT c.table_schema, c.table_name FROM information_schema.columns c
              JOIN information_schema.tables x ON x.table_schema = c.table_schema AND x.table_name = c.table_name
             WHERE c.column_name = 'tenant_id' AND c.table_schema IN ('public', 'inventario', 'pedidos') AND x.table_type = 'BASE TABLE' LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id IN ($1, $2)', t.table_schema, t.table_name) INTO v_n USING a, b;
        IF v_n > 0 THEN
            v_quedan := v_quedan + v_n;
            v_donde := v_donde || ' ' || t.table_schema || '.' || t.table_name || '=' || v_n;
        END IF;
    END LOOP;
    IF v_quedan > 0 THEN
        RAISE EXCEPTION 'V83: quedaron % filas de prueba:%', v_quedan, v_donde;
    END IF;
    RAISE NOTICE 'V83: marca en catalogo (solo lo que viaja) y clientes (tambien por cupo e insolvencia, no por deuda); lapidas por sentencia con purga de 14 dias por negocio; permisos; 0 restos';
END $cierre$;
