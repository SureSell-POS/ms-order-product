-- =====================================================================
-- V60 -- La venta sabe quién vendió, cómo se paga y de dónde viene.
--
-- Plan de mayoristas (docs/planes/PLAN-DESARROLLO-MAYORISTAS.md), F1.1, M-P1
-- partida en dos: esta es la parte ADITIVA. El CHECK de `users.role` va en
-- su propia migración cuando se haya medido qué roles hay en staging y
-- producción (hoy `users.role` no tiene ninguna restricción, V4:26).
--
-- ── orders.vendedor_id no es created_by ───────────────────────────────
--
-- `orders.created_by` (V37) es quien OPERÓ la caja. En una distribuidora
-- quien vende no es quien digita: el vendedor de ruta cierra el pedido y la
-- caja lo captura. Si se mezclan, el informe por vendedor mide a la cajera
-- (DISTRIBUIDORAS §3.2a). `waiter_id` (V10) es el mesero y no tiene FK.
--
-- Un vendedor de OTRO negocio se rechaza con P0001 (nunca 0A000: Hikari
-- cierra la conexión). La aplicación lo valida antes y responde 400 con
-- `campo`; el disparador es el suelo por si alguien escribe por otro lado.
-- Qué ROL puede ser vendedor lo decide la aplicación (F1.3), no la base.
--
-- ── condicion_pago no se infiere del medio ────────────────────────────
--
-- CONTADO o CREDITO: lo que la DIAN pide en la factura (forma de pago) y lo
-- que decide si la venta entra a cuentas por cobrar. Un pago con tarjeta
-- puede ser de una venta a crédito que se abona después. NULL = venta
-- anterior a V60: «no aplica», nunca «contado».
--
-- ── origen y pedido_id ────────────────────────────────────────────────
--
-- `origen` ∈ {caja, app_meseros, pedido}: el canal por el que nació la venta.
-- NULL = anterior a V60. `pedido_id` es la referencia BLANDA a
-- `pedidos.pedidos` (cadena `pedidos`, F5): sin FK entre cadenas, como
-- `clientes.documento` con la cartera (V45:174-177).
--
-- IMPACTO: cuatro columnas nulas en `orders`, una en `users`, un índice
-- parcial y un disparador BEFORE que solo consulta cuando hay vendedor. Cero
-- filas cambian. ms-core-app lee `orders` con JPA y no mapea estas columnas:
-- no le afecta (regla R16).
-- =====================================================================

SET lock_timeout = '3s';

-- ── users.nombre ────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN IF NOT EXISTS nombre TEXT NULL;
COMMENT ON COLUMN users.nombre IS
    'Nombre visible en pantalla (vendedor, cajero). NULL = mostrar el correo. V60.';

-- ── orders ──────────────────────────────────────────────────────────
ALTER TABLE orders ADD COLUMN IF NOT EXISTS vendedor_id    BIGINT NULL REFERENCES users(id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS condicion_pago TEXT   NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS origen         TEXT   NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pedido_id      UUID   NULL;

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_condicion_pago;
ALTER TABLE orders ADD CONSTRAINT ck_orders_condicion_pago
    CHECK (condicion_pago IS NULL OR condicion_pago IN ('CONTADO', 'CREDITO'));
ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_origen;
ALTER TABLE orders ADD CONSTRAINT ck_orders_origen
    CHECK (origen IS NULL OR origen IN ('caja', 'app_meseros', 'pedido'));

COMMENT ON COLUMN orders.vendedor_id IS
    'Quien VENDIO (users.id del mismo negocio). No es created_by (quien opero la caja) '
    'ni waiter_id (el mesero). NULL = sin asignar o anterior a V60.';
COMMENT ON COLUMN orders.condicion_pago IS
    'CONTADO | CREDITO. No se deduce del medio de pago. NULL = anterior a V60 (no aplica).';
COMMENT ON COLUMN orders.origen IS
    'caja | app_meseros | pedido. NULL = anterior a V60.';
COMMENT ON COLUMN orders.pedido_id IS
    'Referencia blanda a pedidos.pedidos (otra cadena Flyway, F5). Sin FK a proposito.';

-- «Qué vendió este vendedor»: la pregunta del tablero (F3). Parcial: el
-- histórico sin vendedor no ocupa índice.
CREATE INDEX IF NOT EXISTS ix_orders_vendedor
    ON orders (tenant_id, vendedor_id, created_at) WHERE vendedor_id IS NOT NULL;

CREATE OR REPLACE FUNCTION fn_vendedor_es_del_negocio()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.vendedor_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.users u WHERE u.id = NEW.vendedor_id AND u.tenant_id = NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'El vendedor % no es un usuario de este negocio.', NEW.vendedor_id
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_orders_vendedor ON orders;
CREATE TRIGGER trg_orders_vendedor BEFORE INSERT OR UPDATE OF vendedor_id, tenant_id ON orders
    FOR EACH ROW WHEN (NEW.vendedor_id IS NOT NULL)
    EXECUTE FUNCTION fn_vendedor_es_del_negocio();

-- =====================================================================
-- La comprobación, por comportamiento. Escribe en `orders`, que tiene
-- disparadores que provisionan sede y contador (el rastro de V45), así que
-- al final se le pregunta a la base por TODAS las tablas con tenant_id.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v60_a__';
    b CONSTANT TEXT := '__prueba_v60_b__';
    v_vendedor_a BIGINT; v_vendedor_b BIGINT;
    v_rechazado BOOLEAN;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V60 A', 'basico'), (b, 'Prueba V60 B', 'basico');
    INSERT INTO users (email, password_hash, tenant_id, role, status, nombre)
    VALUES ('ana@prueba-v60.invalid', '!', a, 'vendedor', 'disabled', 'Ana') RETURNING id INTO v_vendedor_a;
    INSERT INTO users (email, password_hash, tenant_id, role, status)
    VALUES ('luis@prueba-v60.invalid', '!', b, 'vendedor', 'disabled') RETURNING id INTO v_vendedor_b;
    PERFORM set_config('app.tenant_id', a, true);

    -- 1. Una venta sin vendedor ni condición entra: lo de siempre no cambia.
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, created_at)
    VALUES (gen_random_uuid(), a, 'pagado', 'CASH', 1, 1, true, false, now());

    -- 2. Con su vendedor, su condición y su origen, entra.
    INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, created_at,
                        vendedor_id, condicion_pago, origen)
    VALUES (gen_random_uuid(), a, 'pagado', 'CASH', 1, 1, true, false, now(), v_vendedor_a, 'CONTADO', 'caja');

    -- 3. Un vendedor de otro negocio se rechaza con P0001.
    v_rechazado := false;
    BEGIN
        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed,
                            created_at, vendedor_id)
        VALUES (gen_random_uuid(), a, 'pagado', 'CASH', 1, 1, true, false, now(), v_vendedor_b);
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V60: una venta de A entro con un vendedor de B';
    END IF;

    -- 4. Valores inventados en condición y origen se rechazan.
    v_rechazado := false;
    BEGIN
        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed,
                            created_at, condicion_pago)
        VALUES (gen_random_uuid(), a, 'pagado', 'CASH', 1, 1, true, false, now(), 'FIADO');
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V60: condicion_pago admitio un valor fuera del catalogo';
    END IF;
    v_rechazado := false;
    BEGIN
        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed,
                            created_at, origen)
        VALUES (gen_random_uuid(), a, 'pagado', 'CASH', 1, 1, true, false, now(), 'otro');
    EXCEPTION WHEN check_violation THEN
        v_rechazado := true;
    END;
    IF NOT v_rechazado THEN
        RAISE EXCEPTION 'V60: origen admitio un valor fuera del catalogo';
    END IF;

    -- Limpieza: lo insertado y lo que provocaron los disparadores de orders.
    PERFORM set_config('app.tenant_id', '', true);
    DELETE FROM order_item               WHERE tenant_id IN (a, b);
    DELETE FROM orders                   WHERE tenant_id IN (a, b);
    DELETE FROM tenant_order_counters    WHERE tenant_id IN (a, b);
    DELETE FROM sites                    WHERE tenant_id IN (a, b);
    DELETE FROM users                    WHERE tenant_id IN (a, b);
    DELETE FROM tenants                  WHERE id IN (a, b);

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario', 'pedidos', 'red')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id IN ($1, $2)', t.s, t.tn)
           INTO n USING a, b;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id IN (a, b)) THEN
        RAISE EXCEPTION 'V60: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V60: la venta sabe quien vendio (del mismo negocio), como se paga y de donde viene.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TRIGGER IF EXISTS trg_orders_vendedor ON orders;
-- DROP FUNCTION IF EXISTS fn_vendedor_es_del_negocio();
-- DROP INDEX IF EXISTS ix_orders_vendedor;
-- ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_origen;
-- ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_condicion_pago;
-- ALTER TABLE orders DROP COLUMN IF EXISTS pedido_id, DROP COLUMN IF EXISTS origen,
--     DROP COLUMN IF EXISTS condicion_pago, DROP COLUMN IF EXISTS vendedor_id;
-- ALTER TABLE users DROP COLUMN IF EXISTS nombre;
