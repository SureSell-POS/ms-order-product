-- =====================================================================
-- V45 -- Listas de precio por cliente, y la venta a credito que AVISA.
--
-- POR QUE
--
-- Un mayorista no vende al mismo precio a todos: hay precio por cliente, por
-- volumen y por plazo. Hoy el precio vive en `menu_products.price` (uno por
-- producto) y, peor, LO MANDA EL POS: `order_item.unit_price` se guarda tal
-- cual llega (`OrderHandler.createOrderItems`); el servidor solo compara el
-- total declarado con el suyo (V36). Para un mostrador de restaurante vale;
-- para un mayorista con 40 clientes y 3 listas, no: el precio tiene que
-- resolverlo el servidor a partir de QUIEN compra y CUANTO.
--
-- EL MODELO: LISTA ASIGNADA AL CLIENTE, CON ESCALAS POR VOLUMEN DENTRO
--
-- Dos opciones sobre la mesa: (a) una lista de precios por cliente, (b)
-- reglas de descuento por volumen. El caso real las necesita las dos, y la
-- BASE es la lista:
--
--   · el mayorista negocia por cliente ("a Tienda La Esquina le doy el
--     precio de distribuidor"), y esa negociacion es una LISTA que comparten
--     varios clientes, no un descuento suelto;
--   · el volumen es una propiedad de la lista, no del cliente: "en la lista
--     distribuidor, de 12 en adelante vale menos". Por eso cada linea de la
--     lista lleva `cantidad_minima`: la escala vive dentro de la lista;
--   · un descuento por volumen sin lista obligaria a resolver el precio con
--     dos tablas y una precedencia; con la lista, la escala ES la linea.
--
-- Resolver un precio = la linea vigente de la lista del cliente para ese
-- producto con la mayor `cantidad_minima` <= cantidad; si no hay, el precio
-- base de `menu_products`. Lo hace `fn_precio_para`, en la base, y lo usa
-- el servidor al crear la orden.
--
-- 🔴 EL PRECIO APLICADO SE GUARDA CON LA VENTA, NO SE RECALCULA DESPUES
--
-- `order_item.unit_price` ya lo guarda. Lo que faltaba es DE DONDE SALIO:
-- `precio_origen` (LISTA / BASE / POS) y `lista_precio_item_id`, la LINEA
-- VERSIONADA que se aplico. Si manana cambia la lista, la venta de ayer sigue
-- siendo lo que fue, y se puede decir con que linea se cobro. Es la misma
-- razon por la que las presentaciones de compra son eventos versionados
-- (inventario V2): un precio que se sobrescribe borra la negociacion que
-- habia.
--
-- Por eso `listas_precio_items` es APPEND-ONLY con vigencia: cambiar un
-- precio es cerrar la linea vigente y abrir otra. La aplicacion tiene INSERT
-- y un UPDATE que un trigger limita a cerrar `vigente_hasta`. Ni un solo
-- precio se reescribe.
--
-- LA VENTA A CREDITO: AVISA, NO BLOQUEA
--
-- La cartera de V28 bloquea al superar el cupo (`canAddDebt`). Para un
-- abono manual esta bien. Para una VENTA en el mostrador no: bloquear una
-- venta legitima porque un cupo declarado con confianza 1 se paso en 5.000
-- pesos es caro, y el cliente esta delante. Es la misma logica de la
-- compuerta de version: hay puertas que fallan abiertas. Asi que una orden
-- con `payment_method = 'CREDITO'`:
--
--   1. exige `cliente_documento` y una cuenta de cartera ACTIVA de ese
--      cliente (sin cuenta no hay a quien cobrarle: eso si se niega);
--   2. escribe el DEBITO en `debt_transactions` con `order_uuid` (V44) y
--      actualiza `total_debt`, en la MISMA transaccion que la venta;
--   3. si la cuenta queda por encima del cupo, marca `excede_cupo = TRUE` en
--      la orden y en el debito -- y la venta ENTRA. El panel lo ensena; el
--      dueño decide. Nadie se entera tres semanas despues: queda en la fila.
--
-- Lo hace un trigger BEFORE INSERT sobre `orders`, para que valga igual
-- venga la venta del POS, del panel o de un script.
--
-- IMPACTO: 0 filas tocadas. Tres tablas nuevas, cinco columnas anulables
-- en orders/order_item, una funcion, dos triggers. En produccion no hay
-- ningun negocio mayorista; nada cambia para shark-burger. ROLLBACK al final.
-- =====================================================================


-- =====================================================================
-- 1 · listas_precio -- la cabecera
-- =====================================================================
CREATE TABLE IF NOT EXISTS listas_precio (
    id          UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   TEXT    NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    codigo      TEXT    NOT NULL,
    nombre      TEXT    NOT NULL,
    activa      BOOLEAN NOT NULL DEFAULT TRUE,
    creado_por  TEXT    NOT NULL,
    creado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_listas_precio PRIMARY KEY (id),
    CONSTRAINT ux_listas_precio_codigo UNIQUE (tenant_id, codigo),
    CONSTRAINT ck_listas_precio_codigo CHECK (codigo ~ '^[a-z0-9][a-z0-9_-]{0,39}$'),
    CONSTRAINT ck_listas_precio_nombre CHECK (length(btrim(nombre)) > 0),
    CONSTRAINT ck_listas_precio_usuario CHECK (length(btrim(creado_por)) > 0)
);
CREATE INDEX IF NOT EXISTS ix_listas_precio_tenant ON listas_precio (tenant_id);

-- =====================================================================
-- 2 · listas_precio_items -- APPEND-ONLY, versionado por vigencia
-- =====================================================================
CREATE TABLE IF NOT EXISTS listas_precio_items (
    id              UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       TEXT    NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    lista_id        UUID    NOT NULL,
    -- Referencia blanda a menu_products.id_product: esa tabla la escriben
    -- dos servicios y su ciclo de vida no es de esta lista.
    producto_id     TEXT    NOT NULL,
    -- La escala: esta linea aplica desde esta cantidad. 1 = precio base de
    -- la lista.
    cantidad_minima INTEGER NOT NULL DEFAULT 1,
    precio          NUMERIC(15,2) NOT NULL,
    vigente_desde   TIMESTAMPTZ NOT NULL DEFAULT now(),
    vigente_hasta   TIMESTAMPTZ NULL,
    -- Admisibilidad (LINEAMIENTOS reglas 3-6): quien, cuando, de donde, cuanto
    -- se puede confiar. Un precio negociado por telefono no vale lo mismo que
    -- uno que esta en un acuerdo firmado.
    usuario_id      TEXT    NOT NULL,
    fuente          TEXT    NOT NULL,
    confianza       SMALLINT NOT NULL,
    ocurrido_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    registrado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    corrige_evento_id UUID  NULL,
    nota            TEXT    NULL,
    CONSTRAINT pk_listas_precio_items PRIMARY KEY (id),
    CONSTRAINT fk_lpi_lista FOREIGN KEY (lista_id) REFERENCES listas_precio (id),
    CONSTRAINT fk_lpi_correccion FOREIGN KEY (corrige_evento_id) REFERENCES listas_precio_items (id),
    CONSTRAINT ck_lpi_cantidad_minima CHECK (cantidad_minima >= 1),
    CONSTRAINT ck_lpi_precio CHECK (precio >= 0),
    CONSTRAINT ck_lpi_vigencia CHECK (vigente_hasta IS NULL OR vigente_hasta > vigente_desde),
    CONSTRAINT ck_lpi_usuario CHECK (length(btrim(usuario_id)) > 0),
    -- Enum cerrado, sin "otro" (regla 10).
    CONSTRAINT ck_lpi_fuente CHECK (fuente IN ('acuerdo_documentado', 'declarado_comerciante', 'migracion')),
    CONSTRAINT ck_lpi_confianza CHECK (confianza BETWEEN 0 AND 3),
    CONSTRAINT ck_lpi_coherencia CHECK (
        (fuente = 'acuerdo_documentado' AND confianza >= 2)
        OR (fuente IN ('declarado_comerciante', 'migracion') AND confianza <= 1)),
    CONSTRAINT ck_lpi_reloj CHECK (ocurrido_en <= registrado_en),
    CONSTRAINT ck_lpi_no_autocorreccion CHECK (corrige_evento_id IS NULL OR corrige_evento_id <> id)
);
CREATE INDEX IF NOT EXISTS ix_lpi_tenant ON listas_precio_items (tenant_id);
-- Una sola linea vigente por (lista, producto, escala).
CREATE UNIQUE INDEX IF NOT EXISTS ux_lpi_vigente
    ON listas_precio_items (lista_id, producto_id, cantidad_minima)
    WHERE vigente_hasta IS NULL;
CREATE INDEX IF NOT EXISTS ix_lpi_resolver
    ON listas_precio_items (lista_id, producto_id, cantidad_minima DESC)
    WHERE vigente_hasta IS NULL;

-- El UNICO cambio admitido sobre una linea: cerrar su vigencia. Cualquier
-- otro UPDATE se rechaza. Asi el UPDATE que la aplicacion necesita para
-- versionar no se convierte en una puerta para reescribir precios.
CREATE OR REPLACE FUNCTION fn_lpi_solo_se_cierra()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.vigente_hasta IS NOT NULL THEN
        RAISE EXCEPTION 'La linea de precio ya estaba cerrada: no se reabre ni se edita. Abre otra.';
    END IF;
    IF NEW.vigente_hasta IS NULL
       OR NEW.precio IS DISTINCT FROM OLD.precio
       OR NEW.producto_id IS DISTINCT FROM OLD.producto_id
       OR NEW.cantidad_minima IS DISTINCT FROM OLD.cantidad_minima
       OR NEW.lista_id IS DISTINCT FROM OLD.lista_id
       OR NEW.vigente_desde IS DISTINCT FROM OLD.vigente_desde
       OR NEW.fuente IS DISTINCT FROM OLD.fuente
       OR NEW.confianza IS DISTINCT FROM OLD.confianza
       OR NEW.usuario_id IS DISTINCT FROM OLD.usuario_id THEN
        RAISE EXCEPTION 'Un precio no se edita: se cierra la linea vigente y se abre otra con el precio nuevo.';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_lpi_solo_se_cierra ON listas_precio_items;
CREATE TRIGGER trg_lpi_solo_se_cierra BEFORE UPDATE ON listas_precio_items
    FOR EACH ROW EXECUTE FUNCTION fn_lpi_solo_se_cierra();

-- =====================================================================
-- 3 · clientes -- quien compra, y con que lista y plazo
-- =====================================================================
-- La cartera (V28) identifica al cliente por `customer_document`; aqui se
-- usa el mismo documento para que las dos cosas hablen sin FK entre
-- servicios. `plazo_dias` es el plazo pactado; hoy se guarda, y la alerta
-- de vencido de la cartera (`/overdue?days`) es quien lo puede leer.
CREATE TABLE IF NOT EXISTS clientes (
    id              UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       TEXT    NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    documento       TEXT    NOT NULL,
    nombre          TEXT    NOT NULL,
    telefono        TEXT    NULL,
    lista_precio_id UUID    NULL,
    plazo_dias      INTEGER NULL,
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    creado_por      TEXT    NOT NULL,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_clientes PRIMARY KEY (id),
    CONSTRAINT ux_clientes_documento UNIQUE (tenant_id, documento),
    CONSTRAINT fk_clientes_lista FOREIGN KEY (lista_precio_id) REFERENCES listas_precio (id),
    CONSTRAINT ck_clientes_documento CHECK (length(btrim(documento)) > 0),
    CONSTRAINT ck_clientes_nombre CHECK (length(btrim(nombre)) > 0),
    CONSTRAINT ck_clientes_plazo CHECK (plazo_dias IS NULL OR plazo_dias BETWEEN 0 AND 365)
);
CREATE INDEX IF NOT EXISTS ix_clientes_tenant ON clientes (tenant_id);

-- =====================================================================
-- 4 · La venta lleva al cliente y el precio lleva su origen
-- =====================================================================
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS cliente_documento TEXT    NULL,
    ADD COLUMN IF NOT EXISTS lista_precio_id   UUID    NULL,
    -- NULL = no era a credito; FALSE/TRUE = a credito, y si supero el cupo.
    ADD COLUMN IF NOT EXISTS excede_cupo       BOOLEAN NULL;

ALTER TABLE order_item
    ADD COLUMN IF NOT EXISTS precio_origen        TEXT NULL,
    ADD COLUMN IF NOT EXISTS lista_precio_item_id UUID NULL;

ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_precio_origen;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_precio_origen
    CHECK (precio_origen IS NULL OR precio_origen IN ('POS', 'LISTA', 'BASE'));
-- Con origen LISTA hay linea; con otro origen no. NULL-safe: IS NULL / IS NOT NULL.
ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_linea_de_lista;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_linea_de_lista
    CHECK ((COALESCE(precio_origen, '') = 'LISTA') = (lista_precio_item_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS ix_orders_cliente
    ON orders (tenant_id, cliente_documento) WHERE cliente_documento IS NOT NULL;

-- =====================================================================
-- 5 · fn_precio_para -- el precio que corresponde, y de donde sale
-- =====================================================================
CREATE OR REPLACE FUNCTION fn_precio_para(
    p_documento TEXT,
    p_producto  TEXT,
    p_cantidad  INTEGER,
    p_momento   TIMESTAMPTZ DEFAULT now())
RETURNS TABLE (precio NUMERIC, origen TEXT, lista_precio_item_id UUID, lista_precio_id UUID)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    -- La linea de la lista del cliente con la mayor escala que la cantidad
    -- alcance, vigente en el momento; si no hay, el precio base del producto.
    WITH lista AS (
        SELECT c.lista_precio_id
          FROM public.clientes c
         WHERE c.documento = p_documento AND c.activo
    ),
    linea AS (
        SELECT i.precio, i.id, i.lista_id
          FROM public.listas_precio_items i
          JOIN lista l ON l.lista_precio_id = i.lista_id
          JOIN public.listas_precio lp ON lp.id = i.lista_id AND lp.activa
         WHERE i.producto_id = p_producto
           AND i.cantidad_minima <= GREATEST(p_cantidad, 1)
           AND i.vigente_desde <= p_momento
           AND (i.vigente_hasta IS NULL OR i.vigente_hasta > p_momento)
         ORDER BY i.cantidad_minima DESC, i.vigente_desde DESC
         LIMIT 1
    )
    SELECT l.precio, 'LISTA', l.id, l.lista_id FROM linea l
    UNION ALL
    SELECT mp.price::numeric, 'BASE', NULL::uuid, (SELECT lista_precio_id FROM lista)
      FROM public.menu_products mp
     WHERE mp.id_product = p_producto
       AND NOT EXISTS (SELECT 1 FROM linea)
    LIMIT 1;
$$;

COMMENT ON FUNCTION fn_precio_para IS
    'El precio para un cliente, un producto y una cantidad en un momento: la linea '
    'vigente de su lista con la mayor escala alcanzada (LISTA) o el precio base '
    'del producto (BASE). Sin fila si el producto no existe.';

-- =====================================================================
-- 6 · La venta a credito: el debito en la cartera, y el aviso
-- =====================================================================
CREATE OR REPLACE FUNCTION fn_venta_a_credito()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_cuenta   public.accounts_receivable%ROWTYPE;
    v_nueva    NUMERIC;
    v_excede   BOOLEAN;
BEGIN
    IF NEW.cliente_documento IS NULL OR btrim(NEW.cliente_documento) = '' THEN
        RAISE EXCEPTION 'Una venta a credito necesita el documento del cliente.';
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
    v_nueva  := v_cuenta.total_debt + COALESCE(NEW.total, 0);
    -- AVISA, NO BLOQUEA: la venta entra y queda marcada.
    v_excede := v_nueva > v_cuenta.credit_limit;

    INSERT INTO public.debt_transactions
        (id, tenant_id, account_id, amount, created_at, description, payment_method,
         reference, transaction_date, type, order_uuid, excede_cupo, registrado_por)
    VALUES (gen_random_uuid()::text, NEW.tenant_id, v_cuenta.id, COALESCE(NEW.total, 0), now(),
            'Venta a credito', NULL, NEW.uuid_id::text, CURRENT_DATE, 'DEBIT',
            NEW.uuid_id, v_excede, 'sistema:venta');

    UPDATE public.accounts_receivable
       SET total_debt = v_nueva,
           last_transaction_date = CURRENT_DATE,
           status = 'ACTIVE',
           updated_at = now()
     WHERE id = v_cuenta.id;

    NEW.excede_cupo := v_excede;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_orders_venta_a_credito ON orders;
CREATE TRIGGER trg_orders_venta_a_credito BEFORE INSERT ON orders
    FOR EACH ROW WHEN (NEW.payment_method = 'CREDITO')
    EXECUTE FUNCTION fn_venta_a_credito();

-- =====================================================================
-- 7 · Aislamiento y permisos, por tabla
-- =====================================================================
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['listas_precio', 'listas_precio_items', 'clientes'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON public.%I', t, t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON public.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT, UPDATE ON public.listas_precio       TO app_user;
        -- INSERT y un UPDATE que el trigger limita a cerrar la vigencia. Sin DELETE.
        GRANT SELECT, INSERT, UPDATE ON public.listas_precio_items TO app_user;
        GRANT SELECT, INSERT, UPDATE ON public.clientes            TO app_user;
        GRANT EXECUTE ON FUNCTION fn_precio_para(TEXT, TEXT, INTEGER, TIMESTAMPTZ) TO app_user;
    END IF;
END
$rls$;

-- =====================================================================
-- 8 · La comprobacion, por comportamiento: la escala, el origen y el aviso
-- =====================================================================
DO $cierre$
DECLARE
    v_tenant TEXT := '__prueba_v45__';
    v_lista  UUID; v_cuenta TEXT := gen_random_uuid()::text;
    v_precio NUMERIC; v_origen TEXT; v_item UUID; v_excede BOOLEAN; v_deuda NUMERIC;
    v_orden  UUID := gen_random_uuid();
    abiertas TEXT;
BEGIN
    SELECT string_agg(tablename || '.' || policyname, ', ') INTO abiertas
      FROM pg_policies WHERE schemaname = 'public'
       AND tablename IN ('listas_precio', 'listas_precio_items', 'clientes')
       AND permissive = 'PERMISSIVE' AND (qual = 'true' OR qual IS NULL);
    IF abiertas IS NOT NULL THEN
        RAISE EXCEPTION 'V45: politicas abiertas: %', abiertas;
    END IF;

    PERFORM set_config('app.tenant_id', v_tenant, true);
    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('__p45__', v_tenant, 'bulto de prueba', 10000, true);
    INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) VALUES (v_tenant, 'dist', 'Distribuidor', 'v45') RETURNING id INTO v_lista;
    INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza)
    VALUES (v_tenant, v_lista, '__p45__', 1,  9000, 'v45', 'declarado_comerciante', 1),
           (v_tenant, v_lista, '__p45__', 12, 8000, 'v45', 'acuerdo_documentado', 2);
    INSERT INTO clientes (tenant_id, documento, nombre, lista_precio_id, creado_por)
    VALUES (v_tenant, '__c45__', 'Cliente de prueba', v_lista, 'v45');

    -- Escala: 5 unidades -> 9.000 (LISTA); 12 -> 8.000; un desconocido -> 10.000 (BASE).
    SELECT precio, origen, lista_precio_item_id INTO v_precio, v_origen, v_item FROM fn_precio_para('__c45__', '__p45__', 5);
    IF v_precio IS DISTINCT FROM 9000 OR v_origen <> 'LISTA' OR v_item IS NULL THEN
        RAISE EXCEPTION 'V45: 5 unidades deberian valer 9000 de LISTA y dio % %', v_precio, v_origen;
    END IF;
    SELECT precio INTO v_precio FROM fn_precio_para('__c45__', '__p45__', 12);
    IF v_precio IS DISTINCT FROM 8000 THEN
        RAISE EXCEPTION 'V45: 12 unidades deberian valer 8000 y dio %', v_precio;
    END IF;
    SELECT precio, origen INTO v_precio, v_origen FROM fn_precio_para('nadie', '__p45__', 1);
    IF v_precio IS DISTINCT FROM 10000 OR v_origen <> 'BASE' THEN
        RAISE EXCEPTION 'V45: sin lista deberia valer el precio base 10000 y dio % %', v_precio, v_origen;
    END IF;

    -- Un precio no se edita: se cierra y se abre otro.
    BEGIN
        UPDATE listas_precio_items SET precio = 1 WHERE lista_id = v_lista AND cantidad_minima = 1;
        RAISE EXCEPTION 'V45: se pudo editar un precio de la lista';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM LIKE 'V45:%' THEN RAISE; END IF;
    END;

    -- Venta a credito por encima del cupo: ENTRA y avisa; el debito queda en el libro.
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
    VALUES (v_cuenta, v_tenant, now(), 20000, '__c45__', 'Cliente de prueba', 'ACTIVE', 0, now());
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
    VALUES (v_orden, v_tenant, 'pagado', 'CREDITO', 45000, 45000, true, false, '__c45__', now());
    SELECT excede_cupo INTO v_excede FROM orders WHERE uuid_id = v_orden;
    SELECT total_debt INTO v_deuda FROM accounts_receivable WHERE id = v_cuenta;
    IF v_excede IS DISTINCT FROM TRUE OR v_deuda IS DISTINCT FROM 45000 THEN
        RAISE EXCEPTION 'V45: la venta a credito sobre el cupo deberia entrar avisando (excede=% deuda=%)', v_excede, v_deuda;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM debt_transactions WHERE order_uuid = v_orden AND type = 'DEBIT' AND excede_cupo) THEN
        RAISE EXCEPTION 'V45: el debito de la venta no quedo en el libro con su orden';
    END IF;
    -- Sin cuenta: se niega.
    BEGIN
        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, cliente_documento, created_at)
        VALUES (gen_random_uuid(), v_tenant, 'pagado', 'CREDITO', 1, 1, true, false, 'sin-cuenta', now());
        RAISE EXCEPTION 'V45: una venta a credito sin cuenta de cartera entro';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM LIKE 'V45:%' THEN RAISE; END IF;
    END;

    DELETE FROM debt_transactions WHERE tenant_id = v_tenant;
    DELETE FROM accounts_receivable WHERE tenant_id = v_tenant;
    DELETE FROM orders WHERE tenant_id = v_tenant;
    DELETE FROM clientes WHERE tenant_id = v_tenant;
    DELETE FROM listas_precio_items WHERE tenant_id = v_tenant;
    DELETE FROM listas_precio WHERE tenant_id = v_tenant;
    DELETE FROM menu_products WHERE tenant_id = v_tenant;
    PERFORM set_config('app.tenant_id', '', true);
    RAISE NOTICE 'V45: listas por cliente con escala, precio con origen en la venta, y credito que avisa.';
END
$cierre$;

-- =====================================================================
-- DOWN -- rollback explicito
-- =====================================================================
--
-- DROP TRIGGER IF EXISTS trg_orders_venta_a_credito ON orders;
-- DROP FUNCTION IF EXISTS fn_venta_a_credito();
-- DROP FUNCTION IF EXISTS fn_precio_para(TEXT, TEXT, INTEGER, TIMESTAMPTZ);
-- ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_linea_de_lista, DROP CONSTRAINT IF EXISTS ck_order_item_precio_origen;
-- ALTER TABLE order_item DROP COLUMN IF EXISTS precio_origen, DROP COLUMN IF EXISTS lista_precio_item_id;
-- ALTER TABLE orders DROP COLUMN IF EXISTS cliente_documento, DROP COLUMN IF EXISTS lista_precio_id, DROP COLUMN IF EXISTS excede_cupo;
-- DROP TABLE IF EXISTS clientes; DROP TRIGGER IF EXISTS trg_lpi_solo_se_cierra ON listas_precio_items;
-- DROP FUNCTION IF EXISTS fn_lpi_solo_se_cierra(); DROP TABLE IF EXISTS listas_precio_items; DROP TABLE IF EXISTS listas_precio;
