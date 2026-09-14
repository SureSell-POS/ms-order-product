-- =====================================================================
-- V67 -- El NIT con su dígito de verificación, en su propia columna.
--
-- Plan de mayoristas F4.10 (parte de -mt; preparación de F9, factura).
--
-- ── Lo que había ──────────────────────────────────────────────────────
--
-- `clientes.documento` (TEXT, clave por negocio) y `tipo_documento` (V62). El DV
-- del NIT no tenía sitio: o no se guardaba o viajaba pegado al número
-- («900123456-7»), y nada comprobaba que correspondiera. Un NIT con un dígito mal
-- tecleado sale en la factura electrónica y la DIAN la rechaza.
--
-- ── Lo que se añade ───────────────────────────────────────────────────
--
-- 1. `fn_dv_nit(numero)`: el algoritmo de la DIAN (pesos 3, 7, 13, 17, 19, 23,
--    29, 37, 41, 43, 47, 53, 59, 67, 71 desde la derecha; residuo módulo 11; si
--    es 0 o 1, ese es el DV; si no, 11 − residuo). IMMUTABLE: solo se evalúa al
--    escribir un cliente.
-- 2. `clientes.dv` SMALLINT y un CHECK: si hay DV, el cliente es NIT, el
--    documento es solo dígitos y el DV es el que da la DIAN. Con NULL en
--    `tipo_documento` o un documento no numérico el CHECK tiene que dar FALSO,
--    no NULL (COALESCE): comprobado abajo con una fila de cada caso.
-- 3. `dv` en `clientes_eventos` (CHECK y función redefinida desde el cuerpo que
--    devolvió `pg_get_functiondef` en staging el 2026-09-14, md5
--    dfbc619a829d72dd3edaf3dd72f63595 = el de V64, con una fila más).
-- 4. Reparación: un cliente con el DV pegado («NNN-D») se separa SOLO si el DV
--    es válido, el número no choca con otro cliente del negocio y ni la cartera
--    ni las ventas nombran todavía el documento viejo (cambiar la clave las
--    dejaría huérfanas). Los demás se dejan como están y se listan en el log.
--    Medido antes, 2026-09-14: staging 1 cliente, ninguno con DV pegado;
--    producción 0 clientes. Si separa alguno, deja constancia en
--    `inventario.reparaciones_de_datos` con los ids.
--
-- El municipio DANE ya tiene su CHECK de 5 dígitos desde V62 (medido: 0 filas
-- fuera de formato en staging).
--
-- IMPACTO: una columna nula y un CHECK sobre `clientes` (tabla pequeña), una
-- función inmutable y la función del disparador redefinida.
-- =====================================================================

SET lock_timeout = '3s';

CREATE OR REPLACE FUNCTION public.fn_dv_nit(p_numero TEXT)
RETURNS SMALLINT LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE WHEN p_numero !~ '^[0-9]{1,15}$' THEN NULL ELSE (
        SELECT CASE WHEN x.s % 11 IN (0, 1) THEN x.s % 11 ELSE 11 - x.s % 11 END
          FROM (SELECT sum(substr(reverse(p_numero), i, 1)::int
                           * (ARRAY[3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71])[i]) AS s
                  FROM generate_series(1, length(p_numero)) AS i) x
    )::smallint END
$$;

COMMENT ON FUNCTION public.fn_dv_nit(TEXT) IS
    'Digito de verificacion de un NIT (algoritmo DIAN, modulo 11). NULL si no son solo digitos (1 a 15). V67.';

ALTER TABLE clientes ADD COLUMN IF NOT EXISTS dv SMALLINT NULL;
ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_dv;
ALTER TABLE clientes ADD CONSTRAINT ck_clientes_dv CHECK (
    dv IS NULL
    OR (COALESCE(tipo_documento, '') = 'NIT'
        AND COALESCE(public.fn_dv_nit(documento) = dv, false))
);
COMMENT ON COLUMN clientes.dv IS
    'Digito de verificacion del NIT, aparte del numero. Solo con tipo_documento = NIT; la base comprueba que sea el de la DIAN. V67.';

ALTER TABLE clientes_eventos DROP CONSTRAINT IF EXISTS ck_clientes_eventos_campo;
ALTER TABLE clientes_eventos ADD CONSTRAINT ck_clientes_eventos_campo CHECK (campo IN (
    'nombre', 'lista_precio_id', 'plazo_dias', 'activo', 'vendedor_id', 'tipo_cliente',
    'exige_factura', 'tipo_documento', 'razon_social', 'direccion_entrega', 'municipio_dane',
    'correo', 'whatsapp', 'telefono', 'en_insolvencia_desde', 'cupo', 'dv'));

-- El cuerpo vigente (V64), con una fila más.
CREATE OR REPLACE FUNCTION fn_clientes_eventos()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_autor_txt TEXT := NULLIF(current_setting('app.user_id', true), '');
    v_autor     BIGINT := CASE WHEN v_autor_txt ~ '^[0-9]+$' THEN v_autor_txt::BIGINT END;
    v_campo     TEXT;
    v_antes     TEXT;
    v_despues   TEXT;
    v_escritos  INT := 0;
BEGIN
    FOR v_campo, v_antes, v_despues IN
        SELECT c.campo, c.antes, c.despues FROM (VALUES
            ('nombre',               OLD.nombre,                       NEW.nombre),
            ('lista_precio_id',      OLD.lista_precio_id::text,        NEW.lista_precio_id::text),
            ('plazo_dias',           OLD.plazo_dias::text,             NEW.plazo_dias::text),
            ('activo',               OLD.activo::text,                 NEW.activo::text),
            ('vendedor_id',          OLD.vendedor_id::text,            NEW.vendedor_id::text),
            ('tipo_cliente',         OLD.tipo_cliente,                 NEW.tipo_cliente),
            ('exige_factura',        OLD.exige_factura::text,          NEW.exige_factura::text),
            ('tipo_documento',       OLD.tipo_documento,               NEW.tipo_documento),
            ('razon_social',         OLD.razon_social,                 NEW.razon_social),
            ('direccion_entrega',    OLD.direccion_entrega,            NEW.direccion_entrega),
            ('municipio_dane',       OLD.municipio_dane,               NEW.municipio_dane),
            ('correo',               OLD.correo,                       NEW.correo),
            ('whatsapp',             OLD.whatsapp,                     NEW.whatsapp),
            ('telefono',             OLD.telefono,                     NEW.telefono),
            ('en_insolvencia_desde', OLD.en_insolvencia_desde::text,   NEW.en_insolvencia_desde::text),
            ('dv',                   OLD.dv::text,                     NEW.dv::text)
        ) AS c(campo, antes, despues)
        WHERE c.antes IS DISTINCT FROM c.despues
    LOOP
        INSERT INTO public.clientes_eventos (tenant_id, cliente_id, campo, valor_anterior, valor_nuevo, usuario_id)
        VALUES (NEW.tenant_id, NEW.id, v_campo, v_antes, v_despues, v_autor);
        v_escritos := v_escritos + 1;
    END LOOP;
    IF v_escritos > 0 AND v_autor IS NULL THEN
        RAISE WARNING 'clientes_eventos sin autor: cliente % del negocio % cambio % campos sin app.user_id',
            NEW.id, NEW.tenant_id, v_escritos;
    END IF;
    RETURN NULL;
END $$;

-- ── 4 · Reparación de los que traen el DV pegado ────────────────────
-- En pg_temp: la usa esta migración (sobre los datos reales) y su comprobación
-- (sobre un negocio de prueba), y no queda en el esquema.
CREATE FUNCTION pg_temp.separar_dv_pegado(p_negocio TEXT, OUT separados UUID[], OUT dejados TEXT[])
LANGUAGE plpgsql AS $$
DECLARE
    r RECORD;
    v_numero TEXT;
    v_dv SMALLINT;
    v_motivo TEXT;
BEGIN
    separados := ARRAY[]::UUID[];
    dejados := ARRAY[]::TEXT[];
    FOR r IN SELECT c.id, c.tenant_id, c.documento, c.tipo_documento
               FROM public.clientes c
              WHERE c.documento ~ '^[0-9]+-[0-9]$'
                AND (p_negocio IS NULL OR c.tenant_id = p_negocio)
                AND (p_negocio IS NOT NULL OR c.tenant_id NOT LIKE '\_\_prueba\_%')
    LOOP
        v_numero := split_part(r.documento, '-', 1);
        v_dv := split_part(r.documento, '-', 2)::smallint;
        v_motivo := CASE
            WHEN COALESCE(r.tipo_documento, 'NIT') <> 'NIT' THEN 'tipo ' || r.tipo_documento || ' con DV'
            WHEN public.fn_dv_nit(v_numero) <> v_dv THEN 'DV invalido (deberia ser ' || public.fn_dv_nit(v_numero) || ')'
            WHEN EXISTS (SELECT 1 FROM public.clientes o WHERE o.tenant_id = r.tenant_id AND o.documento = v_numero)
                THEN 'el numero sin DV ya es otro cliente'
            WHEN EXISTS (SELECT 1 FROM public.accounts_receivable a WHERE a.tenant_id = r.tenant_id AND a.customer_document = r.documento)
                THEN 'tiene cuenta por cobrar con el documento pegado'
            WHEN EXISTS (SELECT 1 FROM public.orders o WHERE o.tenant_id = r.tenant_id AND o.cliente_documento = r.documento)
                THEN 'tiene ventas con el documento pegado'
        END;
        IF v_motivo IS NULL THEN
            UPDATE public.clientes
               SET documento = v_numero, dv = v_dv, tipo_documento = 'NIT', actualizado_en = now()
             WHERE id = r.id;
            separados := separados || r.id;
        ELSE
            dejados := dejados || (r.tenant_id || '/' || r.id || ' ' || r.documento || ': ' || v_motivo);
        END IF;
    END LOOP;
END $$;

DO $reparacion$
DECLARE
    v RECORD;
BEGIN
    SELECT * INTO v FROM pg_temp.separar_dv_pegado(NULL);
    IF cardinality(v.dejados) > 0 THEN
        RAISE WARNING 'V67: % clientes con DV pegado se dejan como estan: %', cardinality(v.dejados), array_to_string(v.dejados, ' | ');
    END IF;
    IF cardinality(v.separados) > 0 AND to_regclass('inventario.reparaciones_de_datos') IS NOT NULL THEN
        INSERT INTO inventario.reparaciones_de_datos (migracion, filas, verificado, rol, nota)
        VALUES ('V67__el_nit_con_su_digito_de_verificacion', cardinality(v.separados), true, current_user,
                'clientes con DV pegado separados en documento + dv; ids: ' || array_to_string(v.separados, ','));
    END IF;
    RAISE NOTICE 'V67: reparacion sobre datos reales: % separados, % dejados.', cardinality(v.separados), cardinality(v.dejados);
END
$reparacion$;

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v67_a__';
    v RECORD;
    v_rechazado BOOLEAN;
    v_fila RECORD;
    t RECORD; n BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- 1. El algoritmo con NIT públicos: DIAN 800197268-4, Bancolombia 890903938-8, Ecopetrol 899999068-1.
    IF fn_dv_nit('800197268') <> 4 OR fn_dv_nit('890903938') <> 8 OR fn_dv_nit('899999068') <> 1
       OR fn_dv_nit('80019726A') IS NOT NULL OR fn_dv_nit('') IS NOT NULL THEN
        RAISE EXCEPTION 'V67: fn_dv_nit no da los DV de la DIAN (% % %)',
            fn_dv_nit('800197268'), fn_dv_nit('890903938'), fn_dv_nit('899999068');
    END IF;

    INSERT INTO tenants (id, name, plan) VALUES (a, 'Prueba V67', 'basico');
    PERFORM set_config('app.tenant_id', a, true);

    -- 2. El CHECK: bien con NIT y DV correcto; FALSO (no NULL) con DV errado, tipo CC,
    --    tipo NULL o documento no numérico. Una fila por caso.
    INSERT INTO clientes (tenant_id, documento, nombre, tipo_documento, dv, creado_por)
    VALUES (a, '800197268', 'DIAN', 'NIT', 4, 'v67');
    FOR v_fila IN SELECT * FROM (VALUES
            ('890903938', 'NIT', 5::smallint, 'DV errado'),
            ('890903939', 'CC',  8::smallint, 'tipo CC'),
            ('890903940', NULL,  fn_dv_nit('890903940'), 'tipo NULL'),
            ('8909039-38', 'NIT', 8::smallint, 'documento no numerico')
        ) AS x(doc, tipo, dv, caso)
    LOOP
        v_rechazado := false;
        BEGIN
            INSERT INTO clientes (tenant_id, documento, nombre, tipo_documento, dv, creado_por)
            VALUES (a, v_fila.doc, 'X', v_fila.tipo, v_fila.dv, 'v67');
        EXCEPTION WHEN check_violation THEN
            v_rechazado := true;
        END;
        IF NOT v_rechazado THEN
            RAISE EXCEPTION 'V67: ck_clientes_dv dejo pasar el caso «%»', v_fila.caso;
        END IF;
    END LOOP;

    -- 3. La reparación: separa el válido y libre; deja el de DV inválido, el que choca y el que tiene cartera.
    INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES
        (a, '890903938-8', 'Pegado valido', 'v67'),
        (a, '899999068-7', 'Pegado invalido', 'v67'),
        (a, '800197268-4', 'Choca con la DIAN', 'v67'),
        (a, '860034313-7', 'Con cartera', 'v67');
    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name,
                                     status, total_debt, updated_at)
    VALUES (gen_random_uuid()::text, a, now(), 0, '860034313-7', 'Con cartera', 'ACTIVE', 0, now());
    SELECT * INTO v FROM pg_temp.separar_dv_pegado(a);
    IF cardinality(v.separados) <> 1 OR cardinality(v.dejados) <> 3
       OR NOT EXISTS (SELECT 1 FROM clientes WHERE tenant_id = a AND documento = '890903938' AND dv = 8 AND tipo_documento = 'NIT')
       OR NOT EXISTS (SELECT 1 FROM clientes WHERE tenant_id = a AND documento = '899999068-7' AND dv IS NULL)
       OR NOT EXISTS (SELECT 1 FROM clientes_eventos e JOIN clientes c ON c.id = e.cliente_id
                       WHERE c.tenant_id = a AND c.documento = '890903938' AND e.campo = 'dv' AND e.valor_nuevo = '8') THEN
        RAISE EXCEPTION 'V67: la reparacion separo % y dejo %: %', cardinality(v.separados), cardinality(v.dejados),
            array_to_string(v.dejados, ' | ');
    END IF;

    PERFORM set_config('app.tenant_id', '', true);
    DELETE FROM accounts_receivable WHERE tenant_id = a;
    DELETE FROM clientes_eventos    WHERE tenant_id = a;
    DELETE FROM clientes            WHERE tenant_id = a;
    DELETE FROM tenants             WHERE id = a;

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text = $1', t.s, t.tn) INTO n USING a;
        IF n > 0 THEN
            quedan := quedan + n;
            donde := donde || t.s || '.' || t.tn || '(' || n || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM tenants WHERE id = a) THEN
        RAISE EXCEPTION 'V67: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V67: DV de la DIAN correcto en tres NIT publicos; el CHECK rechaza DV errado, tipo CC, tipo NULL y documento no numerico; la reparacion separa solo el valido y libre, y registra el evento dv.';
END
$cierre$;

-- =====================================================================
-- DOWN
-- =====================================================================
-- ALTER TABLE clientes DROP CONSTRAINT IF EXISTS ck_clientes_dv, DROP COLUMN IF EXISTS dv;
-- DROP FUNCTION IF EXISTS fn_dv_nit(TEXT);
-- (CHECK de clientes_eventos y fn_clientes_eventos: los de V64/V66, sin 'dv'; antes borrar eventos 'dv')
-- Los clientes separados por la reparación: ver inventario.reparaciones_de_datos.
