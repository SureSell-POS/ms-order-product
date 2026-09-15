package com.suresell.orders.infrastructure.web;
import com.suresell.orders.application.dto.OrderRequestRecord;
import com.suresell.orders.application.dto.OrderResponseRecord;
import com.suresell.orders.application.dto.ReciboResponse;
import com.suresell.orders.application.dto.PageResponse;
import com.suresell.orders.application.dto.PagerAvailabilityResponse;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderEditHistory;
import com.suresell.orders.domain.port.in.OrderPort;
import com.suresell.orders.infrastructure.web.adapter.OrderRequestWebAdapter;
import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.shared.exception.NegocioDeOtraSesionException;
import com.suresell.orders.shared.exception.SoloAdministradorException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@Slf4j
@Tag(name = "Orders", description = "Gestión de órdenes")
public class OrderController {
    private static final int MAX_PAGE_SIZE = 50;
    private final OrderPort orderPort;
    private final OrderRequestWebAdapter orderRequestWebAdapter;
    /**
     * El rol sale del JWT, igual que en {@code OrderDeletionController} — aqui
     * no hay Spring Security y cada endpoint que quiso comprobar el rol lo lee
     * a mano. Eran siete sitios y este controlador no era ninguno de ellos,
     * aunque es el que cambia el importe de una venta.
     */
    private final JwtTenantResolver resolver;
    public OrderController(OrderPort orderPort, OrderRequestWebAdapter orderRequestWebAdapter,
                           JwtTenantResolver resolver) {
        this.orderPort = orderPort;
        this.orderRequestWebAdapter = orderRequestWebAdapter;
        this.resolver = resolver;
    }

    /**
     * Lo que toca dinero es de administrador (2026-09-12).
     *
     * <p>Se lee el claim {@code role} del JWT, que es el patron de
     * {@code OrderDeletionController:75}. El rechazo va por
     * {@link SoloAdministradorException} para que el cuerpo salga con el
     * contrato de errores del servicio ({@code error}, {@code mensaje},
     * {@code codigo}) y no como un Map suelto.
     */
    private void exigirAdministrador(HttpServletRequest http, String queCosa) {
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!"admin".equalsIgnoreCase(role)) {
            throw new SoloAdministradorException(queCosa);
        }
    }
    /**
     * Bloqueo #4 de la fase 0, del lado del servidor: la venta que el POS guardó
     * trae su {@code tenantId}; si no es el del token, se rechaza antes de
     * escribir nada. Integridad de datos, no control de acceso: el negocio lo
     * decide el token de todas formas (ver {@link NegocioDeOtraSesionException}).
     *
     * <p>No se rechaza cuando el campo no viene (app de meseros, POS antiguo) ni
     * cuando es {@code "demo"}: es lo que el POS escribe sin sesión
     * ({@code order.service.ts:272}) y ningún negocio se llama así. Rechazarlo
     * dejaría en bucle ventas de un comercio legítimo. Queda en el log.
     */
    private static void exigirQueLaVentaSeaDeEsteNegocio(Map<String, Object> payload) {
        String delToken = com.suresell.orders.multitenant.TenantContext.get();
        Object declarado = payload == null ? null : payload.get("tenantId");
        if (delToken == null || !(declarado instanceof String deLaVenta) || deLaVenta.isBlank()) {
            return;
        }
        if ("demo".equals(deLaVenta)) {
            log.warn("Venta con tenantId 'demo' en el negocio {}: se acepta (POS sin sesion al guardarla)", delToken);
            return;
        }
        if (!deLaVenta.equals(delToken)) {
            log.warn("409 {}: venta de '{}' enviada con la sesion de '{}'. No se escribe nada.",
                    NegocioDeOtraSesionException.CODIGO, deLaVenta, delToken);
            throw new NegocioDeOtraSesionException(deLaVenta);
        }
    }

    @GetMapping("/pager-availability")
    @Operation(summary = "Obtener disponibilidad de pagers", description = "Lista los pagers disponibles y ocupados (Amarillo y Azul del 1 al 16).")
    public ResponseEntity<PagerAvailabilityResponse> getPagerAvailability() {
        return ResponseEntity.ok(orderPort.getPagerAvailability());
    }
    @PostMapping("/create")
    @Operation(summary = "Crear orden")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> payload) {
        exigirQueLaVentaSeaDeEsteNegocio(payload);
        OrderRequestRecord dto = orderRequestWebAdapter.normalize(payload);
        Order created = orderPort.createOrUpdateOrder(dto);
        // Devuelve el idOrder (número por-tenant) para que el cliente offline lo guarde
        // sin tener que re-consultar el historial. Ver docs/140 (F2).
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("message", "Orden creada con éxito");
        body.put("idOrder", created != null ? created.getIdOrder() : null);
        // F1.3 (plan §7.1): aditivo. null = la venta no es a crédito; true = superó
        // el cupo y entró con aviso (D9).
        body.put("excedeCupo", created != null ? created.getExcedeCupo() : null);
        // F1.15 (aditivo): lo que el POS declaró (`total`) menos lo que cobró el servidor.
        // 0 = coinciden; positivo = el POS pedía de más (p. ej. precio base y el cliente
        // tiene lista); null = el POS no declaró total y no hubo con qué comparar.
        body.put("totalDiscrepancia", created != null ? created.getTotalDiscrepancia() : null);
        // F4.11 (aditivo): true = la caja le vendió a crédito a un cliente ya en insolvencia;
        // la venta entró con su deuda y quedó por revisar en Cuentas por cobrar.
        body.put("revisionPorInsolvencia", created != null && Boolean.TRUE.equals(created.getRevisionPorInsolvencia()));
        // F4.12 (aditivo): el saldo a favor del cliente que se aplicó solo a esta venta. null = la venta
        // no es a crédito; 0 = a crédito sin saldo a favor aplicado. La tirilla dice «Se aplicó $X de saldo a favor».
        body.put("saldoAFavorAplicado", created != null ? created.getSaldoAFavorAplicado() : null);
        // F4.12 (aditivo): el saldo a favor que le queda al cliente, para «Saldo a favor restante: $Y». null = no es a crédito.
        body.put("saldoAFavorRestante", created != null ? created.getSaldoAFavorRestante() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
    @PutMapping("/{orderId}")
    @Operation(summary = "Editar orden (solo administrador, y solo si sigue abierta)",
            description = "403 `SOLO_ADMINISTRADOR` si el JWT no es de un administrador; "
                    + "403 `ORDEN_YA_COBRADA` si la orden ya se cobro (ahi el camino es la anulacion, "
                    + "no la edicion); 403 `ORDER_EDIT_TIME_EXCEEDED` si la orden abierta lleva mas de "
                    + "7 minutos.")
    public ResponseEntity<Map<String, String>> updateOrder(
            @Parameter(description = "ID de la orden") @PathVariable Long orderId,
            @RequestBody Map<String, Object> payload,
            HttpServletRequest http) {
        exigirAdministrador(http, "editar una orden");
        OrderRequestRecord dto = orderRequestWebAdapter.normalize(payload);
        orderPort.updateOrder(orderId, dto);
        return ResponseEntity.ok(Map.of("message", "Orden actualizada con éxito"));
    }
    @GetMapping("/historial")
    @Operation(summary = "Historial de órdenes paginado con filtros")
    public ResponseEntity<PageResponse<OrderResponseRecord>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String pagerColor,
            @RequestParam(required = false) String pagerNumber,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String reciboEstado,
            @RequestParam(required = false) Long afterId) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (afterId != null) {
            List<OrderResponseRecord> orders = orderPort.getAllOrdersKeyset(afterId, safeSize);
            return ResponseEntity.ok(new PageResponse<>(
                    orders,
                    orders.size(),
                    -1,
                    safeSize,
                    0,
                    orders.size() < safeSize));
        }
        String filterColor = (pagerColor == null || pagerColor.isEmpty() || pagerColor.equalsIgnoreCase("Todos")) ? null : pagerColor;
        String filterNumber = (pagerNumber == null || pagerNumber.isEmpty()) ? null : pagerNumber;
        // V52 — «Sin tirilla»: el POS lista las ventas cobradas cuya tirilla no salió.
        String filterRecibo = (reciboEstado == null || reciboEstado.isBlank()) ? null : reciboEstado;
        Page<OrderResponseRecord> ordersPage = orderPort.getAllOrdersPaginated(filterColor, filterNumber, orderId, filterRecibo, page, safeSize);
        return ResponseEntity.ok(PageResponse.from(ordersPage));
    }
    @GetMapping("/{orderId}")
    @Operation(summary = "Obtener orden por ID")
    public ResponseEntity<OrderResponseRecord> getOrderById(@PathVariable Long orderId) {
        OrderResponseRecord order = orderPort.getOrderById(orderId);
        return ResponseEntity.ok(order);
    }
    @PatchMapping("/{orderId}/apply-discount")
    @Operation(summary = "Aplicar cupón de descuento a orden (solo administrador)",
            description = "Cambia el TOTAL de una venta: exige rol `admin` en el JWT (403 "
                    + "`SOLO_ADMINISTRADOR` si no) y deja fila en `order_edit_history` con quien, "
                    + "cuando, el total de antes y el de despues (V57).")
    public ResponseEntity<OrderResponseRecord> applyDiscountToOrder(
            @Parameter(description = "ID de la orden") @PathVariable Long orderId,
            @RequestParam String discountCode,
            HttpServletRequest http) {
        exigirAdministrador(http, "aplicar un descuento a una orden");
        OrderResponseRecord updatedOrder = orderPort.applyDiscountToOrder(orderId, discountCode);
        return ResponseEntity.ok(updatedOrder);
    }

    /**
     * V52 — «Cobrado, no impreso». La venta ya se cobró; esto dice qué pasó con
     * su TIRILLA. No bloquea nada, no toca cocina: {@code mark-as-printed} es la
     * comanda y sigue siendo otro documento.
     */
    @PatchMapping("/{orderId}/recibo")
    @Operation(summary = "Estado de la tirilla de una venta cobrada",
            description = "estado: no_solicitado | enviado | confirmado | no_impreso | descartado. "
                    + "motivo (solo con no_impreso): agente_apagado | navegador_pide_permiso | agente_no_responde | "
                    + "impresora_sin_conexion | en_cola | error_agente | dialogo_cancelado")
    public ResponseEntity<ReciboResponse> actualizarRecibo(@PathVariable Long orderId,
                                                           @RequestBody ReciboRequest req) {
        return ResponseEntity.ok(orderPort.actualizarRecibo(orderId, req == null ? null : req.estado(),
                req == null ? null : req.motivo()));
    }

    /** Cuerpo de {@code PATCH /orders/{id}/recibo}. */
    public record ReciboRequest(String estado, String motivo) {}

    @PatchMapping("/{orderId}/mark-as-printed")
    @Operation(summary = "Marcar orden como IMPRESA", description = "Cambia el estado de la orden a 'impreso' localmente y sincroniza con AWS para que la cocina no la duplique.")
    public ResponseEntity<Map<String, String>> markAsPrinted(@PathVariable Long orderId) {
        orderPort.markAsPrinted(orderId);
        return ResponseEntity.ok(Map.of("message", "Orden marcada como impresa y sincronización encolada"));
    }

    @PutMapping("/{orderId}/deliver")
    @Operation(summary = "Marcar orden como ENTREGADA manualmente", description = "Libera el pager asociado a la orden en la base de datos local.")
    public ResponseEntity<Map<String, String>> markAsDelivered(@PathVariable Long orderId) {
        orderPort.markAsDeliveredLocally(orderId);
        return ResponseEntity.ok(Map.of("message", "Orden marcada como entregada y pager liberado"));
    }

    @DeleteMapping("/pagers/release")
    @Operation(summary = "Liberar un Pager por Color y Número", description = "Busca la orden activa con ese pager y marca el pager como devuelto localmente.")
    public ResponseEntity<Map<String, String>> releasePager(
            @RequestParam String color,
            @RequestParam String number) {
        orderPort.releasePager(color, number);
        return ResponseEntity.ok(Map.of("message", "Proceso de liberación de pager ejecutado"));
    }
}
