-- =====================================================================
-- V2 (cadena `pedidos`) -- El pedido: su cabecera, sus líneas, su historia de
--                          eventos y la única función que lo mueve.
--
-- Plan de mayoristas F5.1 (M-D1, D6), con las decisiones de ECM del 2026-09-14 y
-- el contraste de DevEspecializadoEnB2BYpedidos contra R1-R12 / D1 / D4 / D7 / D8
-- del plan de la red. Solo en staging hasta que haya mayorista real y reversa (D7).
--
-- ── Lo que hay ────────────────────────────────────────────────────────
--
-- 1. `pedidos.pedidos`: dueño = el PROVEEDOR (`tenant_id`). `estado` es un índice
--    verificado: lo escribe solo la función, igual al tipo del último evento.
--    `plazo_dias` con la semántica de V65: NULL = sin plazo pactado, 0 = contraentrega.
-- 2. `pedidos.pedidos_lineas`: lo pedido y su precio. Las cantidades confirmada,
--    despachada, entregada y recibida NO son columnas: salen de los eventos.
-- 3. `pedidos.pedidos_eventos`: solo anexar. El orden es `secuencia` (la asigna la
--    función bajo FOR UPDATE), no `ocurrido_en`, que llega desordenado desde un
--    celular sin señal. Sin `corrige_evento_id`: no hay tipo de corrección (ECM).
-- 4. `pedidos.pedidos_eventos_lineas`: la cantidad (y, en un ajuste de precio, el
--    precio) de cada línea en cada evento que las declara. Siempre la cantidad
--    RESULTANTE: la vista toma la última, sin adivinar.
-- 5. `pedidos.transiciones`: el catálogo (desde, hacia, actor). Global, sin negocio.
-- 6. `pedidos.fn_pedido_crear` y `pedidos.fn_pedido_transicionar`: los únicos
--    caminos de escritura. `app_user` solo LEE las tablas y EJECUTA las funciones.
--    El actor lo deriva la función de la sesión (`app.tenant_id`, `app.user_id`),
--    nunca llega como parámetro.
-- 7. `pedidos.v_pedidos_lineas` (cantidades por línea, sin estado PARCIAL) y
--    `pedidos.v_pedidos_estado` (guardado frente a derivado).
--
-- ── Guarda de F5 (la retira la migración de la red cuando se levante C1) ─
--
-- `comprador_tenant_id`, `relacion_id` y `acceso_id` NULL, y `origen` no es
-- `canal_app` ni `enlace`: todo pedido de F5 nace sin comprador. Así la red no
-- tendrá que rellenar filas de solo anexar al colgar su lectura de dos partes
-- (CHECK `ck_pedidos_sin_canal_en_f5`, además de la función). El actor COMPRADOR,
-- por la misma guarda, no es posible en F5; SISTEMA llega con F5.4 por su vía.
--
-- ── Aislamiento ───────────────────────────────────────────────────────
--
-- RLS de UN negocio (el proveedor) en las cuatro tablas, FORCE (ECM: la lectura de
-- dos partes llega con la red y su guarda). Las funciones son SECURITY DEFINER y su
-- dueño salta RLS (medido en staging: `postgres`, BYPASSRLS).
--
-- 🔴 DENTRO DE LAS FUNCIONES, RLS NO ES SUELO: el único aislamiento es el filtro de
-- negocio ESCRITO en cada consulta, con el negocio sacado de la sesión. Por eso
-- el control negativo sin ese filtro es obligatorio (lo tiene ElPedidoTest y el
-- cierre de abajo). Y por ser DEFINER: `search_path` fijo con `pg_temp` al final
-- (sin él, un objeto temporal del que llama podría suplantar un nombre), y EXECUTE
-- revocado a PUBLIC y dado solo a `app_user`; el cierre comprueba las dos cosas.
-- Llaman a `public.fn_precio_para`, que también corre como dueño: su filtro por
-- negocio es el de V59, así que esta cadena NO llega a producción antes que V59.
--
-- IMPACTO: cinco tablas nuevas, un contador, dos funciones y dos vistas en el
-- esquema `pedidos`, vacío hasta hoy. Nada en `public`.
-- =====================================================================

SET lock_timeout = '3s';

-- ── 1 · Catálogo de transiciones ────────────────────────────────────
CREATE TABLE pedidos.transiciones (
    desde  TEXT NULL,               -- NULL = el pedido nace
    hacia  TEXT NOT NULL,
    actor  TEXT NOT NULL,
    CONSTRAINT ck_transiciones_actor CHECK (actor IN ('PROVEEDOR', 'COMPRADOR', 'SISTEMA')),
    CONSTRAINT ux_transiciones UNIQUE NULLS NOT DISTINCT (desde, hacia, actor)
);
COMMENT ON TABLE pedidos.transiciones IS
    'Quien puede mover un pedido de que estado a cual. Global (sin negocio): catalogo de solo lectura. V2 pedidos.';

INSERT INTO pedidos.transiciones (desde, hacia, actor)
SELECT d, h, a FROM (VALUES
    (NULL, 'CREADO_BORRADOR', 'PROVEEDOR'),                -- el carrito del comprador no es un pedido (red)
    (NULL, 'ENVIADO', 'PROVEEDOR'), (NULL, 'ENVIADO', 'COMPRADOR'),
    ('CREADO_BORRADOR', 'ENVIADO', 'PROVEEDOR'),
    ('CREADO_BORRADOR', 'CANCELADO', 'PROVEEDOR'),
    ('ENVIADO', 'CONFIRMADO', 'PROVEEDOR'), ('ENVIADO', 'AJUSTADO', 'PROVEEDOR'),
    ('ENVIADO', 'RECHAZADO', 'PROVEEDOR'),  ('ENVIADO', 'RETENIDO', 'PROVEEDOR'),
    ('ENVIADO', 'CANCELADO', 'PROVEEDOR'),  ('ENVIADO', 'CANCELADO', 'COMPRADOR'),
    ('AJUSTADO', 'CANCELADO', 'PROVEEDOR'), ('AJUSTADO', 'CANCELADO', 'COMPRADOR'),
    ('RETENIDO', 'CANCELADO', 'PROVEEDOR'), ('RETENIDO', 'CANCELADO', 'COMPRADOR'),
    ('LIBERADO', 'CANCELADO', 'PROVEEDOR'), ('LIBERADO', 'CANCELADO', 'COMPRADOR'),
    ('RETENIDO', 'LIBERADO', 'PROVEEDOR'),  ('RETENIDO', 'RECHAZADO', 'PROVEEDOR'),
    ('LIBERADO', 'CONFIRMADO', 'PROVEEDOR'),
    ('AJUSTADO', 'CONFIRMADO', 'PROVEEDOR'),
    ('CONFIRMADO', 'DESPACHADO', 'PROVEEDOR'), ('CONFIRMADO', 'CANCELADO', 'PROVEEDOR'),
    ('DESPACHADO', 'ENTREGADO', 'PROVEEDOR'), ('DESPACHADO', 'ENTREGADO_CON_NOVEDAD', 'PROVEEDOR'),
    ('DESPACHADO', 'ENTREGA_FALLIDA', 'PROVEEDOR'),
    ('ENTREGA_FALLIDA', 'DESPACHADO', 'PROVEEDOR'),        -- reprogramar (D8: segundo despacho)
    ('ENTREGA_FALLIDA', 'CANCELADO', 'PROVEEDOR'),
    ('DESPACHADO', 'RECIBIDO', 'COMPRADOR'), ('DESPACHADO', 'RECIBIDO_CON_NOVEDAD', 'COMPRADOR'),
    ('ENTREGADO', 'RECIBIDO', 'COMPRADOR'), ('ENTREGADO', 'RECIBIDO_CON_NOVEDAD', 'COMPRADOR'),
    ('ENTREGADO_CON_NOVEDAD', 'RECIBIDO', 'COMPRADOR'), ('ENTREGADO_CON_NOVEDAD', 'RECIBIDO_CON_NOVEDAD', 'COMPRADOR')
) AS t(d, h, a);

-- ── 2 · El pedido ───────────────────────────────────────────────────
CREATE TABLE pedidos.contadores_de_pedidos (
    tenant_id TEXT   NOT NULL,
    ultimo    BIGINT NOT NULL,
    CONSTRAINT pk_contadores_de_pedidos PRIMARY KEY (tenant_id),
    CONSTRAINT ck_contadores_de_pedidos CHECK (ultimo >= 1)
);

CREATE TABLE pedidos.pedidos (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id               TEXT        NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), ''),
    numero                  BIGINT      NOT NULL,
    cliente_documento       TEXT        NULL,
    comprador_tenant_id     TEXT        NULL,
    relacion_id             UUID        NULL,       -- red.relaciones_comerciales: SIN FK (la añade la red, NOT VALID)
    origen                  TEXT        NOT NULL,
    modalidad               TEXT        NOT NULL DEFAULT 'PREVENTA',
    capturado_por           BIGINT      NULL REFERENCES public.users (id),
    acceso_id               UUID        NULL,       -- red.accesos_por_enlace: SIN FK (la añade la red)
    vendedor_id             BIGINT      NULL REFERENCES public.users (id),
    site_id                 BIGINT      NULL,
    estado                  TEXT        NOT NULL,
    plazo_dias              SMALLINT    NULL,
    condicion_pago          TEXT        NOT NULL DEFAULT 'CONTADO',
    fecha_entrega_prometida DATE        NULL,
    hora_corte_aplicada     TIME        NULL,
    lista_precio_id         UUID        NULL,
    precio_congelado_en     TIMESTAMPTZ NULL,
    despachado_en           TIMESTAMPTZ NULL,
    entregado_en            TIMESTAMPTZ NULL,
    recibido_en             TIMESTAMPTZ NULL,
    order_uuid              UUID        NULL,
    ocurrido_en             TIMESTAMPTZ NOT NULL,
    registrado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key         TEXT        NOT NULL,
    CONSTRAINT pk_pedidos PRIMARY KEY (id),
    CONSTRAINT ux_pedidos_negocio_id UNIQUE (tenant_id, id),
    CONSTRAINT ux_pedidos_numero UNIQUE (tenant_id, numero),
    CONSTRAINT ux_pedidos_idempotencia UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_pedidos_origen CHECK (origen IN ('vendedor', 'televenta', 'canal_app', 'enlace', 'mostrador')),
    CONSTRAINT ck_pedidos_modalidad CHECK (modalidad IN ('PREVENTA', 'AUTOVENTA')),
    CONSTRAINT ck_pedidos_estado CHECK (estado IN ('CREADO_BORRADOR', 'ENVIADO', 'CONFIRMADO', 'AJUSTADO', 'RETENIDO',
        'LIBERADO', 'RECHAZADO', 'CANCELADO', 'DESPACHADO', 'ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA',
        'RECIBIDO', 'RECIBIDO_CON_NOVEDAD')),
    CONSTRAINT ck_pedidos_plazo CHECK (plazo_dias IS NULL OR plazo_dias BETWEEN 0 AND 365),
    CONSTRAINT ck_pedidos_condicion CHECK (condicion_pago IN ('CONTADO', 'CREDITO')),
    CONSTRAINT ck_pedidos_hay_comprador CHECK (cliente_documento IS NOT NULL OR comprador_tenant_id IS NOT NULL),
    CONSTRAINT ck_pedidos_cliente_del_mayorista CHECK (origen NOT IN ('vendedor', 'televenta', 'mostrador') OR cliente_documento IS NOT NULL),
    CONSTRAINT ck_pedidos_quien_captura CHECK (capturado_por IS NOT NULL OR acceso_id IS NOT NULL),
    CONSTRAINT ck_pedidos_reloj CHECK (ocurrido_en <= registrado_en + interval '5 minutes'),
    -- Guarda de F5: la retira la migración de la red cuando se levante C1.
    CONSTRAINT ck_pedidos_sin_canal_en_f5 CHECK (comprador_tenant_id IS NULL AND relacion_id IS NULL
        AND acceso_id IS NULL AND origen NOT IN ('canal_app', 'enlace'))
);
CREATE INDEX ix_pedidos_bandeja ON pedidos.pedidos (tenant_id, estado, ocurrido_en DESC);
CREATE INDEX ix_pedidos_cliente ON pedidos.pedidos (tenant_id, cliente_documento) WHERE cliente_documento IS NOT NULL;
CREATE INDEX ix_pedidos_vendedor ON pedidos.pedidos (tenant_id, vendedor_id) WHERE vendedor_id IS NOT NULL;
COMMENT ON TABLE pedidos.pedidos IS
    'El pedido con entrega diferida, del PROVEEDOR. estado = tipo del ultimo evento (lo escribe solo '
    'fn_pedido_transicionar). plazo_dias: NULL sin pactar, 0 contraentrega (V65). V2 pedidos.';

CREATE TABLE pedidos.pedidos_lineas (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            TEXT          NOT NULL,
    pedido_id            UUID          NOT NULL,
    n                    SMALLINT      NOT NULL,
    producto_id          TEXT          NOT NULL,
    cantidad_pedida      INTEGER       NOT NULL,
    precio_visto         NUMERIC(15,2) NULL,      -- fn_precio_para con momento = captura (Q2)
    precio_confirmado    NUMERIC(15,2) NULL,      -- congelado al CONFIRMAR: el de la captura, salvo ERROR_DE_PRECIO
    precio_origen        TEXT          NULL,
    lista_precio_item_id UUID          NULL,
    CONSTRAINT pk_pedidos_lineas PRIMARY KEY (id),
    CONSTRAINT ux_pedidos_lineas_pedido_id UNIQUE (tenant_id, pedido_id, id),
    CONSTRAINT ux_pedidos_lineas_n UNIQUE (pedido_id, n),
    CONSTRAINT fk_pedidos_lineas_pedido FOREIGN KEY (tenant_id, pedido_id) REFERENCES pedidos.pedidos (tenant_id, id),
    CONSTRAINT ck_pedidos_lineas_cantidad CHECK (cantidad_pedida > 0),
    CONSTRAINT ck_pedidos_lineas_precio_origen CHECK (precio_origen IS NULL OR precio_origen IN ('LISTA', 'BASE'))
);

CREATE TABLE pedidos.pedidos_eventos (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        TEXT        NOT NULL,
    pedido_id        UUID        NOT NULL,
    secuencia        INTEGER     NOT NULL,
    tipo             TEXT        NOT NULL,
    actor            TEXT        NOT NULL,
    actor_tenant_id  TEXT        NULL,
    actor_usuario_id BIGINT      NULL,
    acceso_id        UUID        NULL,
    motivo           TEXT        NULL,
    nota             TEXT        NULL,
    ocurrido_en      TIMESTAMPTZ NOT NULL,
    registrado_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key  TEXT        NOT NULL,
    CONSTRAINT pk_pedidos_eventos PRIMARY KEY (id),
    CONSTRAINT ux_pedidos_eventos_pedido_id UNIQUE (tenant_id, pedido_id, id),
    CONSTRAINT ux_pedidos_eventos_secuencia UNIQUE (pedido_id, secuencia),
    CONSTRAINT ux_pedidos_eventos_idempotencia UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_pedidos_eventos_pedido FOREIGN KEY (tenant_id, pedido_id) REFERENCES pedidos.pedidos (tenant_id, id),
    CONSTRAINT ck_pedidos_eventos_secuencia CHECK (secuencia >= 1),
    CONSTRAINT ck_pedidos_eventos_tipo CHECK (tipo IN ('CREADO_BORRADOR', 'ENVIADO', 'CONFIRMADO', 'AJUSTADO', 'RETENIDO',
        'LIBERADO', 'RECHAZADO', 'CANCELADO', 'DESPACHADO', 'ENTREGADO', 'ENTREGADO_CON_NOVEDAD', 'ENTREGA_FALLIDA',
        'RECIBIDO', 'RECIBIDO_CON_NOVEDAD')),
    CONSTRAINT ck_pedidos_eventos_actor CHECK (actor IN ('PROVEEDOR', 'COMPRADOR', 'SISTEMA')),
    CONSTRAINT ck_pedidos_eventos_motivo CHECK (motivo IS NULL OR motivo IN (
        'CUPO_EXCEDIDO', 'FACTURA_VENCIDA', 'MORA', 'SIN_EXISTENCIA', 'PRODUCTO_DESCONTINUADO', 'ERROR_DE_PRECIO',
        'CLIENTE_DESISTIO', 'DUPLICADO', 'CERRADO', 'SIN_DINERO', 'FUERA_DE_VENTANA', 'DIRECCION_ERRADA', 'RECHAZO_EN_PUERTA',
        'FALTANTE', 'SOBRANTE', 'AVERIA', 'PRODUCTO_NO_PEDIDO', 'VENCIDO')),
    CONSTRAINT ck_pedidos_eventos_motivo_obligatorio CHECK ((tipo IN ('RETENIDO', 'RECHAZADO', 'CANCELADO', 'ENTREGA_FALLIDA',
        'ENTREGADO_CON_NOVEDAD', 'RECIBIDO_CON_NOVEDAD')) = (motivo IS NOT NULL) OR (tipo = 'AJUSTADO')),
    -- El rastro nunca atribuye al proveedor lo que hizo un enlace: con enlace no hay negocio; sin él, sí.
    CONSTRAINT ck_pedidos_eventos_quien CHECK ((acceso_id IS NOT NULL AND actor = 'COMPRADOR' AND actor_tenant_id IS NULL)
        OR (acceso_id IS NULL AND actor_tenant_id IS NOT NULL)),
    CONSTRAINT ck_pedidos_eventos_reloj CHECK (ocurrido_en <= registrado_en + interval '5 minutes')
);

CREATE TABLE pedidos.pedidos_eventos_lineas (
    tenant_id  TEXT          NOT NULL,
    pedido_id  UUID          NOT NULL,
    evento_id  UUID          NOT NULL,
    linea_id   UUID          NOT NULL,
    cantidad   INTEGER       NOT NULL,
    precio     NUMERIC(15,2) NULL,        -- solo en AJUSTADO con ERROR_DE_PRECIO (lo impone la función)
    CONSTRAINT pk_pedidos_eventos_lineas PRIMARY KEY (evento_id, linea_id),
    -- El evento y la línea son del MISMO pedido y del mismo negocio.
    CONSTRAINT fk_pel_evento FOREIGN KEY (tenant_id, pedido_id, evento_id) REFERENCES pedidos.pedidos_eventos (tenant_id, pedido_id, id),
    CONSTRAINT fk_pel_linea  FOREIGN KEY (tenant_id, pedido_id, linea_id)  REFERENCES pedidos.pedidos_lineas (tenant_id, pedido_id, id),
    CONSTRAINT ck_pel_cantidad CHECK (cantidad >= 0),
    CONSTRAINT ck_pel_precio CHECK (precio IS NULL OR precio >= 0)
);
CREATE INDEX ix_pel_linea ON pedidos.pedidos_eventos_lineas (tenant_id, linea_id);

-- ── 3 · Aislamiento: un solo negocio, FORCE ─────────────────────────
DO $rls$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['pedidos', 'pedidos_lineas', 'pedidos_eventos', 'pedidos_eventos_lineas', 'contadores_de_pedidos'] LOOP
        EXECUTE format('ALTER TABLE pedidos.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE pedidos.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation_%I ON pedidos.%I
            USING (tenant_id = current_setting('app.tenant_id', true))
            WITH CHECK (tenant_id = current_setting('app.tenant_id', true))$p$, t, t);
    END LOOP;
END
$rls$;

-- ── 4 · Las vistas ──────────────────────────────────────────────────
CREATE VIEW pedidos.v_pedidos_lineas WITH (security_invoker = true) AS
WITH ultimos AS (
    SELECT DISTINCT ON (el.linea_id, grupo)
           el.linea_id, e.secuencia, el.cantidad,
           CASE WHEN e.tipo IN ('CONFIRMADO', 'AJUSTADO') THEN 'confirmada'
                WHEN e.tipo = 'DESPACHADO' THEN 'despachada'
                WHEN e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD') THEN 'entregada'
                ELSE 'recibida' END AS grupo
      FROM pedidos.pedidos_eventos_lineas el
      JOIN pedidos.pedidos_eventos e ON e.tenant_id = el.tenant_id AND e.pedido_id = el.pedido_id AND e.id = el.evento_id
     ORDER BY el.linea_id, grupo, e.secuencia DESC
)
SELECT l.tenant_id, l.pedido_id, l.id AS linea_id, l.n, l.producto_id,
       l.cantidad_pedida                                     AS pedida,
       c.cantidad                                            AS confirmada,
       d.cantidad                                            AS despachada,
       -- Una entrega vale si es posterior al último despacho (un ENTREGA_FALLIDA y un segundo despacho la anulan).
       CASE WHEN en.secuencia > d.secuencia THEN en.cantidad END AS entregada,
       CASE WHEN r.secuencia > d.secuencia THEN r.cantidad END   AS recibida,
       COALESCE(c.cantidad, l.cantidad_pedida)
         - COALESCE(CASE WHEN en.secuencia > d.secuencia THEN en.cantidad END, 0) AS pendiente,
       l.precio_visto, l.precio_confirmado, l.precio_origen, l.lista_precio_item_id
  FROM pedidos.pedidos_lineas l
  LEFT JOIN ultimos c  ON c.linea_id = l.id  AND c.grupo = 'confirmada'
  LEFT JOIN ultimos d  ON d.linea_id = l.id  AND d.grupo = 'despachada'
  LEFT JOIN ultimos en ON en.linea_id = l.id AND en.grupo = 'entregada'
  LEFT JOIN ultimos r  ON r.linea_id = l.id  AND r.grupo = 'recibida';
COMMENT ON VIEW pedidos.v_pedidos_lineas IS
    'Cantidades por linea derivadas de los eventos: pedida, confirmada, despachada, entregada, recibida, pendiente. '
    'Sin estado PARCIAL. Filtrar SIEMPRE por tenant_id. V2 pedidos.';

CREATE VIEW pedidos.v_pedidos_estado WITH (security_invoker = true) AS
SELECT p.tenant_id, p.id AS pedido_id, p.estado AS estado_guardado, u.tipo AS estado_derivado, u.secuencia AS ultima_secuencia
  FROM pedidos.pedidos p
  LEFT JOIN LATERAL (SELECT e.tipo, e.secuencia FROM pedidos.pedidos_eventos e
                      WHERE e.tenant_id = p.tenant_id AND e.pedido_id = p.id
                      ORDER BY e.secuencia DESC LIMIT 1) u ON true;
COMMENT ON VIEW pedidos.v_pedidos_estado IS
    'estado guardado frente al derivado (tipo del evento de mayor secuencia). Deben coincidir siempre. V2 pedidos.';

-- ── 5 · La escritura: dos funciones, y nada más ─────────────────────
CREATE FUNCTION pedidos.fn_sesion_proveedor(OUT negocio TEXT, OUT usuario BIGINT)
LANGUAGE plpgsql STABLE AS $$
DECLARE v_usuario TEXT := NULLIF(current_setting('app.user_id', true), '');
BEGIN
    negocio := NULLIF(current_setting('app.tenant_id', true), '');
    IF negocio IS NULL THEN
        RAISE EXCEPTION 'Pedido sin negocio en la sesion.' USING ERRCODE = 'P0001';
    END IF;
    IF v_usuario IS NULL OR v_usuario !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'Pedido sin usuario en la sesion: el rastro no admite un autor desconocido.' USING ERRCODE = 'P0001';
    END IF;
    usuario := v_usuario::BIGINT;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = usuario AND u.tenant_id = negocio) THEN
        RAISE EXCEPTION 'El usuario % no es de este negocio.', usuario USING ERRCODE = 'P0001';
    END IF;
END $$;

/*
 * Inserta el evento y sus líneas y actualiza la cabecera. INTERNA: la llaman las
 * dos funciones públicas con el pedido ya bloqueado (FOR UPDATE) y validado.
 * p_lineas: [{"linea_id": uuid, "cantidad": int, "precio": numeric?}]. Para los
 * eventos que declaran cantidades, las líneas que no vengan conservan su cantidad
 * vigente; así cada evento guarda la cantidad RESULTANTE de TODAS sus líneas.
 */
CREATE FUNCTION pedidos.fn_pedido_escribir_evento(p_pedido pedidos.pedidos, p_tipo TEXT, p_actor TEXT, p_usuario BIGINT,
                                                  p_motivo TEXT, p_nota TEXT, p_lineas JSONB, p_ocurrido_en TIMESTAMPTZ,
                                                  p_idempotency_key TEXT)
RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE
    v_evento UUID := gen_random_uuid();
    v_secuencia INTEGER;
    v_grupo TEXT := CASE WHEN p_tipo IN ('CONFIRMADO', 'AJUSTADO') THEN 'confirmada'
                         WHEN p_tipo = 'DESPACHADO' THEN 'despachada'
                         WHEN p_tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD') THEN 'entregada' END;
    v_desconocidas INT;
BEGIN
    SELECT COALESCE(max(e.secuencia), 0) + 1 INTO v_secuencia
      FROM pedidos.pedidos_eventos e WHERE e.tenant_id = p_pedido.tenant_id AND e.pedido_id = p_pedido.id;

    INSERT INTO pedidos.pedidos_eventos (id, tenant_id, pedido_id, secuencia, tipo, actor, actor_tenant_id, actor_usuario_id,
                                         motivo, nota, ocurrido_en, idempotency_key)
    VALUES (v_evento, p_pedido.tenant_id, p_pedido.id, v_secuencia, p_tipo, p_actor, p_pedido.tenant_id, p_usuario,
            p_motivo, p_nota, p_ocurrido_en, p_idempotency_key);

    IF p_lineas IS NOT NULL AND jsonb_typeof(p_lineas) = 'array' AND jsonb_array_length(p_lineas) > 0 THEN
        SELECT count(*) INTO v_desconocidas
          FROM jsonb_array_elements(p_lineas) x
         WHERE NOT EXISTS (SELECT 1 FROM pedidos.pedidos_lineas l
                            WHERE l.tenant_id = p_pedido.tenant_id AND l.pedido_id = p_pedido.id
                              AND l.id = (x ->> 'linea_id')::uuid);
        IF v_desconocidas > 0 THEN
            RAISE EXCEPTION 'Una linea no es de este pedido.' USING ERRCODE = 'P0001';
        END IF;
        IF v_grupo IS NULL THEN
            RAISE EXCEPTION 'El evento % no declara cantidades por linea.', p_tipo USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_lineas) x WHERE x ? 'precio' AND x ->> 'precio' IS NOT NULL)
           AND NOT (p_tipo = 'AJUSTADO' AND p_motivo = 'ERROR_DE_PRECIO') THEN
            RAISE EXCEPTION 'Un precio por linea solo va en un AJUSTADO con motivo ERROR_DE_PRECIO.' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF p_tipo = 'AJUSTADO' AND p_motivo = 'ERROR_DE_PRECIO'
       AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(p_lineas, '[]')) x WHERE x ->> 'precio' IS NOT NULL) THEN
        RAISE EXCEPTION 'Un ajuste por ERROR_DE_PRECIO dice el precio nuevo de al menos una linea.' USING ERRCODE = 'P0001';
    END IF;

    IF v_grupo IS NOT NULL THEN
        -- Cada línea del pedido: la cantidad declarada, o la vigente de su etapa (o la anterior).
        INSERT INTO pedidos.pedidos_eventos_lineas (tenant_id, pedido_id, evento_id, linea_id, cantidad, precio)
        SELECT p_pedido.tenant_id, p_pedido.id, v_evento, l.id,
               COALESCE((x ->> 'cantidad')::int,
                        CASE v_grupo
                            WHEN 'confirmada' THEN COALESCE(v.confirmada, v.pedida)
                            WHEN 'despachada' THEN COALESCE(v.confirmada, v.pedida)
                            WHEN 'entregada'  THEN COALESCE(v.despachada, 0)
                        END),
               (x ->> 'precio')::numeric
          FROM pedidos.pedidos_lineas l
          JOIN pedidos.v_pedidos_lineas v ON v.tenant_id = l.tenant_id AND v.linea_id = l.id
          LEFT JOIN jsonb_array_elements(COALESCE(p_lineas, '[]')) x ON (x ->> 'linea_id')::uuid = l.id
         WHERE l.tenant_id = p_pedido.tenant_id AND l.pedido_id = p_pedido.id;
    END IF;

    UPDATE pedidos.pedidos p
       SET estado = p_tipo,
           despachado_en = CASE WHEN p_tipo = 'DESPACHADO' THEN p_ocurrido_en ELSE p.despachado_en END,
           entregado_en  = CASE WHEN p_tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD') THEN p_ocurrido_en ELSE p.entregado_en END
     WHERE p.tenant_id = p_pedido.tenant_id AND p.id = p_pedido.id;
    RETURN v_evento;
END $$;

/*
 * Crear un pedido: la cabecera, sus líneas con el precio del MOMENTO de la captura
 * (fn_precio_para con momento = p_ocurrido_en, Q2) y su primer evento. Nace en
 * CREADO_BORRADOR o ENVIADO. Idempotente por (negocio, clave): el reintento
 * devuelve el mismo pedido.
 * p_lineas: [{"producto_id": text, "cantidad": int}]
 */
CREATE FUNCTION pedidos.fn_pedido_crear(p_tipo_inicial TEXT, p_cliente_documento TEXT, p_origen TEXT, p_modalidad TEXT,
                                        p_vendedor_id BIGINT, p_site_id BIGINT, p_fecha_entrega_prometida DATE,
                                        p_lineas JSONB, p_ocurrido_en TIMESTAMPTZ, p_idempotency_key TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_pedido pedidos.pedidos%ROWTYPE;
    v_existente UUID;
    v_cliente RECORD;
    v_numero BIGINT;
    v_linea JSONB;
    v_n SMALLINT := 0;
    v_precio RECORD;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RAISE EXCEPTION 'Pedido sin clave de idempotencia.' USING ERRCODE = 'P0001';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('pedido:' || s.negocio || ':' || p_idempotency_key));
    SELECT id INTO v_existente FROM pedidos.pedidos WHERE tenant_id = s.negocio AND idempotency_key = p_idempotency_key;
    IF v_existente IS NOT NULL THEN
        RETURN v_existente;
    END IF;

    IF p_tipo_inicial NOT IN ('CREADO_BORRADOR', 'ENVIADO') THEN
        RAISE EXCEPTION 'Un pedido nace en CREADO_BORRADOR o ENVIADO, no en %.', p_tipo_inicial USING ERRCODE = 'P0001';
    END IF;
    IF p_origen IN ('canal_app', 'enlace') THEN
        RAISE EXCEPTION 'El origen % es del canal entre negocios, que aun no esta abierto.', p_origen USING ERRCODE = 'P0001';
    END IF;
    IF p_ocurrido_en IS NULL OR p_ocurrido_en > now() + interval '5 minutes' THEN
        RAISE EXCEPTION 'El pedido no puede ser del futuro.' USING ERRCODE = 'P0001';
    END IF;
    IF p_lineas IS NULL OR jsonb_typeof(p_lineas) <> 'array' OR jsonb_array_length(p_lineas) = 0 THEN
        RAISE EXCEPTION 'Un pedido necesita al menos una linea.' USING ERRCODE = 'P0001';
    END IF;
    SELECT c.plazo_dias, c.lista_precio_id, c.activo INTO v_cliente
      FROM public.clientes c WHERE c.tenant_id = s.negocio AND c.documento = btrim(p_cliente_documento);
    IF v_cliente IS NULL OR NOT v_cliente.activo THEN
        RAISE EXCEPTION 'El cliente % no es un cliente activo de este negocio.', p_cliente_documento USING ERRCODE = 'P0001';
    END IF;
    IF p_vendedor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_vendedor_id AND u.tenant_id = s.negocio) THEN
        RAISE EXCEPTION 'El vendedor % no es de este negocio.', p_vendedor_id USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO pedidos.contadores_de_pedidos AS c (tenant_id, ultimo) VALUES (s.negocio, 1)
    ON CONFLICT (tenant_id) DO UPDATE SET ultimo = c.ultimo + 1 RETURNING c.ultimo INTO v_numero;

    INSERT INTO pedidos.pedidos (tenant_id, numero, cliente_documento, origen, modalidad, capturado_por, vendedor_id, site_id,
                                 estado, plazo_dias, condicion_pago, fecha_entrega_prometida, lista_precio_id,
                                 ocurrido_en, idempotency_key)
    VALUES (s.negocio, v_numero, btrim(p_cliente_documento), p_origen, COALESCE(p_modalidad, 'PREVENTA'), s.usuario,
            p_vendedor_id, p_site_id, p_tipo_inicial, v_cliente.plazo_dias,
            CASE WHEN v_cliente.plazo_dias IS NULL THEN 'CONTADO' ELSE 'CREDITO' END,
            p_fecha_entrega_prometida, v_cliente.lista_precio_id, p_ocurrido_en, p_idempotency_key)
    RETURNING * INTO v_pedido;

    FOR v_linea IN SELECT value FROM jsonb_array_elements(p_lineas) LOOP
        v_n := v_n + 1;
        -- fn_precio_para filtra por el negocio de la sesión (V59): aquí corre con el mismo app.tenant_id.
        SELECT * INTO v_precio FROM public.fn_precio_para(btrim(p_cliente_documento), v_linea ->> 'producto_id',
                                                         (v_linea ->> 'cantidad')::int, p_ocurrido_en);
        IF v_precio IS NULL THEN
            RAISE EXCEPTION 'El producto % no existe en este negocio.', v_linea ->> 'producto_id' USING ERRCODE = 'P0001';
        END IF;
        INSERT INTO pedidos.pedidos_lineas (tenant_id, pedido_id, n, producto_id, cantidad_pedida, precio_visto,
                                            precio_origen, lista_precio_item_id)
        VALUES (s.negocio, v_pedido.id, v_n, v_linea ->> 'producto_id', (v_linea ->> 'cantidad')::int, v_precio.precio,
                v_precio.origen, v_precio.lista_precio_item_id);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pedidos.transiciones WHERE desde IS NULL AND hacia = p_tipo_inicial AND actor = 'PROVEEDOR') THEN
        RAISE EXCEPTION 'El proveedor no puede crear un pedido en %.', p_tipo_inicial USING ERRCODE = 'P0001';
    END IF;
    PERFORM pedidos.fn_pedido_escribir_evento(v_pedido, p_tipo_inicial, 'PROVEEDOR', s.usuario, NULL, NULL, NULL,
                                              p_ocurrido_en, p_idempotency_key || ':creado');
    IF p_tipo_inicial = 'ENVIADO' THEN
        UPDATE pedidos.pedidos SET precio_congelado_en = p_ocurrido_en WHERE tenant_id = s.negocio AND id = v_pedido.id;
    END IF;
    RETURN v_pedido.id;
END $$;

/*
 * Mover un pedido. El actor sale de la sesión: PROVEEDOR (F5). Valida contra el
 * catálogo con el pedido bloqueado; idempotente por (negocio, clave).
 * p_lineas: [{"linea_id": uuid, "cantidad": int, "precio": numeric?}]
 */
CREATE FUNCTION pedidos.fn_pedido_transicionar(p_pedido_id UUID, p_tipo TEXT, p_motivo TEXT, p_nota TEXT, p_lineas JSONB,
                                               p_ocurrido_en TIMESTAMPTZ, p_idempotency_key TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pedidos, public, pg_temp AS $$
DECLARE
    s RECORD;
    v_pedido pedidos.pedidos%ROWTYPE;
    v_existente RECORD;
    v_evento UUID;
BEGIN
    SELECT * INTO s FROM pedidos.fn_sesion_proveedor();
    IF p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RAISE EXCEPTION 'Evento sin clave de idempotencia.' USING ERRCODE = 'P0001';
    END IF;
    -- El pedido, de ESTE negocio (el dueño de la función salta RLS: el filtro va escrito), bloqueado.
    SELECT * INTO v_pedido FROM pedidos.pedidos WHERE tenant_id = s.negocio AND id = p_pedido_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Ese pedido no existe en este negocio.' USING ERRCODE = 'P0001';
    END IF;
    SELECT id, pedido_id, tipo INTO v_existente FROM pedidos.pedidos_eventos
     WHERE tenant_id = s.negocio AND idempotency_key = p_idempotency_key;
    IF v_existente.id IS NOT NULL THEN
        IF v_existente.pedido_id <> p_pedido_id OR v_existente.tipo <> p_tipo THEN
            RAISE EXCEPTION 'La clave % ya se uso para otro evento.', p_idempotency_key USING ERRCODE = 'P0001';
        END IF;
        RETURN v_existente.id;
    END IF;
    IF p_ocurrido_en IS NULL OR p_ocurrido_en > now() + interval '5 minutes' THEN
        RAISE EXCEPTION 'El evento no puede ser del futuro.' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pedidos.transiciones t
                    WHERE t.desde = v_pedido.estado AND t.hacia = p_tipo AND t.actor = 'PROVEEDOR') THEN
        RAISE EXCEPTION 'Un pedido % no pasa a % por el proveedor.', v_pedido.estado, p_tipo USING ERRCODE = 'P0001';
    END IF;
    IF p_tipo = 'AJUSTADO' AND (p_lineas IS NULL OR jsonb_array_length(p_lineas) = 0) THEN
        RAISE EXCEPTION 'Un ajuste dice que lineas cambian.' USING ERRCODE = 'P0001';
    END IF;
    IF p_tipo = 'ENTREGADO_CON_NOVEDAD' AND (p_lineas IS NULL OR jsonb_array_length(p_lineas) = 0) THEN
        RAISE EXCEPTION 'Una entrega con novedad dice que se entrego de cada linea.' USING ERRCODE = 'P0001';
    END IF;

    v_evento := pedidos.fn_pedido_escribir_evento(v_pedido, p_tipo, 'PROVEEDOR', s.usuario, p_motivo, p_nota, p_lineas,
                                                  p_ocurrido_en, p_idempotency_key);

    IF p_tipo = 'ENVIADO' THEN   -- desde un borrador: el precio es el del momento del envío (Q2)
        UPDATE pedidos.pedidos_lineas l
           SET (precio_visto, precio_origen, lista_precio_item_id) =
               (SELECT f.precio, f.origen, f.lista_precio_item_id
                  FROM public.fn_precio_para(v_pedido.cliente_documento, l.producto_id, l.cantidad_pedida, p_ocurrido_en) f)
         WHERE l.tenant_id = s.negocio AND l.pedido_id = p_pedido_id;
        UPDATE pedidos.pedidos SET precio_congelado_en = p_ocurrido_en WHERE tenant_id = s.negocio AND id = p_pedido_id;
    ELSIF p_tipo = 'AJUSTADO' AND p_motivo = 'ERROR_DE_PRECIO' THEN
        UPDATE pedidos.pedidos_lineas l
           SET precio_confirmado = el.precio
          FROM pedidos.pedidos_eventos_lineas el
         WHERE el.tenant_id = s.negocio AND el.evento_id = v_evento AND el.linea_id = l.id AND el.precio IS NOT NULL
           AND l.tenant_id = s.negocio AND l.pedido_id = p_pedido_id;
    ELSIF p_tipo = 'CONFIRMADO' THEN
        -- Se congela el precio de la captura; el que se corrigió a propósito (ERROR_DE_PRECIO) se respeta.
        UPDATE pedidos.pedidos_lineas l
           SET precio_confirmado = COALESCE(l.precio_confirmado, l.precio_visto)
         WHERE l.tenant_id = s.negocio AND l.pedido_id = p_pedido_id;
    END IF;
    RETURN v_evento;
END $$;

REVOKE ALL ON FUNCTION pedidos.fn_pedido_crear(TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT, DATE, JSONB, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_pedido_transicionar(UUID, TEXT, TEXT, TEXT, JSONB, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_pedido_escribir_evento(pedidos.pedidos, TEXT, TEXT, BIGINT, TEXT, TEXT, JSONB, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pedidos.fn_sesion_proveedor() FROM PUBLIC;

DO $permisos$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
        -- Solo leer y ejecutar las dos funciones públicas: ningún INSERT ni UPDATE directo.
        GRANT SELECT ON pedidos.pedidos, pedidos.pedidos_lineas, pedidos.pedidos_eventos, pedidos.pedidos_eventos_lineas,
                        pedidos.transiciones, pedidos.v_pedidos_lineas, pedidos.v_pedidos_estado TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_pedido_crear(TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT, DATE, JSONB, TIMESTAMPTZ, TEXT) TO app_user;
        GRANT EXECUTE ON FUNCTION pedidos.fn_pedido_transicionar(UUID, TEXT, TEXT, TEXT, JSONB, TIMESTAMPTZ, TEXT) TO app_user;
    END IF;
END
$permisos$;

-- =====================================================================
-- La comprobación, por comportamiento.
-- =====================================================================
DO $cierre$
DECLARE
    a CONSTANT TEXT := '__prueba_v2p_a__';
    b CONSTANT TEXT := '__prueba_v2p_b__';
    v_admin BIGINT; v_otro BIGINT;
    v_pedido UUID; v_otro_pedido UUID; v_linea1 UUID; v_linea2 UUID; v_linea_ajena UUID;
    v_e1 UUID; v_e2 UUID;
    v_rechazado BOOLEAN; v_texto TEXT;
    t RECORD; v_filas BIGINT; quedan BIGINT := 0; donde TEXT := '';
BEGIN
    -- Las vistas nacen con security_invoker.
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
                WHERE ns.nspname = 'pedidos' AND c.relkind = 'v'
                  AND NOT COALESCE(c.reloptions @> ARRAY['security_invoker=true'], false)) THEN
        RAISE EXCEPTION 'V2 pedidos: una vista del pedido no tiene security_invoker';
    END IF;

    -- DEFINER con search_path fijo (pg_temp al final) y sin EXECUTE para PUBLIC.
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
                WHERE ns.nspname = 'pedidos' AND p.prosecdef
                  AND NOT COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pedidos, public, pg_temp'], false)) THEN
        RAISE EXCEPTION 'V2 pedidos: una funcion SECURITY DEFINER no tiene el search_path fijo con pg_temp al final';
    END IF;
    IF (SELECT count(*) FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
         WHERE ns.nspname = 'pedidos' AND p.prosecdef) <> 2 THEN
        RAISE EXCEPTION 'V2 pedidos: se esperaban exactamente dos funciones SECURITY DEFINER';
    END IF;
    IF has_function_privilege('public', 'pedidos.fn_pedido_crear(text, text, text, text, bigint, bigint, date, jsonb, timestamp with time zone, text)', 'EXECUTE')
       OR has_function_privilege('public', 'pedidos.fn_pedido_transicionar(uuid, text, text, text, jsonb, timestamp with time zone, text)', 'EXECUTE')
       OR has_function_privilege('public', 'pedidos.fn_pedido_escribir_evento(pedidos.pedidos, text, text, bigint, text, text, jsonb, timestamp with time zone, text)', 'EXECUTE') THEN
        RAISE EXCEPTION 'V2 pedidos: PUBLIC puede ejecutar una funcion del pedido';
    END IF;

    INSERT INTO public.tenants (id, name, plan) VALUES (a, 'Prueba V2 pedidos A', 'basico'), (b, 'Prueba V2 pedidos B', 'basico');
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v2p-a@prueba.invalid', '!', a, 'admin') RETURNING id INTO v_admin;
    INSERT INTO public.users (email, password_hash, tenant_id, role) VALUES ('v2p-b@prueba.invalid', '!', b, 'admin') RETURNING id INTO v_otro;
    INSERT INTO public.menu_products (id_product, tenant_id, name_product, price, active)
    VALUES ('v2p-aceite', a, 'Aceite', 10000, true), ('v2p-arroz', a, 'Arroz', 5000, true), ('v2p-aceite-b', b, 'Aceite', 10000, true);
    INSERT INTO public.clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES
        (a, 'v2p-tienda', 'Tienda', 8, 'v2p'), (b, 'v2p-tienda', 'Tienda de B', NULL, 'v2p');

    PERFORM set_config('app.tenant_id', a, true);
    PERFORM set_config('app.user_id', v_admin::text, true);

    -- 1. Crear: precio de la captura, plazo congelado del cliente, idempotente.
    v_pedido := pedidos.fn_pedido_crear('ENVIADO', 'v2p-tienda', 'vendedor', 'PREVENTA', v_admin, NULL, NULL,
        '[{"producto_id":"v2p-aceite","cantidad":20},{"producto_id":"v2p-arroz","cantidad":5}]', now() - interval '1 hour', 'v2p-1');
    IF pedidos.fn_pedido_crear('ENVIADO', 'v2p-tienda', 'vendedor', 'PREVENTA', v_admin, NULL, NULL,
        '[{"producto_id":"v2p-aceite","cantidad":1}]', now(), 'v2p-1') <> v_pedido THEN
        RAISE EXCEPTION 'V2 pedidos: el reintento de crear no devolvio el mismo pedido';
    END IF;
    SELECT id INTO v_linea1 FROM pedidos.pedidos_lineas WHERE pedido_id = v_pedido AND n = 1;
    SELECT id INTO v_linea2 FROM pedidos.pedidos_lineas WHERE pedido_id = v_pedido AND n = 2;
    IF (SELECT precio_visto FROM pedidos.pedidos_lineas WHERE id = v_linea1) <> 10000
       OR (SELECT plazo_dias FROM pedidos.pedidos WHERE id = v_pedido) <> 8
       OR (SELECT condicion_pago FROM pedidos.pedidos WHERE id = v_pedido) <> 'CREDITO'
       OR (SELECT numero FROM pedidos.pedidos WHERE id = v_pedido) <> 1
       OR (SELECT count(*) FROM pedidos.pedidos WHERE tenant_id = a) <> 1 THEN
        RAISE EXCEPTION 'V2 pedidos: el pedido no nacio con precio, plazo, condicion y numero';
    END IF;

    -- 2. Transición no permitida → P0001.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL, NULL, now(), 'v2p-mal');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: ENVIADO paso a DESPACHADO sin confirmar'; END IF;

    -- 3. El comprador no confirma: el catálogo no tiene la fila, y en F5 no hay actor COMPRADOR.
    IF EXISTS (SELECT 1 FROM pedidos.transiciones WHERE hacia = 'CONFIRMADO' AND actor = 'COMPRADOR') THEN
        RAISE EXCEPTION 'V2 pedidos: el catalogo deja al comprador confirmar';
    END IF;

    -- 4. Guarda de F5: con comprador o con origen de canal → rechazo.
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_crear('ENVIADO', 'v2p-tienda', 'enlace', 'PREVENTA', NULL, NULL, NULL,
            '[{"producto_id":"v2p-aceite","cantidad":1}]', now(), 'v2p-enlace');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: un pedido de enlace entro en F5'; END IF;
    v_rechazado := false;
    BEGIN
        UPDATE pedidos.pedidos SET comprador_tenant_id = b WHERE id = v_pedido;
    EXCEPTION WHEN check_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: un pedido de F5 admitio comprador'; END IF;

    -- 5. Ajuste de cantidad (sin motivo) y de precio (ERROR_DE_PRECIO con precio); confirmar congela.
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'AJUSTADO', 'SIN_EXISTENCIA', NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea1, 'cantidad', 18)), now(), 'v2p-ajuste');
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'CONFIRMADO', NULL, NULL,
            jsonb_build_array(jsonb_build_object('linea_id', v_linea2, 'cantidad', 5, 'precio', 1)), now(), 'v2p-precio-mal');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: un precio por linea entro fuera de un ajuste de precio'; END IF;
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v2p-confirma');
    IF (SELECT confirmada FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea1) <> 18
       OR (SELECT confirmada FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea2) <> 5
       OR (SELECT precio_confirmado FROM pedidos.pedidos_lineas WHERE id = v_linea1) <> 10000 THEN
        RAISE EXCEPTION 'V2 pedidos: confirmar no dejo 18 y 5 con el precio de la captura';
    END IF;

    -- 6. Un evento con la línea de OTRO pedido → rechazo (función y FK compuesta).
    v_otro_pedido := pedidos.fn_pedido_crear('ENVIADO', 'v2p-tienda', 'televenta', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v2p-arroz","cantidad":2}]', now(), 'v2p-2');
    SELECT id INTO v_linea_ajena FROM pedidos.pedidos_lineas WHERE pedido_id = v_otro_pedido;
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL,
            jsonb_build_array(jsonb_build_object('linea_id', v_linea_ajena, 'cantidad', 2)), now(), 'v2p-ajena');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: un evento uso la linea de otro pedido'; END IF;
    v_rechazado := false;
    BEGIN
        SELECT id INTO v_e1 FROM pedidos.pedidos_eventos WHERE pedido_id = v_pedido ORDER BY secuencia DESC LIMIT 1;
        INSERT INTO pedidos.pedidos_eventos_lineas (tenant_id, pedido_id, evento_id, linea_id, cantidad)
        VALUES (a, v_pedido, v_e1, v_linea_ajena, 1);
    EXCEPTION WHEN foreign_key_violation THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: la FK compuesta dejo pasar la linea de otro pedido'; END IF;

    -- 7. Despachar 17, entrega fallida, segundo despacho de 18, entregado; idempotencia del evento.
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL,
        jsonb_build_array(jsonb_build_object('linea_id', v_linea1, 'cantidad', 17)), now(), 'v2p-despacho-1');
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'ENTREGA_FALLIDA', 'CERRADO', NULL, NULL, now(), 'v2p-fallida');
    v_e2 := pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL, NULL, now(), 'v2p-despacho-2');
    IF pedidos.fn_pedido_transicionar(v_pedido, 'DESPACHADO', NULL, NULL, NULL, now(), 'v2p-despacho-2') <> v_e2 THEN
        RAISE EXCEPTION 'V2 pedidos: el reintento de un evento no devolvio el mismo evento';
    END IF;
    IF (SELECT despachada FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea1) <> 18
       OR (SELECT entregada FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea1) IS NOT NULL THEN
        RAISE EXCEPTION 'V2 pedidos: el segundo despacho no dejo 18 despachadas y sin entrega';
    END IF;
    PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'ENTREGADO', NULL, NULL, NULL, now(), 'v2p-entregado');
    IF (SELECT entregada FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea1) <> 18
       OR (SELECT pendiente FROM pedidos.v_pedidos_lineas WHERE linea_id = v_linea1) <> 0
       OR (SELECT entregado_en FROM pedidos.pedidos WHERE id = v_pedido) IS NULL THEN
        RAISE EXCEPTION 'V2 pedidos: la entrega no quedo en la vista ni en la cabecera';
    END IF;

    -- 8. Estado guardado = derivado, en todos los pedidos; y manda la secuencia, no ocurrido_en.
    PERFORM pedidos.fn_pedido_transicionar(v_otro_pedido, 'CONFIRMADO', NULL, NULL, NULL, now(), 'v2p-2-confirma');
    PERFORM pedidos.fn_pedido_transicionar(v_otro_pedido, 'CANCELADO', 'CLIENTE_DESISTIO', NULL, NULL,
        now() - interval '3 days', 'v2p-2-cancela');   -- ocurrido ANTES que el confirmado, registrado después
    IF EXISTS (SELECT 1 FROM pedidos.v_pedidos_estado WHERE tenant_id = a AND estado_guardado IS DISTINCT FROM estado_derivado)
       OR (SELECT estado_guardado FROM pedidos.v_pedidos_estado WHERE pedido_id = v_otro_pedido) <> 'CANCELADO' THEN
        RAISE EXCEPTION 'V2 pedidos: el estado guardado no coincide con el derivado por secuencia';
    END IF;

    -- 8b. Un borrador que se envía después: el precio es el del momento del ENVIADO (Q2), no el de la creación.
    v_e1 := pedidos.fn_pedido_crear('CREADO_BORRADOR', 'v2p-tienda', 'vendedor', 'PREVENTA', NULL, NULL, NULL,
        '[{"producto_id":"v2p-arroz","cantidad":3}]', now() - interval '2 hours', 'v2p-borrador');
    UPDATE public.menu_products SET price = 6000 WHERE id_product = 'v2p-arroz';
    PERFORM pedidos.fn_pedido_transicionar(v_e1, 'ENVIADO', NULL, NULL, NULL, now(), 'v2p-borrador-envia');
    IF (SELECT precio_visto FROM pedidos.pedidos_lineas WHERE pedido_id = v_e1) <> 6000
       OR (SELECT precio_congelado_en FROM pedidos.pedidos WHERE id = v_e1) IS NULL THEN
        RAISE EXCEPTION 'V2 pedidos: enviar un borrador no tomo el precio del momento del envio';
    END IF;

    -- 9. Sin usuario en la sesión → P0001; otro negocio no mueve ni ve el pedido.
    PERFORM set_config('app.user_id', '', true);
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_otro_pedido, 'CANCELADO', 'DUPLICADO', NULL, NULL, now(), 'v2p-sin-usuario');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN v_rechazado := true;
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: un evento entro sin usuario'; END IF;
    PERFORM set_config('app.tenant_id', b, true);
    PERFORM set_config('app.user_id', v_otro::text, true);
    v_rechazado := false;
    BEGIN
        PERFORM pedidos.fn_pedido_transicionar(v_pedido, 'RECIBIDO', NULL, NULL, NULL, now(), 'v2p-b-intenta');
    EXCEPTION WHEN SQLSTATE 'P0001' THEN
        GET STACKED DIAGNOSTICS v_texto = MESSAGE_TEXT;
        v_rechazado := v_texto LIKE '%no existe en este negocio%';
    END;
    IF NOT v_rechazado THEN RAISE EXCEPTION 'V2 pedidos: otro negocio llego al pedido (%)', v_texto; END IF;

    -- Limpieza (hijos antes que padres) y barrido de todas las tablas con tenant_id.
    PERFORM set_config('app.tenant_id', '', true);
    PERFORM set_config('app.user_id', '', true);
    DELETE FROM pedidos.pedidos_eventos_lineas WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos_eventos        WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos_lineas         WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.pedidos                WHERE tenant_id IN (a, b);
    DELETE FROM pedidos.contadores_de_pedidos  WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes_eventos        WHERE tenant_id IN (a, b);
    DELETE FROM public.clientes                WHERE tenant_id IN (a, b);
    DELETE FROM public.menu_products           WHERE tenant_id IN (a, b);
    DELETE FROM public.users                   WHERE tenant_id IN (a, b);
    DELETE FROM public.tenants                 WHERE id IN (a, b);

    FOR t IN SELECT c.table_schema s, c.table_name tn
               FROM information_schema.columns c
               JOIN information_schema.tables x
                 ON x.table_schema = c.table_schema AND x.table_name = c.table_name
                AND x.table_type = 'BASE TABLE'
              WHERE c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE tenant_id::text IN ($1, $2)', t.s, t.tn) INTO v_filas USING a, b;
        IF v_filas > 0 THEN
            quedan := quedan + v_filas;
            donde := donde || t.s || '.' || t.tn || '(' || v_filas || ') ';
        END IF;
    END LOOP;
    IF quedan > 0 OR EXISTS (SELECT 1 FROM public.tenants WHERE id IN (a, b)) THEN
        RAISE EXCEPTION 'V2 pedidos: la comprobacion dejo % filas de prueba en %', quedan, donde;
    END IF;

    RAISE NOTICE 'V2 pedidos: crear idempotente con precio de la captura, transiciones del catalogo, guarda de F5, ajuste y confirmacion, linea ajena rechazada, segundo despacho, estado guardado = derivado por secuencia, sin usuario y otro negocio rechazados.';
END
$cierre$;
