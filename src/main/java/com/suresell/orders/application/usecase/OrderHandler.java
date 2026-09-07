package com.suresell.orders.application.usecase;
import com.suresell.orders.application.dto.ApplyDiscountCommand;
import com.suresell.orders.application.dto.ApplyDiscountResult;
import com.suresell.orders.application.dto.LinkOrderCouponCommand;
import com.suresell.orders.application.dto.OrderItemDto;
import com.suresell.orders.application.dto.OrderItemRequestRecord;
import com.suresell.orders.application.dto.OrderItemResponseRecord;
import com.suresell.orders.application.dto.OrderRequestRecord;
import com.suresell.orders.application.dto.OrderResponseRecord;
import com.suresell.orders.application.dto.ProductResponse;
import com.suresell.orders.domain.model.SyncOutbox;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderDeliveryTracking;
import com.suresell.orders.domain.model.OrderEditHistory;
import com.suresell.orders.domain.model.OrderItem;
import com.suresell.orders.domain.model.OrderStatus;
import com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort;
import com.suresell.orders.domain.port.in.DiscountPort;
import com.suresell.orders.domain.port.in.OrderPort;
import com.suresell.orders.domain.port.out.OrderEditHistoryRepositoryPort;
import com.suresell.orders.domain.port.out.OrderDeliveryTrackingRepositoryPort;
import com.suresell.orders.domain.port.out.OrderItemRepositoryPort;
import com.suresell.orders.domain.port.out.OrderRepositoryPort;
import com.suresell.orders.domain.port.out.ProductCatalogPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.application.dto.PagerAvailabilityDto;
import com.suresell.orders.application.dto.PagerAvailabilityResponse;
import com.suresell.orders.domain.model.PagerColor;
import com.suresell.orders.shared.exception.OrderEditNotAllowedException;
import com.suresell.orders.shared.exception.PagerOcupadoException;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class OrderHandler implements OrderPort {
    private static final Logger log = LoggerFactory.getLogger(OrderHandler.class);
    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderDeliveryTrackingRepositoryPort orderDeliveryTrackingRepositoryPort;
    private final SyncOutboxRepositoryPort syncOutboxRepositoryPort;
    private final OrderItemRepositoryPort orderItemRepositoryPort;
    private final ProductCatalogPort productCatalogPort;
    private final DiscountPort discountService;
    private final OrderEditHistoryRepositoryPort orderEditHistoryRepository;
    private final ObjectMapper objectMapper;
    // F5 meseros: nombres de mesero en el historial (RLS acota al tenant).
    private final com.suresell.orders.infrastructure.persistence.WaiterRepository waiterRepository;
    // F5 multipago: splits por medio de pago.
    private final com.suresell.orders.infrastructure.persistence.OrderPaymentRepository orderPaymentRepository;
    // N2/6.7: los grupos y la cantidad de rastreadores salen de la config del
    // negocio, ya no del enum PagerColor ni de un 16 quemado.
    private final PagerConfigService pagerConfigService;
    // N3/#1: resolver la MESA de las órdenes para el historial.
    private final com.suresell.orders.infrastructure.persistence.TableSessionRepository tableSessionRepository;
    // V35/V36 — procedencia de la orden. Declarados AL FINAL a proposito: con
    // @RequiredArgsConstructor el orden de los campos ES el orden del
    // constructor, y meterlos en medio desplazaria todos los parametros
    // posteriores en cada punto de construccion.
    private final RegistroDeTerminales registroDeTerminales;
    private final CorduraDelRelojDelDispositivo corduraDelReloj;

    // V37 — autoria con identidad real (regla 4 de LINEAMIENTOS). Declarado AL
    // FINAL a proposito: con @RequiredArgsConstructor el orden de los campos ES
    // el del constructor, y meterlo en medio desplazaria todos los parametros
    // posteriores en cada punto de construccion.
    private final com.suresell.orders.multitenant.UsuarioDeLaPeticion usuarioDeLaPeticion;
    /**
     * Escribe la intención de descontar inventario en ESTA misma transacción.
     * Nace apagado (`inventario.intenciones.enabled`), así que se despliega a
     * oscuras. Ver la clase para por qué no es una llamada al otro servicio.
     */
    private final RegistroDeIntencionDeInventario registroDeIntencion;
    /** Ola 2: el precio por cliente lo resuelve la base (V45). Va al FINAL: @RequiredArgsConstructor. */
    private final com.suresell.orders.mayorista.ResolucionDePrecios resolucionDePrecios;
    // N2/D2: en el perfil cloud este servicio ES la nube (no hay outbox saliente),
    // así que las órdenes nacen ya sincronizadas. Ver createOrUpdateOrder.
    @org.springframework.beans.factory.annotation.Value("${sync.cloud.enabled:false}")
    private boolean cloudSyncEnabled;
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    private static final int MAX_EDIT_MINUTES = 7;

    @Override
    public PagerAvailabilityResponse getPagerAvailability() {
        List<Order> activeOrders = orderRepositoryPort.findActiveOrdersWithItems(OrderStatus.pagado);
        Set<String> occupiedPagers = activeOrders.stream()
                .filter(o -> {
                    OrderDeliveryTracking dt = o.getDeliveryTracking();
                    return o.getPagerColor() != null && o.getPagerNumber() != null &&
                           (dt == null || (!Boolean.TRUE.equals(dt.getDelivered()) && !Boolean.TRUE.equals(dt.getPagerReturned())));
                })
                .map(o -> o.getPagerColor().toUpperCase() + "-" + o.getPagerNumber())
                .collect(Collectors.toSet());
        List<PagerAvailabilityDto> available = new ArrayList<>();
        List<PagerAvailabilityDto> occupied = new ArrayList<>();
        for (var group : pagerConfigService.getGroups()) {
            PagerColor color = PagerColor.valueOf(group.code());
            for (int i = 1; i <= group.quantity(); i++) {
                String number = String.valueOf(i);
                boolean isOccupied = occupiedPagers.contains(color.name() + "-" + number);
                PagerAvailabilityDto dto = new PagerAvailabilityDto(color, number, !isOccupied);
                if (isOccupied) {
                    occupied.add(dto);
                } else {
                    available.add(dto);
                }
            }
        }
        return new PagerAvailabilityResponse(available, occupied);
    }

    @Override
    @Transactional
    public Order createOrUpdateOrder(OrderRequestRecord dto) {
        // N2/D1 — DEDUPE por idempotencia. Va ANTES de cualquier validación: en un
        // reintento el pager ya quedó ocupado por la primera orden y
        // validatePagerAvailability rechazaría con 400 en vez de devolver la orden
        // que ya existe. Con esto, el doble POST del outbox del POS (checkout +
        // SyncScheduler a la vez) deja UNA sola orden.
        String idempotencyKey = dto.idempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Order> existing = orderRepositoryPort.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Order previa = existing.get();
                log.info("Orden duplicada descartada por idempotencia (key={}, idOrder={})",
                        idempotencyKey, previa.getIdOrder());
                return previa;
            }
        }
        boolean multipago = dto.payments() != null && !dto.payments().isEmpty();
        if (multipago) {
            validatePaymentSplits(dto.payments());
        } else {
            validatePaymentMethod(dto.paymentMethod());
        }
        // N2 — Las órdenes de MESEROS no ocupan rastreador: el mesero lleva el
        // pedido a la mesa. Antes se validaba igual y, como la app manda siempre
        // el mismo pager, la SEGUNDA orden del turno moría con 409 "ya está en
        // uso". El POS de plazoleta no manda la bandera y sigue validando.
        // Las órdenes de MESA tampoco ocupan rastreador: el mesero/cajero lleva el
        // pedido a la mesa. Misma razón que el camino de meseros.
        boolean omitirPager = Boolean.TRUE.equals(dto.skipPagerCheck())
                || (dto.tableSessionId() != null && !dto.tableSessionId().isBlank());
        if (!omitirPager) {
            validatePagerAvailability(dto.pagerColor(), dto.pagerNumber(), null);
        }
        // N3 — Modo Restaurante: una orden que pertenece a una cuenta de mesa nace
        // `abierta` (consumo en curso, TODAVÍA NO COBRADO). El cobro llega después
        // sobre la sesión completa. El cierre de caja excluye este estado, así que
        // una mesa abierta no se cuenta como venta del día.
        java.util.UUID cuentaDeMesa = null;
        if (dto.tableSessionId() != null && !dto.tableSessionId().isBlank()) {
            try {
                cuentaDeMesa = java.util.UUID.fromString(dto.tableSessionId().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("La cuenta de mesa no es válida: " + dto.tableSessionId());
            }
        }

        // N3/#8 — RONDAS SIGUIENTES: si la mesa ya tiene una orden abierta, los
        // productos se SUMAN a ella en vez de crear otra. Así el historial
        // muestra UNA entrada por mesa con su total actualizado, que es como el
        // negocio piensa la cuenta.
        if (cuentaDeMesa != null) {
            List<Order> abiertas = orderRepositoryPort.findByTableSessionId(cuentaDeMesa).stream()
                    .filter(o -> OrderStatus.abierta.equals(o.getStatus()))
                    .toList();
            if (!abiertas.isEmpty()) {
                return agregarAOrdenAbierta(abiertas.get(0), dto);
            }
        }

        Order order = new Order();
        order.setPagerColor(dto.pagerColor());
        order.setPagerNumber(dto.pagerNumber());
        order.setTableSessionId(cuentaDeMesa);
        order.setStatus(cuentaDeMesa != null ? OrderStatus.abierta : OrderStatus.pagado);
        order.setPaymentMethod(multipago ? derivePaymentMethod(dto.payments())
                : normalizePaymentMethod(dto.paymentMethod()));
        order.setCreatedAt(LocalDateTime.now(BOGOTA_ZONE));

        // ------------------------------------------------------------------
        // V36 — Las dos fechas.
        //
        // `createdAt` se sigue estampando igual que siempre: cinco servicios lo
        // leen y de él dependen cierres, analítica y rastreadores. NO cambia de
        // significado.
        //
        // `registradoEn` es el reloj del SERVIDOR, que es lo que esta línea
        // venía siendo en realidad. `ocurridoEn` es el del DISPOSITIVO, y solo
        // se puebla si el cliente la manda.
        // ------------------------------------------------------------------
        java.time.OffsetDateTime registradoEn = java.time.OffsetDateTime.now();
        order.setRegistradoEn(registradoEn);
        order.setOcurridoEn(dto.ocurridoEn());   // nulo si el cliente no la manda

        java.util.UUID terminal = parsearTerminal(dto.terminalId());
        order.setTerminalId(terminal);
        if (terminal != null) {
            // La procedencia solo tiene sentido con un terminal detrás; sin él,
            // el CHECK `ck_orders_procedencia_coherente` (V36) rechaza la fila.
            order.setEpoch(dto.epoch() == null ? 1 : dto.epoch());
            order.setSeq(dto.seq());
            order.setHashAnterior(dto.hashAnterior());
            registroDeTerminales.asegurarRegistrado(terminal, order.getEpoch());
        }

        // La deriva del reloj se REGISTRA, nunca rechaza la venta: un equipo con
        // la pila de la BIOS agotada no puede dejar sin facturar a un negocio.
        order.setRelojVeredicto(
                corduraDelReloj.evaluarYRegistrar(dto.ocurridoEn(), registradoEn, terminal).name());

        // V37 — Quien vendio. Si no se puede resolver, queda nulo: la venta no
        // se pierde por no poder firmarla. Los procesos automaticos firman con
        // UsuarioDeSistema, no con nulo.
        order.setCreatedBy(usuarioDeLaPeticion.id().orElse(null));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey);
        }
        // N2/D2 — estado de sincronización REAL. En el perfil `cloud`
        // (sync.cloud.enabled=false) este backend ES la nube: no hay
        // SyncOutboxScheduler que voltee el flag después, así que una orden
        // persistida aquí ya está sincronizada por definición. En el despliegue
        // local-first (sync.cloud.enabled=true) se mantiene la semántica vieja:
        // nace en false y el outbox la marca al confirmar la nube.
        order.setSynced(!cloudSyncEnabled);
        
        // Ola 2 (mayorista): con CLIENTE, el precio lo resuelve la base con su
        // lista (V45) y el que mandó el POS se descarta. Sin cliente, nada cambia.
        List<OrderItemRequestRecord> lineas = dto.items();
        java.util.Map<String, com.suresell.orders.mayorista.ResolucionDePrecios.Precio> preciosResueltos = java.util.Map.of();
        if (dto.clienteDocumento() != null && !dto.clienteDocumento().isBlank()) {
            preciosResueltos = resolucionDePrecios.resolver(dto.clienteDocumento().trim(), dto.items());
            lineas = resolucionDePrecios.conPrecios(dto.items(), preciosResueltos);
            order.setClienteDocumento(dto.clienteDocumento().trim());
            preciosResueltos.values().stream().map(pr -> pr.listaPrecioId()).filter(java.util.Objects::nonNull)
                    .findFirst().ifPresent(order::setListaPrecioId);
        } else if ("CREDITO".equals(normalizePaymentMethod(dto.paymentMethod()))) {
            throw new IllegalArgumentException("Una venta a crédito necesita el documento del cliente.");
        }
        BigDecimal subtotal = lineas.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setSubtotal(subtotal);
        BigDecimal total = applyDiscountIfPresent(order, dto.discountCode(), subtotal);
        order.setTotal(total);

        // V36 — El total del cliente se COMPARA, nunca se usa. El servidor ya
        // calculo el suyo arriba y es el que se guarda; aceptar el del cliente
        // dejaria a un POS manipulado fijar el importe de su propia venta.
        //
        // Pero descartarlo sin compararlo desperdicia una senal que ya llega
        // gratis: un POS alterado o un desfase de catalogo aparecen aqui.
        order.setTotalDiscrepancia(discrepanciaDeTotal(dto.totalDeclaradoPorElCliente(), total));
        
        // 1. Guardar la Orden (genera idOrder numérico)
        Order savedOrder = orderRepositoryPort.save(order);
        
        // 2. Recuperar ID generado
        Long numericId = orderRepositoryPort.findNumericIdByUuid(savedOrder.getUuidId())
                .orElseThrow(() -> new RuntimeException("Error al recuperar ID numérico de la orden recién creada"));
        
        savedOrder.setIdOrder(numericId);

        // 3. Crear y Guardar Items individualmente con el ID numérico poblado
        List<OrderItem> items = createOrderItems(savedOrder, lineas, preciosResueltos);
        for (OrderItem item : items) {
            orderItemRepositoryPort.save(item);
        }
        savedOrder.setItems(items);

        // 4. Crear e Insertar el tracking con el ID ya conocido
        //
        // Si la comanda se imprimió en papel y la cocina ya preparó el pedido con
        // ese papel, la orden nace ENTREGADA: se registra la venta, pero no entra
        // a la cola de cocina ni deja el rastreador ocupado. Es el caso de una
        // venta hecha sin internet: al sincronizar, volver a mandarla a cocina
        // duplicaría el plato y dejaría un rastreador bloqueado que nadie puede
        // liberar, porque la cocina nunca vio esa orden en pantalla.
        boolean yaPreparado = Boolean.TRUE.equals(dto.preparadoEnComanda());

        OrderDeliveryTracking tracking = new OrderDeliveryTracking();
        tracking.setOrder(savedOrder);
        tracking.setOrderId(numericId);
        tracking.setOrderIdUuid(savedOrder.getUuidId());
        tracking.setDelivered(yaPreparado);
        tracking.setPreparationDurationSeconds(null);

        // `isPrinted` es lo que saca la orden de la cola de cocina
        // (OrderDeliveryTrackingRepository.findActiveKitchenOrders filtra por él).
        if (yaPreparado) {
            savedOrder.setIsPrinted(true);
        }

        orderDeliveryTrackingRepositoryPort.save(tracking);
        savedOrder.setDeliveryTracking(tracking);

        if (multipago) {
            validateSplitSum(savedOrder, dto.payments());
            if ("MIXED".equals(savedOrder.getPaymentMethod())) {
                persistPaymentSplits(savedOrder, dto.payments());
            }
        }

        // La venta y la intención de descontar inventario entran JUNTAS o no
        // entra ninguna. Es lo que impide que exista una venta cuyo consumo de
        // insumo se perdió — un desfase que nadie nota hasta el inventario
        // físico de dentro de tres meses.
        registroDeIntencion.registrar(savedOrder, items);

        saveOrderCreatedOutbox(savedOrder, tracking);
        linkCouponIfPresent(savedOrder);
        return savedOrder;
    }

    /**
     * Agrega una ronda a la orden abierta de la mesa (N3/#8).
     *
     * Se recalculan subtotal y total con TODO lo consumido, no solo lo nuevo:
     * la cuenta de la mesa es acumulativa y el historial debe mostrar el total
     * vigente.
     */
    private Order agregarAOrdenAbierta(Order abierta, OrderRequestRecord dto) {
        List<OrderItem> nuevos = createOrderItems(abierta, dto.items());
        for (OrderItem item : nuevos) {
            orderItemRepositoryPort.save(item);
        }
        List<OrderItem> todos = orderItemRepositoryPort.findByOrderIds(List.of(abierta.getIdOrder()));
        BigDecimal subtotal = todos.stream()
                .map(OrderItem::getTotalPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal descuento = abierta.getDiscountAmount() == null
                ? BigDecimal.ZERO : abierta.getDiscountAmount();
        BigDecimal total = subtotal.subtract(descuento).max(BigDecimal.ZERO);

        // UPDATE dirigido: tocar la colección `items` de la entidad gestionada
        // hace que Hibernate lance "A collection with orphan deletion was no
        // longer referenced by the owning entity instance".
        orderRepositoryPort.actualizarTotales(abierta.getIdOrder(), subtotal, total);
        abierta.setSubtotal(subtotal);
        abierta.setTotal(total);

        // N3/#2 — Si la cocina ya había marcado esta comanda como lista, la orden
        // quedó `delivered=true` y desapareció de la cola: los platos de la
        // ronda nueva no llegaban a cocina. Se reabre para que vuelva a salir,
        // ahora con los ítems viejos en `preparado` y los nuevos resaltados.
        boolean reabierta = orderDeliveryTrackingRepositoryPort.reabrirParaCocina(abierta.getUuidId());

        log.info("Mesa: ronda agregada a la orden {} (ahora {} ítems, total {}){}",
                abierta.getIdOrder(), todos.size(), total,
                reabierta ? " — comanda reabierta en cocina" : "");
        return abierta;
    }

    // ------------------------------------------------------------------
    // F5 multipago
    // ------------------------------------------------------------------

    private static final List<String> SPLIT_METHODS = List.of("CASH", "CARD", "QR");

    private void validatePaymentSplits(List<OrderRequestRecord.PaymentSplitRecord> payments) {
        for (var split : payments) {
            if (split.method() == null || !SPLIT_METHODS.contains(normalizePaymentMethod(split.method()))) {
                throw new IllegalArgumentException("Método de pago inválido en el multipago: " + split.method());
            }
            if (split.amount() == null || split.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Cada split del multipago debe tener un monto mayor a cero");
            }
        }
    }

    /** Un solo split = ese método; varios = MIXED. */
    private String derivePaymentMethod(List<OrderRequestRecord.PaymentSplitRecord> payments) {
        Set<String> methods = payments.stream()
                .map(OrderRequestRecord.PaymentSplitRecord::method)
                .map(OrderHandler::normalizePaymentMethod)
                .collect(java.util.stream.Collectors.toSet());
        return methods.size() == 1 ? methods.iterator().next() : "MIXED";
    }

    /** La suma de splits debe igualar EXACTAMENTE el total (con descuento aplicado). */
    private void validateSplitSum(Order savedOrder, List<OrderRequestRecord.PaymentSplitRecord> payments) {
        BigDecimal sum = payments.stream()
                .map(OrderRequestRecord.PaymentSplitRecord::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(savedOrder.getTotal()) != 0) {
            throw new IllegalArgumentException(String.format(
                    "El multipago no cuadra: los medios suman $%s y el total de la orden es $%s",
                    sum, savedOrder.getTotal()));
        }
    }

    /** Solo para órdenes MIXED (un solo medio se comporta como pago simple). */
    private void persistPaymentSplits(Order savedOrder, List<OrderRequestRecord.PaymentSplitRecord> payments) {
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);
        for (var split : payments) {
            com.suresell.orders.domain.model.OrderPayment payment = new com.suresell.orders.domain.model.OrderPayment();
            payment.setOrderUuidId(savedOrder.getUuidId());
            // Se persiste NORMALIZADO: un APK viejo que mande NEQUI queda como QR
            // y el cierre no vuelve a tener una categoría que ya no existe.
            payment.setMethod(normalizePaymentMethod(split.method()));
            payment.setAmount(split.amount());
            payment.setCreatedAt(now);
            orderPaymentRepository.save(payment);
        }
    }

    @Override
    public List<OrderResponseRecord> getAllOrders() {
        List<Order> orders = orderRepositoryPort.findAllWithItems();
        Map<String, String> productNames = buildProductNameCacheFromOrders(orders);
        Map<java.util.UUID, Mesa> mesas = mesasDe(orders);
        return orders.stream().map(order -> toOrderResponseRecord(order, productNames, Map.of(), mesas)).toList();
    }

    @Override
    public Page<OrderResponseRecord> getAllOrdersPaginated(String pagerColor, String pagerNumber, Long idOrder, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> ordersPage = orderRepositoryPort.findAllOrdersOnly(pagerColor, pagerNumber, idOrder, pageable);
        if (ordersPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Long> orderIds = ordersPage.getContent().stream().map(Order::getIdOrder).toList();
        List<OrderItem> items = orderItemRepositoryPort.findByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemsByOrderId = items.stream()
                .collect(Collectors.groupingBy(oi -> oi.getOrder().getIdOrder()));
        ordersPage.getContent().forEach(order -> {
            List<OrderItem> orderItems = itemsByOrderId.getOrDefault(order.getIdOrder(), new ArrayList<>());
            order.setItems(orderItems);
        });
        Map<String, String> productNames = buildProductNameCacheFromOrders(ordersPage.getContent());
        Map<Long, String> waiterNames = buildWaiterNameCache(ordersPage.getContent());
        Map<java.util.UUID, Mesa> mesas = mesasDe(ordersPage.getContent());
        return ordersPage.map(order -> toOrderResponseRecord(order, productNames, waiterNames, mesas));
    }

    @Override
    public List<OrderResponseRecord> getAllOrdersKeyset(Long afterId, int size) {
        Pageable pageable = PageRequest.of(0, size);
        List<Order> orders = orderRepositoryPort.findOrdersAfter(afterId, pageable);
        if (orders.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> orderIds = orders.stream().map(Order::getIdOrder).toList();
        List<OrderItem> items = orderItemRepositoryPort.findByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemsByOrderId = items.stream()
                .collect(Collectors.groupingBy(oi -> oi.getOrder().getIdOrder()));
        orders.forEach(order -> {
            List<OrderItem> orderItems = itemsByOrderId.getOrDefault(order.getIdOrder(), new ArrayList<>());
            order.setItems(orderItems);
        });
        Map<String, String> productNames = buildProductNameCacheFromOrders(orders);
        Map<Long, String> waiterNames = buildWaiterNameCache(orders);
        Map<java.util.UUID, Mesa> mesas = mesasDe(orders);
        return orders.stream().map(order -> toOrderResponseRecord(order, productNames, waiterNames, mesas)).toList();
    }

    @Override
    public OrderResponseRecord getOrderById(Long orderId) {
        Order order = orderRepositoryPort.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada con ID: " + orderId));
        Map<String, String> productNames = buildProductNameCache(order.getItems());
        return toOrderResponseRecord(order, productNames);
    }

    @Override
    @Transactional
    public void updateOrder(Long orderId, OrderRequestRecord dto) {
        Order order = orderRepositoryPort.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada con ID: " + orderId));
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);
        long minutesSinceCreation = ChronoUnit.MINUTES.between(order.getCreatedAt(), now);
        if (minutesSinceCreation > MAX_EDIT_MINUTES) {
            throw new OrderEditNotAllowedException(
                    String.format(
                            "No se puede editar la orden #%d. Han pasado %d minutos desde su creación (máximo permitido: %d minutos)",
                            orderId,
                            minutesSinceCreation,
                            MAX_EDIT_MINUTES),
                    "ORDER_EDIT_TIME_EXCEEDED");
        }
        if (!(order.getPagerColor().equals(dto.pagerColor()) && order.getPagerNumber().equals(dto.pagerNumber()))) {
            validatePagerAvailability(dto.pagerColor(), dto.pagerNumber(), orderId);
        }
        List<OrderItem> previousItems = new ArrayList<>(order.getItems());
        BigDecimal previousTotal = order.getTotal();
        order.setPagerColor(dto.pagerColor());
        order.setPagerNumber(dto.pagerNumber());
        order.getItems().clear();
        List<OrderItem> newItems = createOrderItems(order, dto.items());
        order.getItems().addAll(newItems);
        BigDecimal subtotal = order.getItems().stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setSubtotal(subtotal);
        BigDecimal total = applyDiscountIfPresent(order, dto.discountCode(), subtotal);
        order.setTotal(total);
        Order savedOrder = orderRepositoryPort.save(order);
        saveOrderCreatedOutbox(savedOrder, savedOrder.getDeliveryTracking());
        saveEditHistory(orderId, previousItems, order.getItems(), previousTotal, order.getTotal());
    }

    /**
     * V35 — El terminal viene como texto del cliente. Un UUID mal formado NO
     * puede tumbar la venta: se registra sin terminal, que es peor que tenerlo
     * pero infinitamente mejor que no vender.
     */
    /**
     * Diferencia entre lo que declara el cliente y lo que calcula el servidor.
     *
     * @return {@code null} si el cliente no declaro total —no habia con que
     *         comparar, que NO es lo mismo que cero— y la diferencia en caso
     *         contrario. Cero significa "comparados y coinciden"
     */
    private BigDecimal discrepanciaDeTotal(BigDecimal declarado, BigDecimal calculado) {
        if (declarado == null || calculado == null) {
            return null;
        }
        BigDecimal diferencia = declarado.subtract(calculado);
        if (diferencia.compareTo(BigDecimal.ZERO) != 0) {
            // WARN y no ERROR: la venta es correcta —se guarda con el total del
            // servidor— pero alguien tiene que poder enterarse.
            log.warn("Discrepancia de total en la orden {}: el cliente declaro {} y el servidor "
                            + "calculo {} (diferencia {}). Se usa el del servidor.",
                    dtoRef(), declarado, calculado, diferencia);
        }
        return diferencia;
    }

    /** Solo para el mensaje del log; la orden aun no tiene numero asignado. */
    private String dtoRef() {
        return "(sin folio aun)";
    }

    private java.util.UUID parsearTerminal(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(texto.trim());
        } catch (IllegalArgumentException e) {
            log.warn("terminalId malformado '{}' — la orden se crea sin procedencia de terminal", texto);
            return null;
        }
    }

    private void saveTrackingToOutbox(OrderDeliveryTracking tracking) {
        try {
            Map<String, Object> trackingPayload = new HashMap<>();
            trackingPayload.put("orderId", tracking.getOrderId());
            trackingPayload.put("orderUuidId", tracking.getOrderIdUuid());
            trackingPayload.put("delivered", Boolean.TRUE.equals(tracking.getDelivered()));
            trackingPayload.put("pagerReturned", Boolean.TRUE.equals(tracking.getPagerReturned()));
            trackingPayload.put("preparationDurationSeconds", tracking.getPreparationDurationSeconds());
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "TRACKING_UPDATED");
            payload.put("orderId", tracking.getOrderId());
            payload.put("orderUuidId", tracking.getOrderIdUuid());
            payload.put("tracking", trackingPayload);
            SyncOutbox outbox = new SyncOutbox();
            outbox.setAggregateType("TRACKING");
            outbox.setAggregateId(tracking.getOrderId());
            outbox.setEventType("TRACKING_UPDATED");
            outbox.setPayloadJson(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setNextRetryAt(System.currentTimeMillis());
            outbox.setCreatedAt(System.currentTimeMillis());
            outbox.setUpdatedAt(System.currentTimeMillis());
            syncOutboxRepositoryPort.save(outbox);
        } catch (Exception e) {
            log.error("Error encolando sincronización de tracking: {}", e.getMessage());
        }
    }

    private void saveEditHistory(Long orderId, List<OrderItem> oldItems, List<OrderItem> newItems, BigDecimal oldTotal, BigDecimal newTotal) {
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);
        // V37 — QUIEN edito. Esta tabla guardaba que cambio y cuando, pero no
        // quien, que es la mitad que importa para el antifraude.
        Long autor = usuarioDeLaPeticion.id().orElse(null);
        Set<String> productIds = new LinkedHashSet<>();
        oldItems.forEach(item -> productIds.add(item.getProductId()));
        newItems.forEach(item -> productIds.add(item.getProductId()));
        Map<String, String> productNames = buildProductNameCacheByIds(productIds);
        for (OrderItem oldItem : oldItems) {
            boolean found = newItems.stream().anyMatch(n -> n.getProductId().equals(oldItem.getProductId()));
            if (!found) {
                OrderEditHistory history = new OrderEditHistory();
                history.setOrderId(orderId);
                history.setEditType("ITEM_REMOVED");
                history.setProductId(oldItem.getProductId());
                history.setProductName(productNames.getOrDefault(oldItem.getProductId(), oldItem.getProductId()));
                history.setOldQuantity(oldItem.getQuantity());
                history.setNewQuantity(0);
                history.setOldTotal(oldTotal);
                history.setNewTotal(newTotal);
                history.setEditedAt(now);
                history.setEditedBy(autor);
                OrderEditHistory savedHistory = orderEditHistoryRepository.save(history);
                saveEditHistoryToOutbox(savedHistory);
            }
        }
        for (OrderItem newItem : newItems) {
            OrderItem oldItem = oldItems.stream()
                    .filter(o -> o.getProductId().equals(newItem.getProductId()))
                    .findFirst()
                    .orElse(null);
            if (oldItem == null) {
                OrderEditHistory history = new OrderEditHistory();
                history.setOrderId(orderId);
                history.setEditType("ITEM_ADDED");
                history.setProductId(newItem.getProductId());
                history.setProductName(productNames.getOrDefault(newItem.getProductId(), newItem.getProductId()));
                history.setOldQuantity(0);
                history.setNewQuantity(newItem.getQuantity());
                history.setOldTotal(oldTotal);
                history.setNewTotal(newTotal);
                history.setEditedAt(now);
                history.setEditedBy(autor);
                OrderEditHistory savedHistory = orderEditHistoryRepository.save(history);
                saveEditHistoryToOutbox(savedHistory);
            } else if (oldItem.getQuantity() != newItem.getQuantity()) {
                OrderEditHistory history = new OrderEditHistory();
                history.setOrderId(orderId);
                history.setEditType("ITEM_QUANTITY_CHANGED");
                history.setProductId(newItem.getProductId());
                history.setProductName(productNames.getOrDefault(newItem.getProductId(), newItem.getProductId()));
                history.setOldQuantity(oldItem.getQuantity());
                history.setNewQuantity(newItem.getQuantity());
                history.setOldTotal(oldTotal);
                history.setNewTotal(newTotal);
                history.setEditedAt(now);
                history.setEditedBy(autor);
                OrderEditHistory savedHistory = orderEditHistoryRepository.save(history);
                saveEditHistoryToOutbox(savedHistory);
            }
        }
    }

    private void saveEditHistoryToOutbox(OrderEditHistory history) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "EDIT_HISTORY_CREATED");
            payload.put("history", history);
            SyncOutbox outbox = new SyncOutbox();
            outbox.setAggregateType("EDIT_HISTORY");
            outbox.setAggregateId(history.getId());
            outbox.setEventType("EDIT_HISTORY_CREATED");
            outbox.setPayloadJson(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setNextRetryAt(System.currentTimeMillis());
            outbox.setCreatedAt(System.currentTimeMillis());
            outbox.setUpdatedAt(System.currentTimeMillis());
            syncOutboxRepositoryPort.save(outbox);
        } catch (Exception e) {
            log.error("Error encolando sincronización de historial de edición: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public OrderResponseRecord applyDiscountToOrder(Long orderId, String discountCode) {
        Order order = orderRepositoryPort.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada con ID: " + orderId));
        if (order.getDiscountCode() != null) {
            throw new IllegalStateException("La orden ya tiene un descuento aplicado: " + order.getDiscountCode());
        }
        BigDecimal subtotal = order.getSubtotal();
        Map<String, ProductResponse> productCache = buildProductCache(order.getItems());
        List<OrderItemDto> itemsForDiscount = order.getItems().stream().map(item -> {
            ProductResponse productDetails = productCache.get(item.getProductId());
            Long productIdLong = null;
            try {
                productIdLong = Long.parseLong(item.getProductId());
            } catch (NumberFormatException ignored) {
            }
            return new OrderItemDto(
                    item.getProductId(),
                    productIdLong,
                    productDetails != null ? productDetails.nameProduct() : null,
                    productDetails != null ? productDetails.categoryName() : null,
                    item.getQuantity(),
                    item.getUnitPrice());
        }).toList();
        ApplyDiscountCommand command = new ApplyDiscountCommand(
                discountCode,
                LocalDateTime.now(BOGOTA_ZONE),
                itemsForDiscount,
                subtotal);
        ApplyDiscountResult discountResult = discountService.applyDiscount(command);
        if (!discountResult.valid()) {
            throw new IllegalArgumentException("Cupón inválido: " + discountResult.message());
        }
        order.setDiscountCode(discountResult.discountCode());
        order.setDiscountAmount(discountResult.discountAmount());
        order.setDiscountPercentage(discountResult.discountPercentage());
        BigDecimal total = discountResult.newSubtotal();
        order.setTotal(total);
        Order savedOrder = orderRepositoryPort.save(order);
        LinkOrderCouponCommand linkCommand = new LinkOrderCouponCommand(
                savedOrder.getIdOrder(),
                savedOrder.getDiscountCode(),
                savedOrder.getSubtotal(),
                savedOrder.getDiscountAmount(),
                savedOrder.getTotal());
        discountService.linkOrderWithCoupon(linkCommand);
        return toOrderResponseRecord(savedOrder, buildProductNameCache(savedOrder.getItems()));
    }

    @Override
    public Page<OrderEditHistory> getOrderEditHistory(Long orderId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (orderId != null) {
            return orderEditHistoryRepository.findByOrderIdOrderByEditedAtDesc(orderId, pageable);
        }
        return orderEditHistoryRepository.findAllByOrderByEditedAtDesc(pageable);
    }

    @Override
    @Transactional
    public void markAsDeliveredLocally(Long orderId) {
        Order order = orderRepositoryPort.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada con ID: " + orderId));
        
        OrderDeliveryTracking tracking = order.getDeliveryTracking();
        boolean isNewTracking = false;
        if (tracking == null) {
            tracking = new OrderDeliveryTracking();
            tracking.setOrder(order);
            tracking.setOrderId(order.getIdOrder());
            tracking.setOrderIdUuid(order.getUuidId());
            order.setDeliveryTracking(tracking);
            isNewTracking = true;
        } else {
            if (tracking.getOrderId() == null) {
                tracking.setOrderId(order.getIdOrder());
            }
            if (tracking.getOrderIdUuid() == null) {
                tracking.setOrderIdUuid(order.getUuidId());
            }
        }
        
        tracking.setDelivered(true);
        tracking.setPagerReturned(true);
        
        if (isNewTracking) {
            orderDeliveryTrackingRepositoryPort.save(tracking);
        }
        
        orderRepositoryPort.save(order);
        log.info("Orden #{} marcada como ENTREGADA y Pager liberado MANUALMENTE (Acción de Cajero).", orderId);
        saveTrackingToOutbox(tracking);
    }

    @Override
    @Transactional
    public void markAsPrinted(Long orderId) {
        Order order = orderRepositoryPort.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Orden no encontrada con ID: " + orderId));

        if (Boolean.TRUE.equals(order.getIsPrinted())) {
            log.info("La orden #{} ya estaba marcada como IMPRESA. Se omite actualización.", orderId);
            return;
        }

        order.setIsPrinted(true);
        orderRepositoryPort.save(order);
        log.info("Orden #{} marcada como IMPRESA físicamente (Contingencia).", orderId);
        saveOrderPrintedStatusToOutbox(order);
    }

    private void saveOrderPrintedStatusToOutbox(Order order) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "ORDER_PRINTED_UPDATED");
            payload.put("aggregateType", "ORDER");
            payload.put("aggregateId", order.getIdOrder());
            payload.put("idOrder", order.getIdOrder());
            payload.put("uuidId", order.getUuidId());
            payload.put("isPrinted", order.getIsPrinted());
            payload.put("updatedAt", LocalDateTime.now(BOGOTA_ZONE).toString());

            SyncOutbox outbox = new SyncOutbox();
            outbox.setAggregateType("ORDER");
            outbox.setAggregateId(order.getIdOrder());
            outbox.setEventType("ORDER_PRINTED_UPDATED");
            outbox.setPayloadJson(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setNextRetryAt(System.currentTimeMillis());
            outbox.setCreatedAt(System.currentTimeMillis());
            outbox.setUpdatedAt(System.currentTimeMillis());

            syncOutboxRepositoryPort.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Error critico serializando evento outbox para orden {}", order.getIdOrder());
            throw new RuntimeException("Fallo al generar payload de sincronizacion", e);
        }
    }

    /** Medios de pago vigentes (N2/6.6: Nequi eliminado). */
    // CREDITO (ola 2): la venta entra a la cartera del cliente (trigger V45).
    private static final List<String> PAYMENT_METHODS = List.of("CASH", "CARD", "QR", "CREDITO");

    /**
     * N2/6.6 — compatibilidad hacia atrás del retiro de Nequi.
     *
     * Nequi ya no existe como medio propio, pero hay **APKs viejos en campo**
     * (meseros/cocina) que todavía mandan `NEQUI`. Rechazarlos con 400 tumbaría
     * ventas en dispositivos que no controlamos, así que se normaliza a `QR`
     * (ambos son transferencia digital) en vez de fallar. Cuando ya no queden
     * clientes viejos, este mapeo se puede borrar.
     */
    static String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null) {
            return null;
        }
        String v = paymentMethod.trim().toUpperCase();
        switch (v) {
            // Etiquetas EN ESPAÑOL que manda la app de meseros (y posiblemente
            // otros clientes viejos). Sin esto, todo pedido que no fuera QR
            // moría con 400 "método de pago inválido" — que es exactamente lo
            // que impedía enviar comandas desde la app.
            case "EFECTIVO":
                return "CASH";
            case "TARJETA":
            case "DATAFONO":
            case "DATÁFONO":
                return "CARD";
            // ⚠️ NEQUI YA NO SE NORMALIZA: se RECHAZA (ver validatePaymentMethod).
            //
            // Se normalizaba a QR "porque hay APKs viejos en campo". Eso ya no
            // aplica: la última orden con NEQUI en Producción es del
            // 2026-07-23, ninguna interfaz lo ofrece desde N2/6.6, y la ronda
            // presencial deja a todos los dispositivos al día.
            //
            // Normalizar en silencio tenía un coste: la venta quedaba como QR
            // sin que nadie supiera que el cliente había mandado otra cosa, así
            // que un APK viejo podía seguir en campo indefinidamente sin que
            // ninguna señal lo delatara. Un 400 explícito lo hace visible.
            //
            // ⚠️ SECUENCIA: esto debe desplegarse DESPUÉS de la ronda. Antes,
            // un dispositivo sin actualizar que aún mandara NEQUI dejaría de
            // vender.  Ver RESUMEN-PRE-RONDA.md §secuencia.
            case "MIXTO":
                return "MIXED";
            default:
                return v;
        }
    }

    private void validatePaymentMethod(String paymentMethod) {
        String normalizado = normalizePaymentMethod(paymentMethod);
        if ("NEQUI".equals(normalizado)) {
            // Mensaje propio y no el genérico: quien lo reciba tiene que saber
            // QUÉ pasa —su aplicación está desactualizada— y no creer que
            // escribió mal un medio de pago.
            throw new IllegalArgumentException(
                    "Nequi ya no es un medio de pago. Actualiza la aplicación "
                            + "y cobra por QR.");
        }
        if (paymentMethod == null || !PAYMENT_METHODS.contains(normalizado)) {
            throw new IllegalArgumentException("Método de pago inválido. Debe ser: CASH, CARD, QR o CREDITO");
        }
    }

    private void saveOrderCreatedOutbox(Order order, OrderDeliveryTracking tracking) {
        try {
            long now = System.currentTimeMillis();
            SyncOutbox outbox = new SyncOutbox();
            outbox.setAggregateType("ORDER");
            outbox.setAggregateId(order.getIdOrder());
            outbox.setEventType("ORDER_CREATED");
            outbox.setPayloadJson(buildOrderCreatedPayload(order, tracking));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setNextRetryAt(now);
            outbox.setLastError(null);
            outbox.setCreatedAt(now);
            outbox.setUpdatedAt(now);
            outbox.setSyncedAt(null);
            syncOutboxRepositoryPort.save(outbox);
        } catch (Exception e) {
            log.error("Error encolando sincronización de orden {}: {}", order.getIdOrder(), e.getMessage());
        }
    }

    private String buildOrderCreatedPayload(Order order, OrderDeliveryTracking tracking) {
        Map<String, Object> trackingPayload = new HashMap<>();
        trackingPayload.put("orderId", order.getIdOrder());
        trackingPayload.put("orderUuidId", order.getUuidId());
        trackingPayload.put("delivered", Boolean.TRUE.equals(tracking.getDelivered()));
        trackingPayload.put("pagerReturned", Boolean.TRUE.equals(tracking.getPagerReturned()));
        trackingPayload.put("preparationDurationSeconds", tracking.getPreparationDurationSeconds());
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", "ORDER_CREATED");
        payload.put("aggregateType", "ORDER");
        payload.put("aggregateId", order.getIdOrder());
        payload.put("order", buildOrderPayload(order));
        payload.put("tracking", trackingPayload);
        payload.put("createdAt", LocalDateTime.now(BOGOTA_ZONE).toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el payload del outbox para la orden " + order.getIdOrder(), ex);
        }
    }

    private Map<String, Object> buildOrderPayload(Order order) {
        List<Map<String, Object>> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream()
                        .map(item -> {
                            Map<String, Object> itemPayload = new HashMap<>();
                            itemPayload.put("idOrderItem", item.getIdOrderItem());
                            itemPayload.put("uuidId", item.getUuidId());
                            itemPayload.put("productId", item.getProductId());
                            itemPayload.put("quantity", item.getQuantity());
                            itemPayload.put("unitPrice", item.getUnitPrice());
                            itemPayload.put("totalPrice", item.getTotalPrice());
                            itemPayload.put("instructions", item.getInstructions());
                            itemPayload.put("comboGroup", item.getComboGroup());
                            return itemPayload;
                        })
                        .toList();
        Map<String, Object> orderPayload = new HashMap<>();
        orderPayload.put("idOrder", order.getIdOrder());
        orderPayload.put("uuidId", order.getUuidId());
        orderPayload.put("pagerColor", order.getPagerColor());
        orderPayload.put("pagerNumber", order.getPagerNumber());
        orderPayload.put("createdAt", order.getCreatedAt());
        orderPayload.put("status", order.getStatus().name());
        orderPayload.put("paymentMethod", order.getPaymentMethod());
        orderPayload.put("subtotal", order.getSubtotal());
        orderPayload.put("total", order.getTotal());
        orderPayload.put("discountCode", order.getDiscountCode());
        orderPayload.put("discountPercentage", order.getDiscountPercentage());
        orderPayload.put("discountAmount", order.getDiscountAmount());
        orderPayload.put("isPrinted", Boolean.TRUE.equals(order.getIsPrinted()));
        orderPayload.put("items", items);
        return orderPayload;
    }

    private void validatePagerAvailability(String pagerColor, String pagerNumber, Long excludeOrderId) {
        Optional<Order> existingPagerOrder = orderRepositoryPort.findOccupiedPagerOrder(
                pagerColor, pagerNumber, OrderStatus.pagado);
        if (existingPagerOrder.isPresent()
                && (excludeOrderId == null || !existingPagerOrder.get().getIdOrder().equals(excludeOrderId))) {
            throw new PagerOcupadoException(
                    String.format(
                            "El pager %s %s ya está en uso por la orden #%d",
                            pagerColor,
                            pagerNumber,
                            existingPagerOrder.get().getIdOrder()),
                    "PAGER_OCUPADO");
        }
    }

    private List<OrderItem> createOrderItems(Order order, List<OrderItemRequestRecord> itemDtos) {
        return createOrderItems(order, itemDtos, java.util.Map.of());
    }

    /** Con los precios resueltos por la base (ola 2): cada línea guarda de dónde salió su precio. */
    private List<OrderItem> createOrderItems(Order order, List<OrderItemRequestRecord> itemDtos,
                                             java.util.Map<String, com.suresell.orders.mayorista.ResolucionDePrecios.Precio> precios) {
        return itemDtos.stream().map(itemDto -> {
            com.suresell.orders.mayorista.ResolucionDePrecios.Precio resuelto = precios.get(itemDto.productId());
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setOrderId(order.getIdOrder()); // ASIGNACIÓN CRÍTICA PARA SQLITE
            item.setProductId(itemDto.productId());
            item.setQuantity(itemDto.quantity());
            item.setUnitPrice(itemDto.unitPrice());
            item.setTotalPrice(itemDto.unitPrice().multiply(BigDecimal.valueOf(itemDto.quantity())));
            item.setInstructions(itemDto.instructions());
            item.setComboGroup(itemDto.comboGroup());
            // El precio aplicado se guarda con la venta, con su origen. POS = lo
            // mandó el mostrador (sin cliente); LISTA/BASE = lo resolvió la base.
            item.setPrecioOrigen(resuelto == null ? "POS" : resuelto.origen());
            item.setListaPrecioItemId(resuelto == null ? null : resuelto.listaPrecioItemId());
            return item;
        }).toList();
    }

    private BigDecimal applyDiscountIfPresent(Order order, String discountCode, BigDecimal subtotal) {
        if (discountCode == null || discountCode.trim().isEmpty()) {
            return subtotal;
        }
        Map<String, ProductResponse> productCache = buildProductCache(order.getItems());
        List<OrderItemDto> itemsForDiscount = order.getItems().stream().map(item -> {
            ProductResponse productDetails = productCache.get(item.getProductId());
            Long productIdLong = null;
            try {
                productIdLong = Long.parseLong(item.getProductId());
            } catch (NumberFormatException ignored) {
            }
            return new OrderItemDto(
                    item.getProductId(),
                    productIdLong,
                    productDetails != null ? productDetails.nameProduct() : null,
                    productDetails != null ? productDetails.categoryName() : null,
                    item.getQuantity(),
                    item.getUnitPrice());
        }).toList();
        ApplyDiscountCommand command = new ApplyDiscountCommand(
                discountCode,
                LocalDateTime.now(BOGOTA_ZONE),
                itemsForDiscount,
                subtotal);
        ApplyDiscountResult discountResult = discountService.applyDiscount(command);
        if (discountResult.valid()) {
            order.setDiscountCode(discountResult.discountCode());
            order.setDiscountAmount(discountResult.discountAmount());
            order.setDiscountPercentage(discountResult.discountPercentage());
            return discountResult.newSubtotal();
        }
        return subtotal;
    }

    private void linkCouponIfPresent(Order order) {
        if (order.getDiscountCode() != null) {
            LinkOrderCouponCommand linkCommand = new LinkOrderCouponCommand(
                    order.getIdOrder(),
                    order.getDiscountCode(),
                    order.getSubtotal(),
                    order.getDiscountAmount(),
                    order.getTotal());
            discountService.linkOrderWithCoupon(linkCommand);
        }
    }

    /** Nombres de mesero por id para las órdenes dadas (una sola query). */
    private Map<Long, String> buildWaiterNameCache(List<Order> orders) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Order order : orders) {
            if (order.getWaiterId() != null) {
                ids.add(order.getWaiterId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        waiterRepository.findAllById(ids).forEach(w -> names.put(w.getId(), w.getName()));
        return names;
    }

    /** N3/#1 — Mesa resuelta para una cuenta. */
    private record Mesa(Integer numero, String etiqueta) {
    }

    /**
     * Resuelve en UNA consulta la mesa de todas las órdenes que vengan de una
     * cuenta. En Plazoleta el mapa sale vacío y no se consulta nada.
     */
    private Map<java.util.UUID, Mesa> mesasDe(List<Order> orders) {
        Set<java.util.UUID> sesiones = orders.stream()
                .map(Order::getTableSessionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (sesiones.isEmpty()) {
            return Map.of();
        }
        Map<java.util.UUID, Mesa> resultado = new java.util.HashMap<>();
        for (Object[] fila : tableSessionRepository.findMesaPorSesion(sesiones)) {
            resultado.put((java.util.UUID) fila[0], new Mesa((Integer) fila[1], (String) fila[2]));
        }
        return resultado;
    }

    private OrderResponseRecord toOrderResponseRecord(Order order, Map<String, String> productNames) {
        return toOrderResponseRecord(order, productNames, Map.of(), mesasDe(List.of(order)));
    }

    private OrderResponseRecord toOrderResponseRecord(Order order, Map<String, String> productNames,
                                                      Map<Long, String> waiterNames) {
        return toOrderResponseRecord(order, productNames, waiterNames, mesasDe(List.of(order)));
    }

    private OrderResponseRecord toOrderResponseRecord(Order order, Map<String, String> productNames,
                                                      Map<Long, String> waiterNames,
                                                      Map<java.util.UUID, Mesa> mesas) {
        List<OrderItemResponseRecord> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream().map(item -> toOrderItemResponseRecord(item, productNames)).toList();
        OrderDeliveryTracking deliveryTracking = order.getDeliveryTracking();
        boolean delivered = deliveryTracking != null && Boolean.TRUE.equals(deliveryTracking.getDelivered());
        Integer preparationDurationSeconds = deliveryTracking != null
                ? deliveryTracking.getPreparationDurationSeconds()
                : null;
        Mesa mesa = order.getTableSessionId() == null ? null : mesas.get(order.getTableSessionId());
        return new OrderResponseRecord(
                order.getIdOrder(),
                order.getPagerColor(),
                order.getPagerNumber(),
                order.getCreatedAt(),
                order.getSubtotal(),
                order.getTotal(),
                order.getStatus().getDisplayName(),
                order.getPaymentMethod(),
                order.getDiscountCode(),
                order.getDiscountPercentage(),
                order.getDiscountAmount(),
                delivered,
                Boolean.TRUE.equals(order.getSynced()),
                Boolean.TRUE.equals(order.getIsPrinted()),
                preparationDurationSeconds,
                items,
                order.getWaiterId(),
                order.getWaiterId() == null ? null : waiterNames.get(order.getWaiterId()),
                mesa == null ? null : mesa.numero(),
                mesa == null ? null : mesa.etiqueta());
    }

    private OrderItemResponseRecord toOrderItemResponseRecord(OrderItem item, Map<String, String> productNames) {
        return new OrderItemResponseRecord(
                item.getProductId(),
                productNames.getOrDefault(item.getProductId(), item.getProductId()),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.getInstructions(),
                item.getComboGroup());
    }

    private Map<String, String> buildProductNameCacheFromOrders(List<Order> orders) {
        Set<String> productIds = new LinkedHashSet<>();
        for (Order order : orders) {
            if (order.getItems() != null) {
                order.getItems().forEach(item -> productIds.add(item.getProductId()));
            }
        }
        return buildProductNameCacheByIds(productIds);
    }

    private Map<String, String> buildProductNameCache(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<String> productIds = items.stream()
                .map(OrderItem::getProductId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return buildProductNameCacheByIds(productIds);
    }

    private Map<String, String> buildProductNameCacheByIds(Set<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductResponse> productCache = productCatalogPort.findProductsByIds(productIds);
        Map<String, String> names = productCache.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().nameProduct()));
        for (String productId : productIds) {
            names.putIfAbsent(productId, productId);
        }
        return names;
    }

    private Map<String, ProductResponse> buildProductCache(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<String> productIds = items.stream()
                .map(OrderItem::getProductId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return productCatalogPort.findProductsByIds(productIds);
    }

    @Override
    @Transactional
    public void releasePager(String color, String number) {
        List<Order> activeOrders = orderRepositoryPort.findActiveOrdersWithItems(OrderStatus.pagado);
        
        Optional<Order> targetOrder = activeOrders.stream()
                .filter(o -> color.equalsIgnoreCase(o.getPagerColor()) && number.equalsIgnoreCase(o.getPagerNumber()))
                .filter(o -> {
                    OrderDeliveryTracking dt = o.getDeliveryTracking();
                    return dt == null || (!dt.getDelivered() && !dt.getPagerReturned());
                })
                .findFirst();

        if (targetOrder.isPresent()) {
            markAsDeliveredLocally(targetOrder.get().getIdOrder());
        } else {
            log.warn("Intento de liberar Pager {} {} fallido: No se encontró orden activa asociada.", color, number);
        }
    }
}
