package com.suresell.orders.pedidos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.shared.exception.ClienteInactivoException;
import com.suresell.orders.shared.exception.DatoInvalidoException;
import com.suresell.orders.shared.exception.PedidoRechazadoException;
import com.suresell.orders.shared.exception.SoloAdministradorException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El pedido del lado del proveedor por la API (plan de mayoristas F5.3, contrato §7.5).
 *
 * <h3>Las reglas que no se negocian</h3>
 * <ul>
 *   <li><b>Se escribe solo por las dos funciones</b> de la cadena {@code pedidos} (V2):
 *       {@code fn_pedido_crear} y {@code fn_pedido_transicionar}. La aplicación no tiene
 *       INSERT ni UPDATE sobre esas tablas. El autor sale de {@code app.user_id}, que se
 *       fija en la transacción; sin usuario del negocio no hay pedido.</li>
 *   <li><b>El precio es el de la captura (Q2):</b> lo resuelve la función al crear, con
 *       el {@code ocurridoEn} de la captura, y CONFIRMADO lo congela. Una lista que sube
 *       después no lo mueve; solo un AJUSTADO con {@code ERROR_DE_PRECIO}, a propósito.</li>
 *   <li><b>El negocio va escrito en cada lectura:</b> RLS es el suelo, no la regla.</li>
 *   <li><b>Un vendedor</b> toma pedidos a su nombre y ve solo los suyos (vendedor o
 *       capturados por él). Confirmar es de admin y cajero; rechazar, cancelar, retener
 *       y liberar, de admin.</li>
 * </ul>
 */
@Service
public class Pedidos {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    public static final Set<String> ORIGENES = Set.of("vendedor", "televenta", "mostrador");
    /** Todos los orígenes del enum de V2: la bandeja filtra también los del canal cuando la red los abra. */
    public static final List<String> ORIGENES_DEL_ENUM = List.of("vendedor", "televenta", "canal_app", "enlace", "mostrador");
    public static final Set<String> MODALIDADES = Set.of("PREVENTA", "AUTOVENTA");
    public static final List<String> ESTADOS = List.of("CREADO_BORRADOR", "ENVIADO", "CONFIRMADO", "AJUSTADO", "RETENIDO",
            "LIBERADO", "RECHAZADO", "CANCELADO", "DESPACHADO", "ENTREGADO", "ENTREGADO_CON_NOVEDAD", "ENTREGA_FALLIDA",
            "RECIBIDO", "RECIBIDO_CON_NOVEDAD");
    /** Motivos por evento: los mismos que impone `ck_pedidos_eventos_motivo_por_tipo` (V2 de pedidos). */
    public static final Set<String> MOTIVOS_DE_AJUSTE = Set.of("SIN_EXISTENCIA", "PRODUCTO_DESCONTINUADO", "ERROR_DE_PRECIO", "DUPLICADO");
    public static final Set<String> MOTIVOS_DE_RECHAZO = Set.of("SIN_EXISTENCIA", "PRODUCTO_DESCONTINUADO", "DUPLICADO",
            "CUPO_EXCEDIDO", "FACTURA_VENCIDA", "MORA", "FUERA_DE_VENTANA");
    public static final Set<String> MOTIVOS_DE_CANCELACION = Set.of("CLIENTE_DESISTIO", "DUPLICADO", "SIN_EXISTENCIA", "FUERA_DE_VENTANA");
    public static final Set<String> MOTIVOS_DE_RETENCION = Set.of("CUPO_EXCEDIDO", "FACTURA_VENCIDA", "MORA");
    public static final Set<String> MOTIVOS_DE_LIBERACION = Set.of("PAGO_RECIBIDO", "ACUERDO_DE_PAGO", "AUTORIZADO_POR_ADMIN");
    public static final Set<String> RESULTADOS_DE_ENTREGA = Set.of("ENTREGADO", "ENTREGADO_CON_NOVEDAD", "ENTREGA_FALLIDA");
    public static final Set<String> MOTIVOS_DE_NOVEDAD = Set.of("FALTANTE", "SOBRANTE", "AVERIA", "PRODUCTO_NO_PEDIDO", "VENCIDO");
    public static final Set<String> MOTIVOS_DE_ENTREGA_FALLIDA = Set.of("CERRADO", "SIN_DINERO", "DIRECCION_ERRADA", "RECHAZO_EN_PUERTA", "FUERA_DE_VENTANA");
    /**
     * Lo despachado ya es venta (F5.5): cancelarlo sin la reversa dejaría la venta, su deuda y el
     * inventario descontado (D7). ENTREGA_FALLIDA también: la mercancía salió con su venta.
     */
    static final Set<String> YA_DESPACHADO = Set.of("DESPACHADO", "ENTREGADO", "ENTREGADO_CON_NOVEDAD", "ENTREGA_FALLIDA");
    static final Set<String> CONFIRMAN = Set.of("admin", "cajero");
    static final int MAX_LINEAS = 500;
    static final int LIMITE_DE_BANDEJA = 50;
    static final int LIMITE_MAXIMO_DE_BANDEJA = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.suresell.orders.domain.port.in.OrderPort ventas;
    private final com.suresell.orders.cartera.Cartera cartera;

    public Pedidos(JdbcTemplate jdbc, ObjectMapper json, com.suresell.orders.domain.port.in.OrderPort ventas,
                   com.suresell.orders.cartera.Cartera cartera) {
        this.jdbc = jdbc;
        this.json = json;
        this.ventas = ventas;
        this.cartera = cartera;
    }

    /** Quién pide: su negocio, su rol y su id de {@code users}. */
    public record Quien(String negocio, String rol, Long usuarioId) {
        boolean esVendedor() {
            return "vendedor".equals(rol);
        }

        boolean esAdmin() {
            return "admin".equals(rol);
        }
    }

    public record LineaNueva(String productoId, Integer cantidad) {}

    /** {@code confirmar}: solo cuenta para admin y cajero; ausente = true. */
    public record PedidoNuevo(String clienteDocumento, String origen, String modalidad, Long vendedorId, Long siteId,
                              LocalDate entregaEl, List<LineaNueva> lineas, OffsetDateTime ocurridoEn,
                              String idempotencyKey, Boolean confirmar) {}

    public record LineaDeEvento(UUID lineaId, Integer cantidad, BigDecimal precio) {}

    public record QuienRecibe(String nombre, String documento) {}

    public record ReciboEnLaEntrega(BigDecimal monto, String medio, String referenciaMedio) {}

    /**
     * POST /api/pedidos/{id}/entregar. Sin foto ni firma hasta F5.7b (ECM): el cuerpo las rechaza
     * como campos desconocidos.
     */
    public record Entrega(String resultado, List<LineaDeEvento> lineas, String motivo, String nota, QuienRecibe recibe,
                          BigDecimal latitud, BigDecimal longitud, ReciboEnLaEntrega recibo, OffsetDateTime ocurridoEn,
                          String idempotencyKey) {}

    /** POST /api/pedidos/{id}/despachar. */
    public record Despacho(List<LineaDeEvento> lineas, Long siteId, String nota, OffsetDateTime ocurridoEn,
                           String idempotencyKey) {}

    /** El cuerpo de toda acción sobre un pedido; cada una usa lo suyo. */
    public record Accion(List<LineaDeEvento> lineas, String motivo, String nota, OffsetDateTime ocurridoEn,
                         String idempotencyKey) {}

    /** El pedido y si es el reintento de una clave ya usada. */
    public record Resultado(Map<String, Object> pedido, boolean repetido) {}

    // ------------------------------------------------------------------ crear

    /**
     * POST /api/pedidos. Nace ENVIADO; si la política de crédito lo retiene (F5.4), pasa a RETENIDO;
     * si no, y quien lo toma puede confirmar (admin o cajero),
     * se confirma en la misma transacción, salvo que pida {@code confirmar=false} (la
     * televenta que revisa existencias antes). A un vendedor ese campo no le cambia nada. Idempotente por clave: el reintento con los
     * mismos datos devuelve el mismo pedido; con otros, 409.
     */
    @Transactional
    public Resultado crear(Quien quien, PedidoNuevo cuerpo) {
        exigirUsuario(quien);
        if (cuerpo == null) {
            throw new DatoInvalidoException("clienteDocumento", "Falta el pedido.");
        }
        String clave = obligatorio(cuerpo.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        String documento = obligatorio(cuerpo.clienteDocumento(), "clienteDocumento", "Falta el documento del cliente.");
        String origen = obligatorio(cuerpo.origen(), "origen", "Falta el origen del pedido.").toLowerCase(Locale.ROOT);
        if (!ORIGENES.contains(origen)) {
            throw new DatoInvalidoException("origen", "El origen es uno de: vendedor, televenta, mostrador.");
        }
        String modalidad = cuerpo.modalidad() == null || cuerpo.modalidad().isBlank()
                ? "PREVENTA" : cuerpo.modalidad().trim().toUpperCase(Locale.ROOT);
        if (!MODALIDADES.contains(modalidad)) {
            throw new DatoInvalidoException("modalidad", "La modalidad es PREVENTA o AUTOVENTA.");
        }
        if (modalidad.equals("AUTOVENTA")) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.AUTOVENTA_SIN_DESPACHO,
                    "La autoventa confirma, despacha y entrega en la misma captura, y el despacho todavía no crea la venta. "
                            + "Tómalo como PREVENTA.");
        }
        List<LineaNueva> lineas = lineasNuevas(cuerpo.lineas());
        Long vendedor = cuerpo.vendedorId();
        if (quien.esVendedor()) {
            if (vendedor != null && !vendedor.equals(quien.usuarioId())) {
                throw new SoloAdministradorException("tomar un pedido a nombre de otro vendedor");
            }
            vendedor = quien.usuarioId();
        }
        OffsetDateTime ocurrido = cuerpo.ocurridoEn() == null ? OffsetDateTime.now(BOGOTA) : cuerpo.ocurridoEn();
        if (cuerpo.entregaEl() != null && cuerpo.entregaEl().isBefore(ocurrido.atZoneSameInstant(BOGOTA).toLocalDate())) {
            throw new DatoInvalidoException("entregaEl", "La entrega no puede ser antes del día en que se toma el pedido.");
        }

        Map<String, Object> existente = pedidoPorClave(quien.negocio(), clave);
        if (existente != null) {
            exigirMismoPedido(quien.negocio(), existente, documento, lineas);
            return new Resultado(detalleVisible(quien, (UUID) existente.get("id")), true);
        }

        List<Map<String, Object>> cliente = jdbc.queryForList(
                "SELECT activo FROM clientes WHERE tenant_id = ? AND documento = ?", quien.negocio(), documento);
        if (cliente.isEmpty()) {
            throw new DatoInvalidoException("clienteDocumento", "Ese cliente no existe en el negocio.");
        }
        if (!Boolean.TRUE.equals(cliente.get(0).get("activo"))) {
            throw new ClienteInactivoException(documento, "tomarle un pedido");
        }
        List<String> faltan = productosQueNoExisten(quien.negocio(), lineas);
        if (!faltan.isEmpty()) {
            throw new DatoInvalidoException("lineas", "No existen en el negocio: " + String.join(", ", faltan) + ".");
        }
        if (cuerpo.siteId() != null && !existeSede(quien.negocio(), cuerpo.siteId())) {
            throw new DatoInvalidoException("siteId", "Esa sede no es de este negocio.");
        }

        fijarAutor(quien);
        List<Map<String, Object>> paraLaFuncion = new ArrayList<>();
        for (LineaNueva l : lineas) {
            paraLaFuncion.add(Map.of("producto_id", l.productoId(), "cantidad", l.cantidad()));
        }
        final Long vendedorFinal = vendedor;
        UUID id = traducir(() -> jdbc.queryForObject(
                "SELECT pedidos.fn_pedido_crear('ENVIADO', ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)", UUID.class,
                documento, origen, modalidad, vendedorFinal, cuerpo.siteId(),
                cuerpo.entregaEl() == null ? null : java.sql.Date.valueOf(cuerpo.entregaEl()),
                aJson(paraLaFuncion), Timestamp.from(ocurrido.toInstant()), clave));
        exigirPrecios(quien.negocio(), id);
        // F5.4: con la política RETENER_PEDIDO, el pedido de un cliente con una factura vencida hace más de
        // N días nace RETENIDO (FACTURA_VENCIDA) y no se confirma. Se evalúa aquí, al tomarlo; nunca después.
        java.util.Optional<com.suresell.orders.cartera.Cartera.Retencion> retencion =
                cartera.retencionPorPolitica(quien.negocio(), documento);
        if (retencion.isPresent()) {
            transicionar(quien, id, "RETENIDO", "FACTURA_VENCIDA",
                    "Política de crédito: factura vencida hace " + retencion.get().diasVencido() + " días (retiene con más de "
                            + retencion.get().diasMoraParaRetener() + ").",
                    null, ocurrido, clave + ":retenido");
        } else if (CONFIRMAN.contains(quien.rol()) && !Boolean.FALSE.equals(cuerpo.confirmar())) {
            transicionar(quien, id, "CONFIRMADO", null, null, null, ocurrido, clave + ":confirmado");
        }
        return new Resultado(detalleVisible(quien, id), false);
    }

    /**
     * F5.3f: toda línea con cantidad vigente mayor que 0 tiene precio mayor que 0. Una línea en 0 por ajuste
     * (SIN_EXISTENCIA) no cuenta. Se llama después de escribir, dentro de la transacción: si falla, no queda nada.
     * Una bonificación a $0 hecha a propósito no existe todavía; cuando exista tendrá su propio origen de precio.
     */
    private void exigirPrecios(String negocio, UUID pedido) {
        List<Map<String, Object>> sinPrecio = jdbc.queryForList("""
                SELECT v.producto_id, COALESCE(m.name_product, v.producto_id) AS nombre
                  FROM pedidos.v_pedidos_lineas v
                  LEFT JOIN menu_products m ON m.tenant_id = v.tenant_id AND m.id_product = v.producto_id
                 WHERE v.tenant_id = ? AND v.pedido_id = ? AND COALESCE(v.confirmada, v.pedida) > 0
                   AND COALESCE(v.precio_confirmado, v.precio_visto, 0) <= 0
                 ORDER BY v.n""", negocio, pedido);
        if (!sinPrecio.isEmpty()) {
            String nombres = String.join(", ", sinPrecio.stream().map(f -> (String) f.get("nombre")).toList());
            throw new PedidoRechazadoException(HttpStatus.BAD_REQUEST, PedidoRechazadoException.SIN_PRECIO,
                    "Sin precio (queda en $0): " + nombres + ". Pon su precio en el catálogo o en la lista del cliente, o corrígelo con ERROR_DE_PRECIO.",
                    (String) sinPrecio.get(0).get("producto_id"));
        }
    }

    // ------------------------------------------------------------ transiciones

    /**
     * POST /api/pedidos/{id}/confirmar. Si alguna cantidad cambia respecto a la vigente,
     * primero un AJUSTADO con esas cantidades y después el CONFIRMADO, en la misma
     * transacción; así la línea de tiempo dice qué se ajustó. Congela el precio de la captura.
     */
    @Transactional
    public Map<String, Object> confirmar(Quien quien, UUID id, Accion cuerpo) {
        exigirRol(quien, CONFIRMAN, "confirmar un pedido");
        exigirUsuario(quien);
        Accion a = cuerpo == null ? new Accion(null, null, null, null, null) : cuerpo;
        String clave = obligatorio(a.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        Map<String, Object> pedido = cabeceraVisible(quien, id);
        OffsetDateTime ocurrido = a.ocurridoEn() == null ? OffsetDateTime.now(BOGOTA) : a.ocurridoEn();
        List<LineaDeEvento> lineas = lineasDeEvento(a.lineas(), false);
        String motivo = motivo(a.motivo(), MOTIVOS_DE_AJUSTE, false);
        if ("ERROR_DE_PRECIO".equals(motivo)) {
            throw new DatoInvalidoException("motivo", "Un cambio de precio va por «ajustar», no por «confirmar».");
        }
        if (!lineas.isEmpty() && cambiaAlgunaCantidad(quien.negocio(), id, lineas)) {
            transicionar(quien, id, "AJUSTADO", motivo, a.nota(), lineas, ocurrido, clave + ":ajustado");
            transicionar(quien, id, "CONFIRMADO", null, null, null, ocurrido, clave + ":confirmado");
        } else {
            transicionar(quien, id, "CONFIRMADO", null, a.nota(), null, ocurrido, clave + ":confirmado");
        }
        exigirPrecios(quien.negocio(), id);
        return detalleVisible(quien, (UUID) pedido.get("id"));
    }

    /** POST /api/pedidos/{id}/ajustar: cantidades (y, solo un admin, precio con ERROR_DE_PRECIO) sin confirmar. */
    @Transactional
    public Map<String, Object> ajustar(Quien quien, UUID id, Accion cuerpo) {
        exigirRol(quien, CONFIRMAN, "ajustar un pedido");
        exigirUsuario(quien);
        Accion a = requerida(cuerpo);
        String clave = obligatorio(a.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        cabeceraVisible(quien, id);
        List<LineaDeEvento> lineas = lineasDeEvento(a.lineas(), true);
        if (lineas.isEmpty()) {
            throw new DatoInvalidoException("lineas", "Un ajuste dice qué líneas cambian.");
        }
        boolean hayPrecio = lineas.stream().anyMatch(l -> l.precio() != null);
        String motivo = motivo(a.motivo(), MOTIVOS_DE_AJUSTE, hayPrecio);
        if (hayPrecio && !"ERROR_DE_PRECIO".equals(motivo)) {
            throw new DatoInvalidoException("motivo", "Un precio nuevo solo va con el motivo ERROR_DE_PRECIO.");
        }
        if ("ERROR_DE_PRECIO".equals(motivo)) {
            if (!quien.esAdmin()) {
                throw new SoloAdministradorException("corregir el precio de un pedido");
            }
            if (!hayPrecio) {
                throw new DatoInvalidoException("lineas", "Un ajuste por ERROR_DE_PRECIO dice el precio nuevo de al menos una línea.");
            }
        }
        OffsetDateTime ocurrido = a.ocurridoEn() == null ? OffsetDateTime.now(BOGOTA) : a.ocurridoEn();
        transicionar(quien, id, "AJUSTADO", motivo, a.nota(), lineas, ocurrido, clave);
        exigirPrecios(quien.negocio(), id);
        return detalleVisible(quien, id);
    }

    /**
     * POST /api/pedidos/{id}/despachar (F5.5, §D6): el evento DESPACHADO y la venta, en UNA
     * transacción. La venta lleva la cantidad DESPACHADA de cada línea al precio congelado,
     * medio CREDITO (entra a cartera) con el plazo pactado en el pedido, sale de la sede de
     * despacho, no entra a cocina y no se encadena. Si la venta no entra (insolvencia, una
     * regla de la base), el despacho tampoco.
     *
     * <ul>
     *   <li>Idempotente por clave: el reintento devuelve el mismo pedido con la misma venta.</li>
     *   <li>Un segundo despacho (tras ENTREGA_FALLIDA) crearía otra venta del mismo pedido:
     *       409 REVERSA_PENDIENTE hasta que exista la reversa (D7). V70 es el suelo.</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> despachar(Quien quien, UUID id, Despacho cuerpo) {
        exigirRol(quien, CONFIRMAN, "despachar un pedido");
        exigirUsuario(quien);
        if (cuerpo == null) {
            throw new DatoInvalidoException("idempotencyKey", "Falta el cuerpo del despacho.");
        }
        String clave = obligatorio(cuerpo.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        Map<String, Object> pedido = cabeceraVisible(quien, id);

        // El reintento: la misma clave ya despachó ESTE pedido → lo mismo que la primera vez.
        List<Map<String, Object>> previo = jdbc.queryForList(
                "SELECT pedido_id, tipo FROM pedidos.pedidos_eventos WHERE tenant_id = ? AND idempotency_key = ?", quien.negocio(), clave);
        if (!previo.isEmpty()) {
            if (!id.equals(previo.get(0).get("pedido_id")) || !"DESPACHADO".equals(previo.get(0).get("tipo"))) {
                throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.IDEMPOTENCIA_REUTILIZADA,
                        "Esa clave ya se usó con otra acción. No se escribió nada.");
            }
            return detalleVisible(quien, id);
        }
        if (YA_DESPACHADO.contains((String) pedido.get("estado"))) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.REVERSA_PENDIENTE,
                    "El pedido ya se despachó y tiene su venta: despacharlo otra vez exige reversar la primera, y la reversa todavía no existe.");
        }
        List<LineaDeEvento> lineas = lineasDeEvento(cuerpo.lineas(), false);
        Long sede = cuerpo.siteId() != null ? cuerpo.siteId() : toLong(pedido.get("site_id"));
        if (sede != null && !existeSede(quien.negocio(), sede)) {
            throw new DatoInvalidoException("siteId", "Esa sede no es de este negocio.");
        }
        // Un solo reloj: el del evento, nunca en el futuro para la base (ck_int_reloj, ck_orders_reloj).
        OffsetDateTime ahora = OffsetDateTime.now(BOGOTA);
        OffsetDateTime ocurrido = cuerpo.ocurridoEn() == null || cuerpo.ocurridoEn().isAfter(ahora) ? ahora : cuerpo.ocurridoEn();

        UUID evento = transicionar(quien, id, "DESPACHADO", null, cuerpo.nota(), lineas, ocurrido, clave);

        List<com.suresell.orders.application.dto.OrderItemRequestRecord> items = new ArrayList<>();
        Map<String, com.suresell.orders.mayorista.ResolucionDePrecios.Precio> precios = new LinkedHashMap<>();
        UUID lista = (UUID) pedido.get("lista_precio_id");
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT producto_id, despachada, COALESCE(precio_confirmado, precio_visto) AS precio, precio_origen, lista_precio_item_id
                  FROM pedidos.v_pedidos_lineas WHERE tenant_id = ? AND pedido_id = ? ORDER BY n""", quien.negocio(), id)) {
            int despachada = f.get("despachada") == null ? 0 : ((Number) f.get("despachada")).intValue();
            if (despachada <= 0) {
                continue;
            }
            BigDecimal precio = (BigDecimal) f.get("precio");
            String producto = (String) f.get("producto_id");
            items.add(new com.suresell.orders.application.dto.OrderItemRequestRecord(producto, despachada, precio, null, null));
            precios.putIfAbsent(producto, new com.suresell.orders.mayorista.ResolucionDePrecios.Precio(precio,
                    f.get("precio_origen") == null ? "PEDIDO" : (String) f.get("precio_origen"), (UUID) f.get("lista_precio_item_id"), lista));
        }
        if (items.isEmpty()) {
            throw new DatoInvalidoException("lineas", "Un despacho lleva al menos una línea con cantidad mayor que 0.");
        }
        // F5.3f: una factura de $0 en cartera no se crea nunca (la venta 26 de staging). La transacción revierte el DESPACHADO.
        BigDecimal totalDeLaVenta = items.stream()
                .map(i -> (i.unitPrice() == null ? BigDecimal.ZERO : i.unitPrice()).multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDeLaVenta.signum() <= 0) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.VENTA_EN_CERO,
                    "Lo despachado suma $0: no se crea una venta ni una deuda de $0. Corrige el precio (ajustar, ERROR_DE_PRECIO) o no despaches esas líneas.");
        }
        items.stream().filter(i -> i.unitPrice() == null || i.unitPrice().signum() <= 0).findFirst().ifPresent(i -> {
            throw new PedidoRechazadoException(HttpStatus.BAD_REQUEST, PedidoRechazadoException.SIN_PRECIO,
                    "La línea de " + i.productId() + " se despacharía a $0: corrige su precio antes de despachar.", i.productId());
        });
        Short plazo = pedido.get("plazo_dias") == null ? null : ((Number) pedido.get("plazo_dias")).shortValue();
        String condicion = plazo != null && plazo == 0 ? "CONTADO" : "CREDITO";
        var dto = new com.suresell.orders.application.dto.OrderRequestRecord(
                null, null, items, null, "CREDITO", null, "pedido-" + id + "-despacho-" + evento, true, null, true,
                ocurrido, null, null, null, null, null, (String) pedido.get("cliente_documento"), null, null, null,
                toLong(pedido.get("vendedor_id")), condicion);
        ventas.crearVentaDePedido(dto, new com.suresell.orders.application.dto.VentaDelServidor(id, sede, plazo, condicion, precios));
        return detalleVisible(quien, id);
    }

    /**
     * POST /api/pedidos/{id}/entregar (F5.7): la entrega con su prueba por {@code fn_pedido_entregar}
     * y, en contraentrega, el recibo de caja aplicado a la venta del pedido, en UNA transacción.
     *
     * <ul>
     *   <li>Vendedor (solo sus pedidos), admin y cajero.</li>
     *   <li>Hecha: quién recibe (nombre y documento). Con novedad: líneas y motivo. Fallida: motivo,
     *       sin quién recibe y sin recibo.</li>
     *   <li>Lo que no llega no toca venta, cartera ni inventario: queda en
     *       {@code v_pedidos_pendiente_de_reversa} hasta que exista la reversa (D7).</li>
     *   <li>El recibo es el de F4.4 (número, CREDIT, aplicación), con sus validaciones; si no entra,
     *       la entrega tampoco.</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> entregar(Quien quien, UUID id, Entrega cuerpo) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "entregar un pedido");
        exigirUsuario(quien);
        if (cuerpo == null) {
            throw new DatoInvalidoException("idempotencyKey", "Falta el cuerpo de la entrega.");
        }
        String clave = obligatorio(cuerpo.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        String resultado = obligatorio(cuerpo.resultado(), "resultado", "Falta el resultado de la entrega.").toUpperCase(Locale.ROOT);
        if (!RESULTADOS_DE_ENTREGA.contains(resultado)) {
            throw new DatoInvalidoException("resultado", "El resultado es ENTREGADO, ENTREGADO_CON_NOVEDAD o ENTREGA_FALLIDA.");
        }
        boolean fallida = resultado.equals("ENTREGA_FALLIDA");
        String motivo = switch (resultado) {
            case "ENTREGADO_CON_NOVEDAD" -> motivo(cuerpo.motivo(), MOTIVOS_DE_NOVEDAD, true);
            case "ENTREGA_FALLIDA" -> motivo(cuerpo.motivo(), MOTIVOS_DE_ENTREGA_FALLIDA, true);
            default -> motivo(cuerpo.motivo(), Set.of(), false);
        };
        List<LineaDeEvento> lineas = lineasDeEvento(cuerpo.lineas(), false);
        if (fallida && !lineas.isEmpty()) {
            throw new DatoInvalidoException("lineas", "Una entrega fallida no lleva cantidades: no se entregó nada.");
        }
        if (resultado.equals("ENTREGADO_CON_NOVEDAD") && lineas.isEmpty()) {
            throw new DatoInvalidoException("lineas", "Una entrega con novedad dice qué se entregó de cada línea.");
        }
        String nombre = null;
        String documento = null;
        if (fallida) {
            if (cuerpo.recibe() != null) {
                throw new DatoInvalidoException("recibe", "En una entrega fallida nadie recibe.");
            }
            if (cuerpo.recibo() != null) {
                throw new DatoInvalidoException("recibo", "En una entrega fallida no se cobra: no se entregó nada.");
            }
        } else {
            nombre = obligatorio(cuerpo.recibe() == null ? null : cuerpo.recibe().nombre(), "recibe", "Di quién recibe: nombre.");
            documento = obligatorio(cuerpo.recibe().documento(), "recibe", "Di quién recibe: documento.");
        }
        if ((cuerpo.latitud() == null) != (cuerpo.longitud() == null)) {
            throw new DatoInvalidoException("latitud", "El lugar va con latitud y longitud, o sin ninguna.");
        }
        cabeceraVisible(quien, id);

        // El reintento: la misma clave ya entregó ESTE pedido → lo mismo que la primera vez (el recibo, por su clave).
        List<Map<String, Object>> previo = jdbc.queryForList(
                "SELECT pedido_id, tipo FROM pedidos.pedidos_eventos WHERE tenant_id = ? AND idempotency_key = ?", quien.negocio(), clave);
        if (!previo.isEmpty() && (!id.equals(previo.get(0).get("pedido_id")) || !resultado.equals(previo.get(0).get("tipo")))) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.IDEMPOTENCIA_REUTILIZADA,
                    "Esa clave ya se usó con otra acción. No se escribió nada.");
        }

        OffsetDateTime ahora = OffsetDateTime.now(BOGOTA);
        OffsetDateTime ocurrido = cuerpo.ocurridoEn() == null || cuerpo.ocurridoEn().isAfter(ahora) ? ahora : cuerpo.ocurridoEn();
        fijarAutor(quien);
        String lineasJson = null;
        if (!lineas.isEmpty()) {
            List<Map<String, Object>> l = new ArrayList<>();
            for (LineaDeEvento e : lineas) {
                l.add(Map.of("linea_id", e.lineaId().toString(), "cantidad", e.cantidad()));
            }
            lineasJson = aJson(l);
        }
        final String lj = lineasJson;
        final String nom = nombre;
        final String doc = documento;
        traducir(() -> jdbc.queryForObject("""
                SELECT pedidos.fn_pedido_entregar(?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""", UUID.class,
                id, resultado, motivo, cuerpo.nota(), lj, nom, doc, cuerpo.latitud(), cuerpo.longitud(),
                Timestamp.from(ocurrido.toInstant()), clave));

        if (cuerpo.recibo() != null) {
            List<Map<String, Object>> venta = jdbc.queryForList("""
                    SELECT o.uuid_id, o.site_id, o.cliente_documento FROM orders o
                     WHERE o.tenant_id = ? AND o.pedido_id = ? AND o.deleted_at IS NULL""", quien.negocio(), id);
            if (venta.isEmpty()) {
                throw new DatoInvalidoException("recibo", "Este pedido no tiene venta a la que aplicar el cobro.");
            }
            ReciboEnLaEntrega r = cuerpo.recibo();
            cartera.registrarRecibo(new com.suresell.orders.cartera.Cartera.Quien(quien.negocio(), quien.rol(), quien.usuarioId()),
                    new com.suresell.orders.cartera.Cartera.NuevoRecibo((String) venta.get(0).get("cliente_documento"), r.monto(), r.medio(),
                            r.referenciaMedio(),
                            List.of(new com.suresell.orders.cartera.Cartera.Aplicacion((UUID) venta.get(0).get("uuid_id"), r.monto())),
                            ocurrido, clave + ":recibo", null, toLong(venta.get(0).get("site_id"))));
        }
        return detalleVisible(quien, id);
    }

    @Transactional
    public Map<String, Object> rechazar(Quien quien, UUID id, Accion cuerpo) {
        return conMotivo(quien, id, cuerpo, "RECHAZADO", MOTIVOS_DE_RECHAZO, "rechazar un pedido");
    }

    @Transactional
    public Map<String, Object> cancelar(Quien quien, UUID id, Accion cuerpo) {
        exigirRol(quien, Set.of("admin"), "cancelar un pedido");
        Map<String, Object> pedido = cabeceraVisible(quien, id);
        if (YA_DESPACHADO.contains((String) pedido.get("estado"))) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.REVERSA_PENDIENTE,
                    "El pedido ya se despachó: cancelarlo exige reversar la venta, y la reversa todavía no existe.");
        }
        return conMotivo(quien, id, cuerpo, "CANCELADO", MOTIVOS_DE_CANCELACION, "cancelar un pedido");
    }

    @Transactional
    public Map<String, Object> retener(Quien quien, UUID id, Accion cuerpo) {
        return conMotivo(quien, id, cuerpo, "RETENIDO", MOTIVOS_DE_RETENCION, "retener un pedido");
    }

    /** POST /api/pedidos/{id}/liberar: con motivo de liberación (D9); la nota es opcional. */
    @Transactional
    public Map<String, Object> liberar(Quien quien, UUID id, Accion cuerpo) {
        return conMotivo(quien, id, cuerpo, "LIBERADO", MOTIVOS_DE_LIBERACION, "liberar un pedido");
    }

    private Map<String, Object> conMotivo(Quien quien, UUID id, Accion cuerpo, String tipo, Set<String> motivos, String queCosa) {
        exigirRol(quien, Set.of("admin"), queCosa);
        exigirUsuario(quien);
        Accion a = requerida(cuerpo);
        String clave = obligatorio(a.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        String motivo = motivo(a.motivo(), motivos, true);
        cabeceraVisible(quien, id);
        OffsetDateTime ocurrido = a.ocurridoEn() == null ? OffsetDateTime.now(BOGOTA) : a.ocurridoEn();
        transicionar(quien, id, tipo, motivo, a.nota(), null, ocurrido, clave);
        return detalleVisible(quien, id);
    }

    private UUID transicionar(Quien quien, UUID id, String tipo, String motivo, String nota, List<LineaDeEvento> lineas,
                              OffsetDateTime ocurrido, String clave) {
        fijarAutor(quien);
        String paraLaFuncion = null;
        if (lineas != null && !lineas.isEmpty()) {
            List<Map<String, Object>> l = new ArrayList<>();
            for (LineaDeEvento e : lineas) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("linea_id", e.lineaId().toString());
                m.put("cantidad", e.cantidad());
                if (e.precio() != null) {
                    m.put("precio", e.precio());
                }
                l.add(m);
            }
            paraLaFuncion = aJson(l);
        }
        final String lineasJson = paraLaFuncion;
        return traducir(() -> jdbc.queryForObject("SELECT pedidos.fn_pedido_transicionar(?, ?, ?, ?, ?::jsonb, ?, ?)", UUID.class,
                id, tipo, motivo, nota, lineasJson, Timestamp.from(ocurrido.toInstant()), clave));
    }

    // ------------------------------------------------------------------ lectura

    /**
     * GET /api/pedidos: la bandeja «Pedidos recibidos», de todos los orígenes, en el orden
     * de quien prepara el despacho: entrega prometida más cercana primero (sin fecha, al
     * final) y, a igual día, por número. {@code fecha} es el día (Bogotá) de la captura y
     * {@code entregaEl} el de la entrega prometida. Paginada por cursor: {@code despuesDe}
     * = el {@code siguiente} de la página anterior.
     */
    public Map<String, Object> bandeja(Quien quien, String estado, String origen, Long vendedorId, LocalDate fecha,
                                       LocalDate entregaEl, String clienteDocumento, Integer limite, String despuesDe) {
        return bandeja(quien, estado, origen, vendedorId, fecha, entregaEl, clienteDocumento, limite, despuesDe, null);
    }

    public Map<String, Object> bandeja(Quien quien, String estado, String origen, Long vendedorId, LocalDate fecha,
                                       LocalDate entregaEl, String clienteDocumento, Integer limite, String despuesDe,
                                       Boolean pendienteDeReversa) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver los pedidos");
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.numero, p.estado, p.origen, p.modalidad, p.cliente_documento, c.nombre AS cliente,
                       p.vendedor_id, u.nombre AS vendedor, p.fecha_entrega_prometida, p.ocurrido_en, p.plazo_dias,
                       p.condicion_pago, t.lineas, t.total, COALESCE(p.fecha_entrega_prometida, 'infinity'::date)::text AS entrega_orden,
                       pr.valor AS valor_pendiente, ret.motivo AS motivo_de_retencion, ret.nota AS nota_de_retencion
                  FROM pedidos.pedidos p
                  LEFT JOIN clientes c ON c.tenant_id = p.tenant_id AND c.documento = p.cliente_documento
                  LEFT JOIN users u ON u.tenant_id = p.tenant_id AND u.id = p.vendedor_id
                  LEFT JOIN LATERAL (SELECT count(*) AS lineas,
                                            sum(COALESCE(v.confirmada, v.pedida) * COALESCE(v.precio_confirmado, v.precio_visto)) AS total
                                       FROM pedidos.v_pedidos_lineas v
                                      WHERE v.tenant_id = p.tenant_id AND v.pedido_id = p.id) t ON true
                  LEFT JOIN pedidos.v_pedidos_pendiente_de_reversa pr ON pr.tenant_id = p.tenant_id AND pr.pedido_id = p.id
                  -- F5.4: por qué está retenido (el último RETENIDO), solo si lo está; la nota de la política dice los días.
                  LEFT JOIN LATERAL (SELECT e.motivo, e.nota FROM pedidos.pedidos_eventos e
                                      WHERE e.tenant_id = p.tenant_id AND e.pedido_id = p.id AND e.tipo = 'RETENIDO'
                                      ORDER BY e.secuencia DESC LIMIT 1) ret ON p.estado = 'RETENIDO'
                 WHERE p.tenant_id = ?""");
        List<Object> args = new ArrayList<>(List.of(quien.negocio()));
        filtrosComunes(quien, origen, vendedorId, fecha, entregaEl, clienteDocumento, sql, args);
        if (estado != null && !estado.isBlank()) {
            List<String> estados = new ArrayList<>();
            for (String e : estado.split(",")) {
                String x = e.trim().toUpperCase(Locale.ROOT);
                if (!ESTADOS.contains(x)) {
                    throw new DatoInvalidoException("estado", "El estado es uno o varios (separados por coma) de: " + String.join(", ", ESTADOS) + ".");
                }
                estados.add(x);
            }
            sql.append(" AND p.estado = ANY (?)");
            args.add(estados.toArray(new String[0]));
        }
        if (pendienteDeReversa != null) {
            sql.append(pendienteDeReversa ? " AND pr.pedido_id IS NOT NULL" : " AND pr.pedido_id IS NULL");
        }
        if (despuesDe != null && !despuesDe.isBlank()) {
            String[] partes = despuesDe.trim().split("_", 2);
            long numero;
            try {
                if (partes.length != 2 || !(partes[0].equals("infinity") || partes[0].matches("\\d{4}-\\d{2}-\\d{2}"))) {
                    throw new NumberFormatException();
                }
                numero = Long.parseLong(partes[1]);
            } catch (NumberFormatException e) {
                throw new DatoInvalidoException("despuesDe", "El cursor no es válido: usa el «siguiente» de la página anterior.");
            }
            sql.append(" AND (COALESCE(p.fecha_entrega_prometida, 'infinity'::date), p.numero) > (?::date, ?)");
            args.add(partes[0]);
            args.add(numero);
        }
        int n = limite == null ? LIMITE_DE_BANDEJA : limite;
        if (n < 1 || n > LIMITE_MAXIMO_DE_BANDEJA) {
            throw new DatoInvalidoException("limite", "El límite va de 1 a " + LIMITE_MAXIMO_DE_BANDEJA + ".");
        }
        sql.append(" ORDER BY COALESCE(p.fecha_entrega_prometida, 'infinity'::date), p.numero LIMIT ?");
        args.add(n + 1);
        List<Map<String, Object>> filas = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hayMas = filas.size() > n;
        List<Map<String, Object>> pedidos = new ArrayList<>();
        for (Map<String, Object> f : filas.subList(0, Math.min(n, filas.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", f.get("id"));
            r.put("numero", f.get("numero"));
            r.put("estado", f.get("estado"));
            r.put("origen", f.get("origen"));
            r.put("modalidad", f.get("modalidad"));
            r.put("clienteDocumento", f.get("cliente_documento"));
            r.put("cliente", f.get("cliente"));
            r.put("vendedorId", f.get("vendedor_id"));
            r.put("vendedor", f.get("vendedor"));
            r.put("entregaEl", fecha(f.get("fecha_entrega_prometida")));
            r.put("ocurridoEn", momento(f.get("ocurrido_en")));
            r.put("plazoDias", f.get("plazo_dias"));
            r.put("condicionPago", f.get("condicion_pago"));
            r.put("lineas", f.get("lineas"));
            r.put("total", f.get("total"));
            r.put("pendienteDeReversa", f.get("valor_pendiente") != null);
            r.put("valorPendienteDeReversa", f.get("valor_pendiente") == null ? BigDecimal.ZERO : f.get("valor_pendiente"));
            r.put("motivoDeRetencion", f.get("motivo_de_retencion"));
            r.put("notaDeRetencion", f.get("nota_de_retencion"));
            r.put("cursor", f.get("entrega_orden") + "_" + f.get("numero"));
            pedidos.add(r);
        }
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("pedidos", pedidos);
        salida.put("siguiente", hayMas ? pedidos.get(pedidos.size() - 1).get("cursor") : null);
        pedidos.forEach(x -> x.remove("cursor"));
        return salida;
    }

    /**
     * GET /api/pedidos/conteos: cuántos pedidos hay en cada estado (sin CREADO_BORRADOR),
     * con los mismos filtros y la misma visibilidad que la bandeja, en UNA consulta
     * agrupada. Vienen todos los estados, también los que están en 0.
     */
    public Map<String, Object> conteos(Quien quien, String origen, Long vendedorId, LocalDate fecha, LocalDate entregaEl,
                                       String clienteDocumento) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver los pedidos");
        StringBuilder sql = new StringBuilder("SELECT p.estado, count(*) AS n FROM pedidos.pedidos p WHERE p.tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(quien.negocio()));
        filtrosComunes(quien, origen, vendedorId, fecha, entregaEl, clienteDocumento, sql, args);
        sql.append(" AND p.estado <> 'CREADO_BORRADOR' GROUP BY p.estado");
        Map<String, Object> porEstado = new LinkedHashMap<>();
        for (String e : ESTADOS) {
            if (!e.equals("CREADO_BORRADOR")) {
                porEstado.put(e, 0L);
            }
        }
        for (Map<String, Object> f : jdbc.queryForList(sql.toString(), args.toArray())) {
            porEstado.put((String) f.get("estado"), ((Number) f.get("n")).longValue());
        }
        StringBuilder pend = new StringBuilder("""
                SELECT count(*) FROM pedidos.pedidos p
                  JOIN pedidos.v_pedidos_pendiente_de_reversa pr ON pr.tenant_id = p.tenant_id AND pr.pedido_id = p.id
                 WHERE p.tenant_id = ?""");
        List<Object> pargs = new ArrayList<>(List.of(quien.negocio()));
        filtrosComunes(quien, origen, vendedorId, fecha, entregaEl, clienteDocumento, pend, pargs);
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("conteos", porEstado);
        salida.put("pendientesDeReversa", jdbc.queryForObject(pend.toString(), Long.class, pargs.toArray()));
        return salida;
    }

    /** Visibilidad por rol y los filtros que comparten la bandeja y los conteos. */
    private static void filtrosComunes(Quien quien, String origen, Long vendedorId, LocalDate fecha, LocalDate entregaEl,
                                       String clienteDocumento, StringBuilder sql, List<Object> args) {
        if (quien.esVendedor()) {
            sql.append(" AND (p.vendedor_id = ? OR p.capturado_por = ?)");
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
        } else if (vendedorId != null) {
            sql.append(" AND p.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (origen != null && !origen.isBlank()) {
            List<String> origenes = new ArrayList<>();
            for (String o : origen.split(",")) {
                String x = o.trim().toLowerCase(Locale.ROOT);
                if (!ORIGENES_DEL_ENUM.contains(x)) {
                    throw new DatoInvalidoException("origen", "El origen es uno o varios (separados por coma) de: " + String.join(", ", ORIGENES_DEL_ENUM) + ".");
                }
                origenes.add(x);
            }
            sql.append(" AND p.origen = ANY (?)");
            args.add(origenes.toArray(new String[0]));
        }
        if (fecha != null) {
            sql.append(" AND p.ocurrido_en >= ? AND p.ocurrido_en < ?");
            args.add(Timestamp.from(fecha.atStartOfDay(BOGOTA).toInstant()));
            args.add(Timestamp.from(fecha.plusDays(1).atStartOfDay(BOGOTA).toInstant()));
        }
        if (entregaEl != null) {
            sql.append(" AND p.fecha_entrega_prometida = ?");
            args.add(java.sql.Date.valueOf(entregaEl));
        }
        if (clienteDocumento != null && !clienteDocumento.isBlank()) {
            sql.append(" AND p.cliente_documento = ?");
            args.add(clienteDocumento.trim());
        }
    }

    /**
     * Las cantidades vigentes de cada línea de los pedidos de {@code ped} en UNA pasada sobre sus
     * eventos (F5.11): la última confirmada, la último despachada y la última entregada DESPUÉS del
     * último despacho, igual que {@code v_pedidos_lineas} pero sin cuatro búsquedas por línea.
     * Medido con 50.000 líneas: 202 ms frente a 1,05 s. {@code EstadoGuardadoEsDerivadoTest} compara
     * las dos formas línea por línea sobre sus 1.000 recorridos. Espera un CTE {@code ped(id)} y el
     * negocio como primer parámetro.
     */
    static final String ULTIMAS_CANTIDADES = """
            ult AS (
                SELECT el.linea_id,
                       (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo IN ('CONFIRMADO', 'AJUSTADO')))[1] AS confirmada,
                       (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo = 'DESPACHADO'))[1] AS despachada,
                       CASE WHEN max(e.secuencia) FILTER (WHERE e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD'))
                                 > max(e.secuencia) FILTER (WHERE e.tipo = 'DESPACHADO')
                            THEN (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD')))[1]
                       END AS entregada
                  FROM ped
                  JOIN pedidos.pedidos_eventos e ON e.tenant_id = ? AND e.pedido_id = ped.id
                  JOIN pedidos.pedidos_eventos_lineas el ON el.tenant_id = e.tenant_id AND el.pedido_id = e.pedido_id AND el.evento_id = e.id
                 GROUP BY el.linea_id
            )""";

    public static final Set<String> AGRUPACIONES = Set.of("cliente", "vendedor", "producto");
    static final Set<String> CANCELADOS_POR_EL_CLIENTE = Set.of("CLIENTE_DESISTIO", "DUPLICADO");
    static final int VENTANA_MAXIMA_DE_CUMPLIMIENTO = 92;

    /**
     * GET /api/pedidos/cumplimiento (F5.11): pedidas, confirmadas, despachadas y entregadas por cliente,
     * vendedor o producto, en los pedidos CAPTURADOS en la ventana (días de Bogotá, hasta 92).
     *
     * <ul>
     *   <li>Cuentan todos los pedidos salvo el borrador y los cancelados por el cliente
     *       (CLIENTE_DESISTIO, DUPLICADO): esos no son incumplimiento del proveedor (ECM). Un rechazado o
     *       un cancelado por existencias o ventana sí cuenta, con 0 entregado.</li>
     *   <li>Cumplimiento = entregado / pedido, en unidades y en valor al precio congelado; sin pedido, null.</li>
     *   <li>Un vendedor ve solo sus pedidos.</li>
     * </ul>
     */
    public Map<String, Object> cumplimiento(Quien quien, LocalDate desde, LocalDate hasta, String agrupar) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver el cumplimiento");
        LocalDate hoy = LocalDate.now(BOGOTA);
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? fin.withDayOfMonth(1) : desde;
        if (inicio.isAfter(fin)) {
            throw new DatoInvalidoException("desde", "«desde» no puede ser posterior a «hasta».");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(inicio, fin) + 1 > VENTANA_MAXIMA_DE_CUMPLIMIENTO) {
            throw new DatoInvalidoException("desde", "La ventana es de " + VENTANA_MAXIMA_DE_CUMPLIMIENTO + " días como máximo.");
        }
        String grupo = agrupar == null || agrupar.isBlank() ? "cliente" : agrupar.trim().toLowerCase(Locale.ROOT);
        if (!AGRUPACIONES.contains(grupo)) {
            throw new DatoInvalidoException("agrupar", "Se agrupa por cliente, vendedor o producto.");
        }
        String clave = switch (grupo) {
            case "vendedor" -> "lin.vendedor_id::text";
            case "producto" -> "lin.producto_id";
            default -> "lin.cliente_documento";
        };
        String nombre = switch (grupo) {
            case "vendedor" -> "(SELECT u.nombre FROM users u WHERE u.tenant_id = ? AND u.id::text = r.clave)";
            case "producto" -> "(SELECT m.name_product FROM menu_products m WHERE m.tenant_id = ? AND m.id_product = r.clave)";
            default -> "(SELECT c.nombre FROM clientes c WHERE c.tenant_id = ? AND c.documento = r.clave)";
        };
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("WITH ped AS (SELECT p.id, p.cliente_documento, p.vendedor_id, p.estado, "
                + "(SELECT e.motivo FROM pedidos.pedidos_eventos e WHERE e.tenant_id = p.tenant_id AND e.pedido_id = p.id "
                + "ORDER BY e.secuencia DESC LIMIT 1) AS motivo_final FROM pedidos.pedidos p "
                + "WHERE p.tenant_id = ? AND p.estado <> 'CREADO_BORRADOR' AND p.ocurrido_en >= ? AND p.ocurrido_en < ?");
        args.add(quien.negocio());
        args.add(Timestamp.from(inicio.atStartOfDay(BOGOTA).toInstant()));
        args.add(Timestamp.from(fin.plusDays(1).atStartOfDay(BOGOTA).toInstant()));
        if (quien.esVendedor()) {
            sql.append(" AND (p.vendedor_id = ? OR p.capturado_por = ?)");
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
        }
        sql.append("), ").append(ULTIMAS_CANTIDADES);
        args.add(quien.negocio());
        sql.append("""
                , lin AS (
                    SELECT ped.id, ped.cliente_documento, ped.vendedor_id, ped.estado, l.producto_id, l.cantidad_pedida AS pedida,
                           u.confirmada, u.despachada, u.entregada, COALESCE(l.precio_confirmado, l.precio_visto) AS precio,
                           NOT (ped.estado = 'CANCELADO' AND ped.motivo_final IN ('CLIENTE_DESISTIO', 'DUPLICADO')) AS cuenta,
                           ped.motivo_final
                      FROM ped
                      JOIN pedidos.pedidos_lineas l ON l.tenant_id = ? AND l.pedido_id = ped.id
                      LEFT JOIN ult u ON u.linea_id = l.id
                ), r AS (
                    SELECT %s AS clave,
                           count(DISTINCT lin.id) FILTER (WHERE lin.cuenta) AS pedidos,
                           COALESCE(sum(lin.pedida) FILTER (WHERE lin.cuenta), 0) AS pedidas,
                           COALESCE(sum(lin.confirmada) FILTER (WHERE lin.cuenta), 0) AS confirmadas,
                           COALESCE(sum(lin.despachada) FILTER (WHERE lin.cuenta), 0) AS despachadas,
                           COALESCE(sum(lin.entregada) FILTER (WHERE lin.cuenta), 0) AS entregadas,
                           COALESCE(sum(lin.pedida * lin.precio) FILTER (WHERE lin.cuenta), 0) AS valor_pedido,
                           COALESCE(sum(COALESCE(lin.entregada, 0) * lin.precio) FILTER (WHERE lin.cuenta), 0) AS valor_entregado,
                           count(DISTINCT lin.id) FILTER (WHERE lin.estado = 'RECHAZADO') AS rechazados,
                           count(DISTINCT lin.id) FILTER (WHERE lin.estado = 'CANCELADO' AND lin.cuenta) AS cancelados_por_el_negocio,
                           count(DISTINCT lin.id) FILTER (WHERE NOT lin.cuenta) AS cancelados_por_el_cliente
                      FROM lin GROUP BY 1
                )
                SELECT r.*, %s AS nombre FROM r ORDER BY r.valor_pedido DESC, r.clave""".formatted(clave, nombre));
        args.add(quien.negocio());
        args.add(quien.negocio());
        List<Map<String, Object>> filas = new ArrayList<>();
        Map<String, BigDecimal> totales = new LinkedHashMap<>();
        for (String k : List.of("pedidas", "confirmadas", "despachadas", "entregadas", "valor_pedido", "valor_entregado")) {
            totales.put(k, BigDecimal.ZERO);
        }
        for (Map<String, Object> f : jdbc.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("clave", f.get("clave"));
            r.put("nombre", f.get("nombre"));
            r.put("pedidos", f.get("pedidos"));
            r.put("pedidas", f.get("pedidas"));
            r.put("confirmadas", f.get("confirmadas"));
            r.put("despachadas", f.get("despachadas"));
            r.put("entregadas", f.get("entregadas"));
            r.put("valorPedido", f.get("valor_pedido"));
            r.put("valorEntregado", f.get("valor_entregado"));
            r.put("cumplimientoUnidades", razon(f.get("entregadas"), f.get("pedidas")));
            r.put("cumplimientoValor", razon(f.get("valor_entregado"), f.get("valor_pedido")));
            r.put("rechazados", f.get("rechazados"));
            r.put("canceladosPorElNegocio", f.get("cancelados_por_el_negocio"));
            r.put("canceladosPorElCliente", f.get("cancelados_por_el_cliente"));
            for (String k : totales.keySet()) {
                totales.merge(k, new BigDecimal(f.get(k).toString()), BigDecimal::add);
            }
            filas.add(r);
        }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("pedidas", totales.get("pedidas"));
        total.put("confirmadas", totales.get("confirmadas"));
        total.put("despachadas", totales.get("despachadas"));
        total.put("entregadas", totales.get("entregadas"));
        total.put("valorPedido", totales.get("valor_pedido"));
        total.put("valorEntregado", totales.get("valor_entregado"));
        total.put("cumplimientoUnidades", razon(totales.get("entregadas"), totales.get("pedidas")));
        total.put("cumplimientoValor", razon(totales.get("valor_entregado"), totales.get("valor_pedido")));
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("desde", inicio.toString());
        salida.put("hasta", fin.toString());
        salida.put("agrupar", grupo);
        salida.put("filas", filas);
        salida.put("total", total);
        return salida;
    }

    /** Parte / todo con 4 decimales; sin todo, null (sin dato, nunca 0). */
    private static BigDecimal razon(Object parte, Object todo) {
        BigDecimal t = new BigDecimal(todo.toString());
        if (t.signum() == 0) {
            return null;
        }
        return new BigDecimal(parte.toString()).divide(t, 4, java.math.RoundingMode.HALF_UP);
    }

    /** GET /api/pedidos/{id}: cabecera, líneas con sus cantidades por etapa y la línea de tiempo. */
    public Map<String, Object> detalle(Quien quien, UUID id) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver los pedidos");
        return detalleVisible(quien, id);
    }

    private Map<String, Object> detalleVisible(Quien quien, UUID id) {
        Map<String, Object> p = cabeceraVisible(quien, id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", p.get("id"));
        r.put("numero", p.get("numero"));
        r.put("estado", p.get("estado"));
        r.put("origen", p.get("origen"));
        r.put("modalidad", p.get("modalidad"));
        r.put("clienteDocumento", p.get("cliente_documento"));
        r.put("cliente", p.get("cliente"));
        r.put("vendedorId", p.get("vendedor_id"));
        r.put("vendedor", p.get("vendedor"));
        r.put("capturadoPor", p.get("capturado_por"));
        r.put("siteId", p.get("site_id"));
        r.put("plazoDias", p.get("plazo_dias"));
        r.put("condicionPago", p.get("condicion_pago"));
        r.put("entregaEl", fecha(p.get("fecha_entrega_prometida")));
        r.put("listaPrecioId", p.get("lista_precio_id"));
        r.put("precioCongeladoEn", momento(p.get("precio_congelado_en")));
        r.put("ocurridoEn", momento(p.get("ocurrido_en")));
        r.put("registradoEn", momento(p.get("registrado_en")));
        // La última prueba de entrega y lo pendiente de reversa (F5.7, derivado de las cantidades).
        List<Map<String, Object>> entregas = jdbc.queryForList("""
                SELECT resultado, recibe_nombre, recibe_documento, latitud, longitud, ocurrido_en
                  FROM pedidos.entregas WHERE tenant_id = ? AND pedido_id = ? ORDER BY registrado_en DESC LIMIT 1""",
                quien.negocio(), p.get("id"));
        if (entregas.isEmpty()) {
            r.put("entrega", null);
        } else {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("resultado", entregas.get(0).get("resultado"));
            e.put("recibeNombre", entregas.get(0).get("recibe_nombre"));
            e.put("recibeDocumento", entregas.get(0).get("recibe_documento"));
            e.put("latitud", entregas.get(0).get("latitud"));
            e.put("longitud", entregas.get(0).get("longitud"));
            e.put("ocurridoEn", momento(entregas.get(0).get("ocurrido_en")));
            r.put("entrega", e);
        }
        List<Map<String, Object>> pendiente = jdbc.queryForList("""
                SELECT valor, lineas_pendientes FROM pedidos.v_pedidos_pendiente_de_reversa WHERE tenant_id = ? AND pedido_id = ?""",
                quien.negocio(), p.get("id"));
        r.put("pendienteDeReversa", !pendiente.isEmpty());
        r.put("valorPendienteDeReversa", pendiente.isEmpty() ? BigDecimal.ZERO : pendiente.get(0).get("valor"));
        // La venta del despacho: la única fuente es orders.pedido_id (F5.5).
        List<Map<String, Object>> venta = jdbc.queryForList("""
                SELECT uuid_id, id_order, total, condicion_pago, site_id FROM orders
                 WHERE tenant_id = ? AND pedido_id = ? AND deleted_at IS NULL""", quien.negocio(), p.get("id"));
        if (venta.isEmpty()) {
            r.put("venta", null);
        } else {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("uuid", venta.get(0).get("uuid_id"));
            v.put("numero", venta.get(0).get("id_order"));
            v.put("total", venta.get(0).get("total"));
            v.put("condicionPago", venta.get(0).get("condicion_pago"));
            v.put("siteId", venta.get(0).get("site_id"));
            r.put("venta", v);
        }

        List<Map<String, Object>> lineas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT v.linea_id, v.n, v.producto_id, m.name_product, v.pedida, v.confirmada, v.despachada, v.entregada,
                       v.recibida, v.pendiente, v.precio_visto, v.precio_confirmado, v.precio_origen, v.lista_precio_item_id
                  FROM pedidos.v_pedidos_lineas v
                  LEFT JOIN menu_products m ON m.tenant_id = v.tenant_id AND m.id_product = v.producto_id
                 WHERE v.tenant_id = ? AND v.pedido_id = ?
                 ORDER BY v.n""", quien.negocio(), id)) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lineaId", f.get("linea_id"));
            l.put("n", f.get("n"));
            l.put("productoId", f.get("producto_id"));
            l.put("producto", f.get("name_product"));
            l.put("pedida", f.get("pedida"));
            l.put("confirmada", f.get("confirmada"));
            l.put("despachada", f.get("despachada"));
            l.put("entregada", f.get("entregada"));
            l.put("recibida", f.get("recibida"));
            l.put("pendiente", f.get("pendiente"));
            l.put("precioVisto", f.get("precio_visto"));
            l.put("precioConfirmado", f.get("precio_confirmado"));
            l.put("precioOrigen", f.get("precio_origen"));
            l.put("listaPrecioItemId", f.get("lista_precio_item_id"));
            BigDecimal precio = (BigDecimal) (f.get("precio_confirmado") != null ? f.get("precio_confirmado") : f.get("precio_visto"));
            Number cantidad = (Number) (f.get("confirmada") != null ? f.get("confirmada") : f.get("pedida"));
            if (precio != null && cantidad != null) {
                total = total.add(precio.multiply(BigDecimal.valueOf(cantidad.longValue())));
            }
            lineas.add(l);
        }
        r.put("total", total);
        r.put("lineas", lineas);

        Map<UUID, List<Map<String, Object>>> lineasPorEvento = new LinkedHashMap<>();
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT el.evento_id, el.linea_id, el.cantidad, el.precio
                  FROM pedidos.pedidos_eventos e
                  JOIN pedidos.pedidos_eventos_lineas el ON el.tenant_id = e.tenant_id AND el.pedido_id = e.pedido_id AND el.evento_id = e.id
                 WHERE e.tenant_id = ? AND e.pedido_id = ?""", quien.negocio(), id)) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lineaId", f.get("linea_id"));
            l.put("cantidad", f.get("cantidad"));
            l.put("precio", f.get("precio"));
            lineasPorEvento.computeIfAbsent((UUID) f.get("evento_id"), k -> new ArrayList<>()).add(l);
        }
        List<Map<String, Object>> eventos = new ArrayList<>();
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT e.id, e.secuencia, e.tipo, e.actor, e.actor_usuario_id, u.nombre AS usuario, e.motivo, e.nota,
                       e.ocurrido_en, e.registrado_en
                  FROM pedidos.pedidos_eventos e
                  LEFT JOIN users u ON u.tenant_id = e.tenant_id AND u.id = e.actor_usuario_id
                 WHERE e.tenant_id = ? AND e.pedido_id = ?
                 ORDER BY e.secuencia""", quien.negocio(), id)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", f.get("id"));
            e.put("secuencia", f.get("secuencia"));
            e.put("tipo", f.get("tipo"));
            e.put("actor", f.get("actor"));
            e.put("usuarioId", f.get("actor_usuario_id"));
            e.put("usuario", f.get("usuario"));
            e.put("motivo", f.get("motivo"));
            e.put("nota", f.get("nota"));
            e.put("ocurridoEn", momento(f.get("ocurrido_en")));
            e.put("registradoEn", momento(f.get("registrado_en")));
            e.put("lineas", lineasPorEvento.getOrDefault((UUID) f.get("id"), List.of()));
            eventos.add(e);
        }
        r.put("eventos", eventos);
        return r;
    }

    /**
     * GET /api/pedidos/{id}/whatsapp (F5.9, D10): el texto para mandarle al cliente según el estado del
     * pedido y el enlace {@code wa.me} que lo abre. Sin integración de WhatsApp: lo manda quien lo abre.
     *
     * <p>Con el WhatsApp del cliente en su ficha (celular colombiano de 10 dígitos, o con 57), el enlace
     * va a su número; sin él, a {@code wa.me/?text=} para elegir el contacto. «Le llega» solo dice el día:
     * la franja («en la mañana») necesita la ventana de entrega del cliente, que todavía no existe.
     */
    public Map<String, Object> whatsapp(Quien quien, UUID id) {
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver los pedidos");
        Map<String, Object> d = detalleVisible(quien, id);
        String negocio = jdbc.queryForList("SELECT name FROM tenants WHERE id = ?", String.class, quien.negocio())
                .stream().findFirst().orElse("nosotros");
        String telefono = jdbc.queryForList("SELECT whatsapp FROM clientes WHERE tenant_id = ? AND documento = ?", String.class,
                quien.negocio(), d.get("clienteDocumento")).stream().filter(Objects::nonNull).findFirst().orElse(null);
        String nombre = d.get("cliente") == null ? "" : " " + d.get("cliente");
        String pedido = "su pedido #" + d.get("numero");
        Object plazo = d.get("plazoDias");
        String paga = plazo == null ? "" : " Paga: " + (((Number) plazo).intValue() == 0 ? "al recibir"
                : "a " + plazo + (((Number) plazo).intValue() == 1 ? " día" : " días")) + ".";
        @SuppressWarnings("unchecked")
        Map<String, Object> venta = (Map<String, Object>) d.get("venta");
        BigDecimal totalVenta = venta == null ? (BigDecimal) d.get("total") : (BigDecimal) venta.get("total");
        String texto = switch ((String) d.get("estado")) {
            case "CONFIRMADO" -> "Hola" + nombre + ", " + negocio + " confirmó " + pedido + "." + cuandoLlega((String) d.get("entregaEl"))
                    + " Total " + com.suresell.orders.cartera.Cartera.pesos((BigDecimal) d.get("total")) + "." + paga;
            case "AJUSTADO" -> "Hola" + nombre + ", ajustamos " + pedido + " en " + negocio + ". Total "
                    + com.suresell.orders.cartera.Cartera.pesos((BigDecimal) d.get("total")) + ". Se lo confirmamos pronto.";
            case "RETENIDO" -> "Hola" + nombre + ", " + pedido + " en " + negocio + " está en espera. Escríbanos para liberarlo.";
            case "DESPACHADO" -> "Hola" + nombre + ", " + pedido + " de " + negocio + " va en camino. Total "
                    + com.suresell.orders.cartera.Cartera.pesos(totalVenta) + "." + paga;
            case "ENTREGADO", "RECIBIDO" -> "Hola" + nombre + ", entregamos " + pedido + " de " + negocio + ". Total "
                    + com.suresell.orders.cartera.Cartera.pesos(totalVenta) + "." + paga + " ¡Gracias!";
            case "ENTREGADO_CON_NOVEDAD", "RECIBIDO_CON_NOVEDAD" -> "Hola" + nombre + ", entregamos " + pedido + " de " + negocio
                    + " con novedades. Total de lo entregado " + com.suresell.orders.cartera.Cartera.pesos(totalEntregado(d)) + ". ¡Gracias!";
            case "ENTREGA_FALLIDA" -> "Hola" + nombre + ", no pudimos entregar " + pedido + " de " + negocio
                    + ". Le escribimos para acordar la entrega.";
            case "RECHAZADO" -> "Hola" + nombre + ", no pudimos atender " + pedido + " en " + negocio + ".";
            case "CANCELADO" -> "Hola" + nombre + ", " + pedido + " en " + negocio + " quedó cancelado.";
            default -> "Hola" + nombre + ", recibimos " + pedido + " en " + negocio + ". Total "
                    + com.suresell.orders.cartera.Cartera.pesos((BigDecimal) d.get("total")) + ". Se lo confirmamos pronto.";
        };
        String numero = numeroDeWhatsapp(telefono);
        String enlace = "https://wa.me/" + (numero == null ? "" : numero) + "?text="
                + java.net.URLEncoder.encode(texto, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("texto", texto);
        r.put("telefono", numero);
        r.put("enlace", enlace);
        return r;
    }

    /** « Le llega hoy.», « Le llega mañana.», « Le llega el jueves.», « Le llega el 24/09.», o nada sin fecha. */
    static String cuandoLlega(String entregaEl) {
        if (entregaEl == null) {
            return "";
        }
        LocalDate dia = LocalDate.parse(entregaEl);
        LocalDate hoy = LocalDate.now(BOGOTA);
        long dias = java.time.temporal.ChronoUnit.DAYS.between(hoy, dia);
        String cuando;
        if (dias == 0) {
            cuando = "hoy";
        } else if (dias == 1) {
            cuando = "mañana";
        } else if (dias > 1 && dias < 7) {
            cuando = "el " + dia.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("es-CO"));
        } else {
            cuando = "el " + dia.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"));
        }
        return " Le llega " + cuando + ".";
    }

    /** Celular colombiano: 10 dígitos que empiezan por 3 → 57 delante; 12 que empiezan por 573 → tal cual; otro → sin número. */
    static String numeroDeWhatsapp(String telefono) {
        if (telefono == null) {
            return null;
        }
        String digitos = telefono.replaceAll("\\D", "");
        if (digitos.length() == 10 && digitos.startsWith("3")) {
            return "57" + digitos;
        }
        if (digitos.length() == 12 && digitos.startsWith("573")) {
            return digitos;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal totalEntregado(Map<String, Object> d) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : (List<Map<String, Object>>) d.get("lineas")) {
            Object entregada = l.get("entregada");
            Object precio = l.get("precioConfirmado") != null ? l.get("precioConfirmado") : l.get("precioVisto");
            if (entregada != null && precio != null) {
                total = total.add(((BigDecimal) precio).multiply(BigDecimal.valueOf(((Number) entregada).longValue())));
            }
        }
        return total;
    }

    /** La cabecera, si es de este negocio y, para un vendedor, suya. Si no, 404: no se dice que existe. */
    private Map<String, Object> cabeceraVisible(Quien quien, UUID id) {
        if (id == null) {
            throw noExiste();
        }
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT p.*, c.nombre AS cliente, u.nombre AS vendedor
                  FROM pedidos.pedidos p
                  LEFT JOIN clientes c ON c.tenant_id = p.tenant_id AND c.documento = p.cliente_documento
                  LEFT JOIN users u ON u.tenant_id = p.tenant_id AND u.id = p.vendedor_id
                 WHERE p.tenant_id = ? AND p.id = ?""", quien.negocio(), id);
        if (filas.isEmpty()) {
            throw noExiste();
        }
        Map<String, Object> p = filas.get(0);
        if (quien.esVendedor() && !Objects.equals(toLong(p.get("vendedor_id")), quien.usuarioId())
                && !Objects.equals(toLong(p.get("capturado_por")), quien.usuarioId())) {
            throw noExiste();
        }
        return p;
    }

    // ------------------------------------------------------------------ apoyo

    private Map<String, Object> pedidoPorClave(String negocio, String clave) {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT id, cliente_documento FROM pedidos.pedidos WHERE tenant_id = ? AND idempotency_key = ?", negocio, clave);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** El reintento tiene que ser el MISMO pedido: mismo cliente y mismas líneas en el mismo orden. */
    private void exigirMismoPedido(String negocio, Map<String, Object> existente, String documento, List<LineaNueva> lineas) {
        List<String> guardadas = jdbc.queryForList("""
                SELECT producto_id || '|' || cantidad_pedida FROM pedidos.pedidos_lineas
                 WHERE tenant_id = ? AND pedido_id = ? ORDER BY n""", String.class, negocio, existente.get("id"));
        List<String> pedidas = lineas.stream().map(l -> l.productoId() + "|" + l.cantidad()).toList();
        if (!documento.equals(existente.get("cliente_documento")) || !guardadas.equals(pedidas)) {
            throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.IDEMPOTENCIA_REUTILIZADA,
                    "Esa clave ya se usó con otro pedido. No se escribió nada.");
        }
    }

    private List<LineaNueva> lineasNuevas(List<LineaNueva> lineas) {
        if (lineas == null || lineas.isEmpty()) {
            throw new DatoInvalidoException("lineas", "Un pedido necesita al menos una línea.");
        }
        if (lineas.size() > MAX_LINEAS) {
            throw new DatoInvalidoException("lineas", "Máximo " + MAX_LINEAS + " líneas por pedido.");
        }
        List<LineaNueva> limpias = new ArrayList<>();
        for (LineaNueva l : lineas) {
            if (l == null || l.productoId() == null || l.productoId().isBlank()) {
                throw new DatoInvalidoException("lineas", "Cada línea necesita productoId.");
            }
            if (l.cantidad() == null || l.cantidad() < 1) {
                throw new DatoInvalidoException("lineas", "La cantidad de cada línea empieza en 1.");
            }
            limpias.add(new LineaNueva(l.productoId().trim(), l.cantidad()));
        }
        return limpias;
    }

    private static List<LineaDeEvento> lineasDeEvento(List<LineaDeEvento> lineas, boolean admitePrecio) {
        if (lineas == null) {
            return List.of();
        }
        if (lineas.size() > MAX_LINEAS) {
            throw new DatoInvalidoException("lineas", "Máximo " + MAX_LINEAS + " líneas.");
        }
        List<LineaDeEvento> limpias = new ArrayList<>();
        java.util.Set<UUID> vistas = new java.util.HashSet<>();
        for (LineaDeEvento l : lineas) {
            if (l == null || l.lineaId() == null) {
                throw new DatoInvalidoException("lineas", "Cada línea necesita lineaId.");
            }
            if (!vistas.add(l.lineaId())) {
                throw new DatoInvalidoException("lineas", "La línea " + l.lineaId() + " viene dos veces.");
            }
            if (l.cantidad() == null || l.cantidad() < 0) {
                throw new DatoInvalidoException("lineas", "La cantidad de cada línea es 0 o más.");
            }
            if (l.precio() != null) {
                if (!admitePrecio) {
                    throw new DatoInvalidoException("lineas", "Un precio nuevo va por «ajustar», con el motivo ERROR_DE_PRECIO.");
                }
                if (l.precio().signum() < 0 || l.precio().scale() > 2) {
                    throw new DatoInvalidoException("lineas", "El precio es 0 o más, con máximo dos decimales.");
                }
            }
            limpias.add(l);
        }
        return limpias;
    }

    /** ¿Alguna cantidad pedida difiere de la vigente de su línea (la confirmada, o la pedida si no hay)? */
    private boolean cambiaAlgunaCantidad(String negocio, UUID id, List<LineaDeEvento> lineas) {
        Map<UUID, Integer> vigentes = new LinkedHashMap<>();
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT linea_id, COALESCE(confirmada, pedida) AS vigente FROM pedidos.v_pedidos_lineas
                 WHERE tenant_id = ? AND pedido_id = ?""", negocio, id)) {
            vigentes.put((UUID) f.get("linea_id"), ((Number) f.get("vigente")).intValue());
        }
        for (LineaDeEvento l : lineas) {
            if (!vigentes.containsKey(l.lineaId())) {
                throw new DatoInvalidoException("lineas", "La línea " + l.lineaId() + " no es de este pedido.");
            }
            if (vigentes.get(l.lineaId()).intValue() != l.cantidad()) {
                return true;
            }
        }
        return false;
    }

    private List<String> productosQueNoExisten(String negocio, List<LineaNueva> lineas) {
        String[] ids = lineas.stream().map(LineaNueva::productoId).distinct().toArray(String[]::new);
        List<String> existen = jdbc.queryForList(
                "SELECT id_product FROM menu_products WHERE tenant_id = ? AND id_product = ANY (?)", String.class, negocio, ids);
        List<String> faltan = new ArrayList<>();
        for (String p : ids) {
            if (!existen.contains(p)) {
                faltan.add(p);
            }
        }
        return faltan;
    }

    private boolean existeSede(String negocio, long sede) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sites WHERE tenant_id = ? AND id = ?)", Boolean.class, negocio, sede));
    }

    private static String motivo(String motivo, Set<String> validos, boolean obligatorio) {
        if (motivo == null || motivo.isBlank()) {
            if (obligatorio) {
                throw new DatoInvalidoException("motivo", "Falta el motivo. Es uno de: " + String.join(", ", new java.util.TreeSet<>(validos)) + ".");
            }
            return null;
        }
        String m = motivo.trim().toUpperCase(Locale.ROOT);
        if (!validos.contains(m)) {
            throw new DatoInvalidoException("motivo", "El motivo es uno de: " + String.join(", ", new java.util.TreeSet<>(validos)) + ".");
        }
        return m;
    }

    private static Accion requerida(Accion cuerpo) {
        if (cuerpo == null) {
            throw new DatoInvalidoException("idempotencyKey", "Falta el cuerpo de la acción.");
        }
        return cuerpo;
    }

    private static String obligatorio(String valor, String campo, String mensaje) {
        if (valor == null || valor.isBlank()) {
            throw new DatoInvalidoException(campo, mensaje);
        }
        return valor.trim();
    }

    private static void exigirRol(Quien quien, Set<String> roles, String queCosa) {
        if (!roles.contains(quien.rol())) {
            throw new SoloAdministradorException(queCosa);
        }
    }

    private static void exigirUsuario(Quien quien) {
        if (quien.usuarioId() == null) {
            throw new PedidoRechazadoException(HttpStatus.FORBIDDEN, PedidoRechazadoException.SIN_USUARIO,
                    "La sesión no corresponde a un usuario de este negocio: vuelve a iniciar sesión.");
        }
    }

    private void fijarAutor(Quien quien) {
        jdbc.query("SELECT set_config('app.user_id', ?, true)", rs -> null, String.valueOf(quien.usuarioId()));
    }

    private static PedidoRechazadoException noExiste() {
        return new PedidoRechazadoException(HttpStatus.NOT_FOUND, PedidoRechazadoException.NO_EXISTE,
                "Ese pedido no existe en el negocio.");
    }

    /**
     * Lo que rechaza la función (P0001) sale con su código. Lo que la aplicación ya
     * valida antes no debería llegar aquí; si llega (una carrera), sale igual de legible.
     */
    private static <T> T traducir(Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (DataAccessException e) {
            SQLException sql = null;
            for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
                if (t instanceof SQLException s) {
                    sql = s;
                    break;
                }
            }
            if (sql == null || !"P0001".equals(sql.getSQLState()) || sql.getMessage() == null) {
                throw e;
            }
            String texto = sql.getMessage();
            int corte = texto.indexOf('\n');
            texto = (corte < 0 ? texto : texto.substring(0, corte)).replaceFirst("^ERROR: ", "").trim();
            if (texto.startsWith("Un pedido ") && texto.contains(" no pasa a ")) {
                throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.TRANSICION_NO_PERMITIDA, texto);
            }
            if (texto.startsWith("Ese pedido no existe")) {
                throw noExiste();
            }
            if (texto.startsWith("La clave ") && texto.contains("ya se uso para otro evento")) {
                throw new PedidoRechazadoException(HttpStatus.CONFLICT, PedidoRechazadoException.IDEMPOTENCIA_REUTILIZADA,
                        "Esa clave ya se usó con otra acción. No se escribió nada.");
            }
            if (texto.startsWith("Pedido sin usuario") || (texto.startsWith("El usuario ") && texto.contains("no es de este negocio"))) {
                throw new PedidoRechazadoException(HttpStatus.FORBIDDEN, PedidoRechazadoException.SIN_USUARIO, texto);
            }
            if (texto.contains("del futuro")) {
                throw new DatoInvalidoException("ocurridoEn", "La hora de la captura no puede estar en el futuro.");
            }
            if (texto.startsWith("Una entrega con diferencias")) {
                throw new DatoInvalidoException("resultado", "Una entrega con diferencias es ENTREGADO_CON_NOVEDAD, con su motivo.");
            }
            if (texto.startsWith("Una linea no puede entregar mas")) {
                throw new DatoInvalidoException("lineas", "Una línea no puede entregar más de lo que se despachó.");
            }
            if (texto.startsWith("Una linea no es de este pedido") || texto.contains("precio")) {
                throw new DatoInvalidoException("lineas", texto);
            }
            throw e;
        }
    }

    private String aJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir las líneas del pedido", e);
        }
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static String fecha(Object o) {
        return o == null ? null : o.toString();
    }

    private static String momento(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Timestamp t) {
            return t.toInstant().atZone(BOGOTA).toOffsetDateTime().toString();
        }
        return o.toString();
    }
}
