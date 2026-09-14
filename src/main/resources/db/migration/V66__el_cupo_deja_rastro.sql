-- =====================================================================
-- V66 -- El cupo de crédito deja rastro en la historia del cliente.
--
-- Plan de mayoristas, F4.4 (§7.4: `PUT /api/cartera/clientes/{documento}/cupo`
-- «rastro en `clientes_eventos`»).
--
-- ── Lo que había ──────────────────────────────────────────────────────
--
-- `clientes_eventos` (V62, con `en_insolvencia_desde` desde V64) la escribe un
-- disparador de `clientes`. El cupo no vive ahí: es `accounts_receivable.
-- credit_limit`. Un cambio de cupo no dejaba nada, ni desde -mt ni desde el
-- panel viejo de core, que todavía puede escribirlo hasta F4.6.
--
-- ── Lo que se añade ───────────────────────────────────────────────────
--
-- 1. `cupo` en el CHECK de `clientes_eventos.campo`.
-- 2. Disparador AFTER INSERT OR UPDATE OF credit_limit en `accounts_receivable`:
--    escribe el evento con el autor de `app.user_id`, como V62. En el disparador y
--    no en la aplicación, para que el cambio hecho por core también quede.
--    · Una cuenta que nace con cupo 0 (la automática de F1.3) no escribe nada:
--      no hay decisión que contar.
--    · Si el documento no tiene ficha en `clientes` (cuentas antiguas de core), no
--      hay dónde colgar el evento: se guarda el cambio y AVISA en el log.
--    · Sin autor, se guarda y AVISA, igual que V62.
--
-- IMPACTO: un CHECK reemplazado sobre una tabla solo-anexar y un disparador
-- nuevo por fila en `accounts_receivable` (escrituras raras: cupo y ventas a
-- crédito; el disparador solo actúa si cambia `credit_limit`).
-- =====================================================================

SET lock_timeout = '3s';

ALTER TABLE clientes_eventos DROP CONSTRAINT IF EXISTS ck_clientes_eventos_campo;
ALTER TABLE clientes_eventos ADD CONSTRAINT ck_clientes_eventos_campo CHECK (campo IN (
    'nombre', 'lista_precio_id', 'plazo_dias', 'activo', 'vendedor_id', 'tipo_cliente',
    'exige_factura', 'tipo_documento', 'razon_social', 'direccion_entrega', 'municipio_dane',
    'correo', 'whatsapp', 'telefono', 'en_insolvencia_desde', 'cupo'));

CREATE OR REPLACE FUNCTION fn_cupo_eventos()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_autor_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_autor     BIGINT := CASE WHEN v_autor_txt ~ '^[0-9]+$' THEN v_autor_txt::BIGINT END;
    v_antes     NUMERIC := CASE WHEN TG_OP = 'UPDATE' THEN OLD.credit_limit END;
    v_cliente   UUID;
BEGIN
    IF v_antes IS NOT DISTINCT FROM NEW.credit_limit
       OR (TG_OP = 'INSERT' AND COALESCE(NEW.credit_limit, 0) = 0) THEN
        RETURN NULL;
    END IF;
    SELECT c.id INTO v_cliente FROM public.clientes c
     WHERE c.tenant_id = NEW.tenant_id AND c.documento = NEW.customer_document;
    IF v_cliente IS NULL THEN
        RAISE WARNING 'cupo sin rastro: la cuenta % del negocio % no tiene ficha en clientes (cupo % -> %)',
            NEW.id, NEW.tenant_id, v_antes, NEW.credit_limit;
        RETURN NULL;
    END IF;
    INSERT INTO public.clientes_eventos (tenant_id, cliente_id, campo, valor_anterior, valor_nuevo, usuario_id)
    VALUES (NEW.tenant_id, v_cliente, 'cupo', v_antes::text, NEW.credit_limit::text, v_autor);
    IF v_autor IS NULL THEN
        RAISE WARNING 'clientes_eventos sin autor: cupo del cliente % del negocio % cambio sin app.user_id',
            v_cliente, NEW.tenant_id;
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_ar_cupo_eventos ON accounts_receivable;
CREATE TRIGGER trg_ar_cupo_eventos AFTER INSERT OR UPDATE OF credit_limit ON accounts_receivable
    FOR EACH ROW EXECUTE FUNCTION fn_cupo_eventos();

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v66_a__';
    v_con   TEXT := gen_random_uuid()::text;
    v_sin   TEXT := gen_random_uuid()::text;
    v_cero  TEXT := gen_random_uuid()::text;
    v_user  BIGINT;
    v_ev    RECORD;
    v_n     BIGINT;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V66', 'basico');
    PERFORM set_config('app.tenant_id', a, true);
    INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('v66@prueba.invalid', '!', a, 'admin')
    RETURNING id INTO v_user;
    INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES
        (a, '__con__', 'Con ficha', 'v66'), (a, '__cero__', 'Cupo cero', 'v66');

    -- 1. La cuenta automática (cupo 0) no escribe; una con ficha y cupo sí, con autor.
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                     status, total_debt, updated_at) VALUES
        (v_cero, a, now(), 0, '__cero__', 'Cupo cero', 'ACTIVE', 0, now()),
        (v_con,  a, now(), 0, '__con__',  'Con ficha', 'ACTIVE', 0, now()),
        (v_sin,  a, now(), 0, '__sin__',  'Sin ficha', 'ACTIVE', 0, now());
    SELECT count(*) INTO v_n FROM clientes_eventos WHERE tenant_id = a;
    IF v_n <> 0 THEN
        RAISE EXCEPTION 'V66: abrir cuentas con cupo 0 escribio % eventos', v_n;
    END IF;

    PERFORM set_config('app.user_id', v_user::text, true);
    UPDATE accounts_receivable SET credit_limit = 500000 WHERE id = v_con;
    SELECT e.valor_anterior, e.valor_nuevo, e.usuario_id INTO v_ev
      FROM clientes_eventos e WHERE e.tenant_id = a AND e.campo = 'cupo';
    IF v_ev.valor_anterior IS DISTINCT FROM '0.00' OR v_ev.valor_nuevo IS DISTINCT FROM '500000.00'
       OR v_ev.usuario_id IS DISTINCT FROM v_user THEN
        RAISE EXCEPTION 'V66: el cupo dejo % -> % autor %', v_ev.valor_anterior, v_ev.valor_nuevo, v_ev.usuario_id;
    END IF;

    -- 2. Otra escritura que no toca el cupo (una venta a crédito mueve total_debt) no escribe.
    UPDATE accounts_receivable SET total_debt = 1000 WHERE id = v_con;
    UPDATE accounts_receivable SET credit_limit = 500000 WHERE id = v_con;
    SELECT count(*) INTO v_n FROM clientes_eventos WHERE tenant_id = a;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'V66: cambios sin cupo nuevo escribieron eventos (hay %)', v_n;
    END IF;

    -- 3. Sin ficha: el cupo se guarda y no falla.
    UPDATE accounts_receivable SET credit_limit = 9000 WHERE id = v_sin;
    IF (SELECT credit_limit FROM accounts_receivable WHERE id = v_sin) <> 9000 THEN
        RAISE EXCEPTION 'V66: una cuenta sin ficha no guardo su cupo';
    END IF;

    -- 4. Un campo fuera del catálogo sigue rechazado.
    BEGIN
        INSERT INTO clientes_eventos (tenant_id, cliente_id, campo) SELECT a, id, 'saldo' FROM clientes WHERE tenant_id = a LIMIT 1;
        RAISE EXCEPTION 'V66: clientes_eventos admitio el campo saldo';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM clientes_eventos    WHERE tenant_id = a;
    DELETE FROM accounts_receivable WHERE tenant_id = a;
    DELETE FROM clientes            WHERE tenant_id = a;
    DELETE FROM users               WHERE tenant_id = a;
    DELETE FROM tenants             WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
                AND c.table_schema IN ('public', 'inventario', 'pedidos', 'red')
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id = $1', t.s, t.tn) INTO n USING a;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V66: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V66: el cupo deja evento con autor; cupo 0 al abrir, total_debt y cuentas sin ficha no escriben.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- DROP TRIGGER IF EXISTS trg_ar_cupo_eventos ON accounts_receivable; DROP FUNCTION IF EXISTS fn_cupo_eventos();
-- (CHECK de clientes_eventos: el de V64, sin 'cupo'; antes borrar los eventos 'cupo')
