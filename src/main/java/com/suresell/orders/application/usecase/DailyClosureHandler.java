package com.suresell.orders.application.usecase;
import com.suresell.orders.domain.model.DailyClosure;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderStatus;
import com.suresell.orders.application.dto.ClosurePreviewResponse;
import com.suresell.orders.application.dto.ClosureRequest;
import com.suresell.orders.application.dto.ClosureResponse;
import com.suresell.orders.domain.port.in.DailyClosurePort;
import com.suresell.orders.domain.port.out.DailyClosureRepositoryPort;
import com.suresell.orders.domain.port.out.OrderRepositoryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Primary
public class DailyClosureHandler
implements DailyClosurePort {
    private static final Logger log = LoggerFactory.getLogger(DailyClosureHandler.class);
    private final DailyClosureRepositoryPort closureRepositoryPort;
    private final OrderRepositoryPort orderRepositoryPort;
    private final com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort syncOutboxRepositoryPort;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** V55: `base_caja` del negocio para `baseInicial` y `baseSugerida`. */
    private final SiteService siteService;
    private static final String PAYMENT_CASH = "CASH";
    private static final String PAYMENT_CARD = "CARD";
    private static final String PAYMENT_NEQUI = "NEQUI";
    private static final String PAYMENT_QR = "QR";
    private static final String STATUS_BALANCED = "BALANCED";
    private static final String STATUS_POSITIVE_DIFF = "POSITIVE_DIFF";
    private static final String STATUS_NEGATIVE_DIFF = "NEGATIVE_DIFF";
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    @Transactional(readOnly=true)
    public ClosurePreviewResponse getClosurePreview() {
        // La pantalla tiene que mirar EXACTAMENTE la misma ventana que mira el
        // cierre al ejecutarse (`ExecuteDailyClosureUseCase`): desde que se
        // cerró la caja por última vez. Si una arranca a medianoche y el otro
        // en el cierre anterior, el cajero ve un número en la pantalla y el
        // cierre calcula otro — y la diferencia aparece como un descuadre suyo.
        Optional<DailyClosure> cierreAnterior = this.closureRepositoryPort.findLastClosure();
        LocalDateTime startOfDay = LocalDate.now(BOGOTA_ZONE).atStartOfDay();
        LocalDateTime ventanaDesde = cierreAnterior
                .map(DailyClosure::getClosingTime)
                .orElse(startOfDay);
        LocalDateTime endOfDay = LocalDateTime.now(BOGOTA_ZONE).with(LocalTime.MAX);
        List<Object[]> results = this.orderRepositoryPort.findTotalByPaymentMethodAndStatus(OrderStatus.pagado, ventanaDesde, endOfDay);
        Integer countOrders = this.orderRepositoryPort.countByStatus(OrderStatus.pagado, ventanaDesde, endOfDay);
        BigDecimal totalCash = BigDecimal.ZERO;
        BigDecimal totalCard = BigDecimal.ZERO;
        BigDecimal totalQr = BigDecimal.ZERO;
        // V55: con lo que arrancó este turno —la base que dejó el cierre
        // anterior (también si fue hoy); si no hay, la configurada; si no, 0.
        // Y lo que se precarga como base a dejar: siempre la configurada.
        Optional<BigDecimal> baseConfigurada = siteService.baseCajaConfigurada();
        BigDecimal baseBalance = cierreAnterior
                .map(DailyClosure::getBaseBalanceForNextDay)
                .orElseGet(() -> baseConfigurada.orElse(BigDecimal.ZERO));
        BigDecimal baseSugerida = baseConfigurada.orElse(BigDecimal.ZERO);
        // El turno que se va a cerrar sale de la MISMA cuenta que usará el
        // cierre (`ExecuteDailyClosureUseCase`): el último turno de hoy + 1.
        LocalDate hoy = LocalDate.now(BOGOTA_ZONE);
        int cierresHoy = this.closureRepositoryPort.countClosuresOn(hoy);
        int turno = this.closureRepositoryPort.ultimoTurnoDel(hoy) + 1;
        for (Object[] result : results) {
            String paymentMethod = (String)result[0];
            BigDecimal sumTotal = result[1] == null ? BigDecimal.ZERO : (BigDecimal) result[1];
            if (paymentMethod == null) {
                continue;
            }
            switch (paymentMethod) {
                case "CASH": {
                    totalCash = sumTotal;
                    break;
                }
                case "CARD": {
                    totalCard = sumTotal;
                    break;
                }
                // N2/6.6 — Nequi eliminado. V18 migró todo a QR, así que no
                // debería quedar ninguna; si apareciera una (p.ej. un APK viejo
                // que escribió antes del deploy), se SUMA a QR en vez de
                // descartarse en silencio, que descuadraría el cierre.
                case "NEQUI":
                case "QR": {
                    totalQr = totalQr.add(sumTotal);
                    break;
                }
                default: {
                }
            }
        }
        BigDecimal totalExpected = totalCash.add(totalCard).add(totalQr);
        LocalDateTime currentTime = LocalDateTime.now(BOGOTA_ZONE);
        // "Turno iniciado" en la pantalla del POS. Antes era la hora de la
        // PRIMERA VENTA del día, que no es cuando arrancó el turno: es cuando
        // llegó el primer cliente. Ahora es el arranque real de la ventana, que
        // es lo que el cierre va a cuadrar.
        LocalDateTime openingTime = ventanaDesde;
        int totalOrders = countOrders != null ? countOrders : 0;
        return new ClosurePreviewResponse(
                openingTime,
                currentTime,
                totalOrders,
                totalCash,
                totalCard,
                totalQr,
                totalExpected,
                baseBalance,
                "Preview de cierre generado correctamente para el d\u00eda actual.",
                turno,
                cierresHoy,
                ventanaDesde,
                baseBalance,
                baseSugerida);
    }
    @Transactional
    public ClosureResponse executeClosure(ClosureRequest request) {
        LocalDateTime openingTime = this.determineOpeningTime();
        LocalDateTime closingTime = LocalDateTime.now(BOGOTA_ZONE);
        List<Order> orders = this.getOrdersForPeriod(openingTime, closingTime);
        BigDecimal expectedCash = this.calculateTotalByPaymentMethod(orders, PAYMENT_CASH);
        BigDecimal expectedCard = this.calculateTotalByPaymentMethod(orders, PAYMENT_CARD);
        // N2/6.6 — Nequi eliminado: lo histórico rotulado NEQUI se contabiliza
        // dentro de QR (ambos son transferencia digital) para no perder plata del
        // cuadre ni mantener viva una categoría que ya no existe.
        BigDecimal expectedNequi = BigDecimal.ZERO;
        BigDecimal expectedQr = this.calculateTotalByPaymentMethod(orders, PAYMENT_QR)
                .add(this.calculateTotalByPaymentMethod(orders, PAYMENT_NEQUI));
        BigDecimal previousBaseBalance = this.closureRepositoryPort.findLastClosure().map(DailyClosure::getBaseBalanceForNextDay).orElse(BigDecimal.ZERO);
        BigDecimal countedCash = request.totalCountedCash().subtract(previousBaseBalance);
        BigDecimal countedCard = request.totalCountedCard();
        BigDecimal countedNequi = BigDecimal.ZERO;
        // Si un cliente viejo todavía manda countedNequi, su monto entra por QR.
        BigDecimal countedQr = request.totalCountedQr()
                .add(request.totalCountedNequi() != null ? request.totalCountedNequi() : BigDecimal.ZERO);
        BigDecimal totalExpected = expectedCash.add(expectedCard).add(expectedNequi).add(expectedQr);
        BigDecimal totalCounted = countedCash.add(countedCard).add(countedNequi).add(countedQr);
        BigDecimal difference = totalCounted.subtract(totalExpected);
        String status = this.determineStatus(difference);
        DailyClosure closure = new DailyClosure();
        closure.setId(UUID.randomUUID());
        closure.setUserName(request.userName());
        closure.setOpeningTime(openingTime);
        closure.setClosingTime(closingTime);
        closure.setTotalExpectedCash(expectedCash);
        closure.setTotalExpectedCard(expectedCard);
        closure.setTotalExpectedQr(expectedQr);
        closure.setTotalCountedCash(countedCash);
        closure.setTotalCountedCard(countedCard);
        closure.setTotalCountedQr(countedQr);
        closure.setDifferenceAmount(difference);
        closure.setStatus(status);
        closure.setNotes(request.notes());
        closure.setBaseBalanceForNextDay(request.baseBalanceForNextDay());
        DailyClosure savedClosure = this.closureRepositoryPort.save(closure);
        saveClosureToOutbox(savedClosure);
        return new ClosureResponse(savedClosure.getId(), savedClosure.getUserName(), savedClosure.getOpeningTime(), savedClosure.getClosingTime(), expectedCash, expectedCard, expectedQr, totalExpected, countedCash, countedCard, countedQr, totalCounted, difference, status, savedClosure.getNotes(), this.generateClosureMessage(status, difference), previousBaseBalance);
    }
    private void saveClosureToOutbox(DailyClosure closure) {
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("eventType", "CLOSURE_CREATED");
            payload.put("closure", closure);
            com.suresell.orders.domain.model.SyncOutbox outbox = new com.suresell.orders.domain.model.SyncOutbox();
            outbox.setAggregateType("DAILY_CLOSURE");
            outbox.setAggregateUuid(closure.getId());
            outbox.setAggregateId(0L); 
            outbox.setEventType("CLOSURE_CREATED");
            outbox.setPayloadJson(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            outbox.setNextRetryAt(System.currentTimeMillis());
            outbox.setCreatedAt(System.currentTimeMillis());
            outbox.setUpdatedAt(System.currentTimeMillis());
            syncOutboxRepositoryPort.save(outbox);
        } catch (Exception e) {
            log.error("Error encolando sincronización de cierre: {}", e.getMessage());
        }
    }    
    private LocalDateTime determineOpeningTime() {
        return this.closureRepositoryPort.findLastClosure().map(DailyClosure::getClosingTime).orElseGet(() -> {
            return this.orderRepositoryPort.findFirstByOrderByCreatedAtAsc()
                    .map(Order::getCreatedAt)
                    .orElse(LocalDateTime.now(BOGOTA_ZONE));
        });
    }
    private List<Order> getOrdersForPeriod(LocalDateTime from, LocalDateTime to) {
        return this.orderRepositoryPort.findByStatusAndPaymentMethodIsNotNullAndCreatedAtBetween(
                OrderStatus.pagado, from, to);
    }
    private BigDecimal calculateTotalByPaymentMethod(List<Order> orders, String paymentMethod) {
        return orders.stream()
                .filter(order -> paymentMethod.equalsIgnoreCase(order.getPaymentMethod()))
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private String determineStatus(BigDecimal difference) {
        int comparison = difference.compareTo(BigDecimal.ZERO);
        if (comparison == 0) {
            return STATUS_BALANCED;
        }
        if (comparison > 0) {
            return STATUS_POSITIVE_DIFF;
        }
        return STATUS_NEGATIVE_DIFF;
    }
    private String generateClosureMessage(String status, BigDecimal difference) {
        return switch (status) {
            case STATUS_BALANCED -> "\u2705 Cierre cuadrado. No hay diferencias.";
            case STATUS_POSITIVE_DIFF -> String.format("\u26a0\ufe0f Sobrante detectado: $%.2f", difference);
            case STATUS_NEGATIVE_DIFF -> String.format("\u274c Faltante detectado: $%.2f", difference.abs());
            default -> "Cierre ejecutado";
        };
    }
    public DailyClosureHandler(DailyClosureRepositoryPort closureRepositoryPort, OrderRepositoryPort orderRepositoryPort, com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort syncOutboxRepositoryPort, com.fasterxml.jackson.databind.ObjectMapper objectMapper, SiteService siteService) {
        this.closureRepositoryPort = closureRepositoryPort;
        this.orderRepositoryPort = orderRepositoryPort;
        this.syncOutboxRepositoryPort = syncOutboxRepositoryPort;
        this.objectMapper = objectMapper;
        this.siteService = siteService;
    }
}
