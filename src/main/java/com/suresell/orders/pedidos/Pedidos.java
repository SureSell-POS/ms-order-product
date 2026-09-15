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
    /** Lo despachado ya es venta (F5.5): cancelarlo sin la reversa dejaría el inventario descontado (D7). */
    static final Set<String> YA_DESPACHADO = Set.of("DESPACHADO", "ENTREGADO", "ENTREGADO_CON_NOVEDAD");
    static final Set<String> CONFIRMAN = Set.of("admin", "cajero");
    static final int MAX_LINEAS = 500;
    static final int LIMITE_DE_BANDEJA = 50;
    static final int LIMITE_MAXIMO_DE_BANDEJA = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Pedidos(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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

    /** El cuerpo de toda acción sobre un pedido; cada una usa lo suyo. */
    public record Accion(List<LineaDeEvento> lineas, String motivo, String nota, OffsetDateTime ocurridoEn,
                         String idempotencyKey) {}

    /** El pedido y si es el reintento de una clave ya usada. */
    public record Resultado(Map<String, Object> pedido, boolean repetido) {}

    // ------------------------------------------------------------------ crear

    /**
     * POST /api/pedidos. Nace ENVIADO; si quien lo toma puede confirmar (admin o cajero),
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
        if (CONFIRMAN.contains(quien.rol()) && !Boolean.FALSE.equals(cuerpo.confirmar())) {
            transicionar(quien, id, "CONFIRMADO", null, null, null, ocurrido, clave + ":confirmado");
        }
        return new Resultado(detalleVisible(quien, id), false);
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

    private void transicionar(Quien quien, UUID id, String tipo, String motivo, String nota, List<LineaDeEvento> lineas,
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
        traducir(() -> jdbc.queryForObject("SELECT pedidos.fn_pedido_transicionar(?, ?, ?, ?, ?::jsonb, ?, ?)", UUID.class,
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
        exigirRol(quien, Set.of("admin", "cajero", "vendedor"), "ver los pedidos");
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.numero, p.estado, p.origen, p.modalidad, p.cliente_documento, c.nombre AS cliente,
                       p.vendedor_id, u.nombre AS vendedor, p.fecha_entrega_prometida, p.ocurrido_en, p.plazo_dias,
                       p.condicion_pago, t.lineas, t.total, COALESCE(p.fecha_entrega_prometida, 'infinity'::date)::text AS entrega_orden
                  FROM pedidos.pedidos p
                  LEFT JOIN clientes c ON c.tenant_id = p.tenant_id AND c.documento = p.cliente_documento
                  LEFT JOIN users u ON u.tenant_id = p.tenant_id AND u.id = p.vendedor_id
                  LEFT JOIN LATERAL (SELECT count(*) AS lineas,
                                            sum(COALESCE(v.confirmada, v.pedida) * COALESCE(v.precio_confirmado, v.precio_visto)) AS total
                                       FROM pedidos.v_pedidos_lineas v
                                      WHERE v.tenant_id = p.tenant_id AND v.pedido_id = p.id) t ON true
                 WHERE p.tenant_id = ?""");
        List<Object> args = new ArrayList<>(List.of(quien.negocio()));
        if (quien.esVendedor()) {
            sql.append(" AND (p.vendedor_id = ? OR p.capturado_por = ?)");
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
            args.add(quien.usuarioId() == null ? -1L : quien.usuarioId());
        } else if (vendedorId != null) {
            sql.append(" AND p.vendedor_id = ?");
            args.add(vendedorId);
        }
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
            r.put("cursor", f.get("entrega_orden") + "_" + f.get("numero"));
            pedidos.add(r);
        }
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("pedidos", pedidos);
        salida.put("siguiente", hayMas ? pedidos.get(pedidos.size() - 1).get("cursor") : null);
        pedidos.forEach(x -> x.remove("cursor"));
        return salida;
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
