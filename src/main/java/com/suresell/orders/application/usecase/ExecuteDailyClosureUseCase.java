package com.suresell.orders.application.usecase;

import com.suresell.orders.shared.ZonaHoraria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.application.dto.request.ExecuteClosureRequest;
import com.suresell.orders.application.dto.responses.CashierClosureResponse;
import com.suresell.orders.domain.model.DailyClosure;
import com.suresell.orders.domain.model.ResultadoQr;
import com.suresell.orders.domain.model.SyncOutbox;
import com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort;
import com.suresell.orders.domain.service.CashflowCalculator;
import com.suresell.orders.infrastructure.persistence.DailyClosureRepository;
import com.suresell.orders.infrastructure.persistence.OrderRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.UUID;

@Log4j2
@Service
public class ExecuteDailyClosureUseCase {
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    private final OrderRepository orderRepository;
    private final DailyClosureRepository closureRepository;
    private final CashflowCalculator cashflowCalculator;
    private final ObjectMapper objectMapper;
    private final SyncOutboxRepositoryPort syncOutboxRepositoryPort;
    private final DailyPaymentRecordService dailyPaymentRecordService;
    // F5 multipago: splits de órdenes MIXED para el esperado por método.
    private final com.suresell.orders.infrastructure.persistence.OrderPaymentRepository orderPaymentRepository;
    // N3/Inc.4: el cierre se bloquea si quedan mesas sin cobrar.
    private final TableSessionService tableSessionService;
    // V34: resuelve el QR contra ms-core-app diciendo SIEMPRE de dónde salió.
    private final ConciliadorDeQr conciliadorDeQr;
    // V55: la base de caja configurada por el negocio (`sites.base_caja`).
    private final SiteService siteService;

    public ExecuteDailyClosureUseCase(OrderRepository orderRepository, DailyClosureRepository closureRepository,
                                    CashflowCalculator cashflowCalculator, ObjectMapper objectMapper,
                                    SyncOutboxRepositoryPort syncOutboxRepositoryPort,
                                    DailyPaymentRecordService dailyPaymentRecordService,
                                    com.suresell.orders.infrastructure.persistence.OrderPaymentRepository orderPaymentRepository,
                                    TableSessionService tableSessionService,
                                    ConciliadorDeQr conciliadorDeQr,
                                    SiteService siteService) {
        this.orderRepository = orderRepository;
        this.closureRepository = closureRepository;
        this.cashflowCalculator = cashflowCalculator;
        this.objectMapper = objectMapper;
        this.syncOutboxRepositoryPort = syncOutboxRepositoryPort;
        this.dailyPaymentRecordService = dailyPaymentRecordService;
        this.orderPaymentRepository = orderPaymentRepository;
        this.tableSessionService = tableSessionService;
        this.conciliadorDeQr = conciliadorDeQr;
        this.siteService = siteService;
    }

    @Transactional
    public CashierClosureResponse execute(ExecuteClosureRequest request, String userName) {
        // N3/Inc.4 — NO se puede cerrar caja con mesas sin cobrar: ese consumo
        // quedaría fuera del cuadre y aparecería como venta al día siguiente.
        // Se bloquea y se dice CUÁLES, para que el cajero sepa qué hacer.
        var mesasAbiertas = tableSessionService.pendientesDeCobro();
        if (!mesasAbiertas.isEmpty()) {
            String detalle = mesasAbiertas.stream()
                    .map(m -> "mesa " + m.getTableId())
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException(String.format(
                    "Hay %d mesa(s) abiertas sin cobrar (%s). Ciérralas antes de cerrar la caja.",
                    mesasAbiertas.size(), detalle));
        }
        BigDecimal calculatedTotalCash = cashflowCalculator.calculateTotalCash(request.cashDetail());

        // V55 — La base del turno siguiente la DECLARA el cajero (precargada
        // con la del negocio), no la calcula el sistema por denominaciones.
        // Orden: lo que vino en la petición; si no, `base_caja` de la sede por
        // defecto; si tampoco, 0. Negativa no vale: es un dato de entrada.
        if (request.baseForNextDay() != null && request.baseForNextDay().signum() < 0) {
            throw new CodigosDeProducto.CampoInvalido("baseForNextDay",
                    "La base que dejas para el siguiente turno no puede ser negativa.");
        }
        BigDecimal calculatedBase = request.baseForNextDay() != null
                ? request.baseForNextDay()
                : siteService.baseCajaConfigurada().orElse(BigDecimal.ZERO);

        // Un cierre con el formulario entero en ceros no es un cierre: es un
        // envío accidental. Pasó en shark-burger el 2026-08-31 a las 09:30,
        // antes de la primera venta del día (10:04), y dejó DOS daños que
        // parecen fallos del sistema y no lo son:
        //
        //   1. El cierre de verdad, por la tarde, ya no cabía. El índice único
        //      era (negocio, fecha): solo había un cierre por día natural, y el
        //      cupo se lo había quemado la fila basura. (Desde V55 la unicidad
        //      es por turno y ya no bloquea el día, pero un turno en ceros sigue
        //      siendo basura en el historial y rompe la base del siguiente.)
        //   2. La base del día siguiente quedó en 0 y se propagó, porque sale
        //      del último cierre por `closing_time`.
        //
        // Se comprueba lo CONTADO y no las ventas: en la gaveta siempre hay al
        // menos la base del día anterior, así que un conteo de cero en los tres
        // medios a la vez no describe ninguna caja real. Y comprobar "no hubo
        // ventas" dejaría pasar el mismo formulario vacío enviado por la tarde.
        BigDecimal contadoTarjeta = request.countedCard() != null ? request.countedCard() : BigDecimal.ZERO;
        BigDecimal contadoQr = request.countedQr() != null ? request.countedQr() : BigDecimal.ZERO;
        if (calculatedTotalCash.signum() == 0 && contadoTarjeta.signum() == 0 && contadoQr.signum() == 0) {
            throw new IllegalStateException(
                    "El conteo está en ceros: no hay efectivo, ni tarjeta, ni QR. "
                    + "Un cierre así no describe ninguna caja. Cuenta al menos la "
                    + "base que quedó en la gaveta y vuelve a intentarlo.");
        }

        LocalDateTime closingTime = LocalDateTime.now(BOGOTA_ZONE);

        // La ventana del cierre y la base del día salen del MISMO cierre
        // anterior. Antes no era así: la base venía de aquí abajo
        // (`findFirstByOrderByClosingTimeDesc`) y la ventana de una consulta
        // aparte por `sellerId`. Esa asimetría ERA el defecto — el POS mandaba
        // `sellerId: 'Angie'` escrito a mano mientras los cierres se guardaban
        // con `user_name = 'Cajero 1'`, así que la consulta no encontraba nunca
        // nada y la ventana caía siempre en el `orElse(medianoche de hoy)`.
        //
        // Medido en producción el 2026-08-31: 103 de 104 cierres abrieron a las
        // 00:00:00, y 0 filas tenían `user_name = 'Angie'`.
        //
        // Mientras se cierre todos los días, "desde medianoche" y "desde el
        // cierre anterior" dan casi lo mismo y el defecto es invisible. En
        // cuanto se salta un día, las ventas de ese día no entran en NINGÚN
        // cierre: pasó el viernes 2026-08-28 con $1.920.600.
        //
        // V55: el cierre anterior es EL ÚLTIMO POR `closing_time`, sin filtrar
        // por fecha. Con varios turnos al día, el segundo turno arranca donde
        // cerró el primero, aunque haya sido hace una hora.
        Optional<DailyClosure> cierreAnterior = closureRepository.findFirstByOrderByClosingTimeDesc();
        LocalDateTime openingTime = cierreAnterior
                .map(DailyClosure::getClosingTime)
                .orElseGet(() -> ZonaHoraria.hoy().atStartOfDay());

        // V55: el turno lo calcula el servidor: el último turno de hoy + 1 (MAX
        // y no COUNT: ver `ultimoTurnoDelDia`). Si dos terminales cierran a la
        // vez el mismo turno, la base lo rechaza
        // (`uq_daily_closures_tenant_date_turno`) y sale como 409.
        int turno = closureRepository.ultimoTurnoDelDia(closingTime.toLocalDate()) + 1;

        List<Object[]> totals = orderRepository.sumTotalsByPaymentMethodAndSeller(
                openingTime,
                closingTime
        );
        log.info("totales: {}", totals);

        Map<String, BigDecimal> expected = parseTotals(totals);

        // F5 multipago: sumar los splits de las órdenes MIXED por método.
        for (Object[] row : orderPaymentRepository.sumSplitsByMethod(openingTime, closingTime)) {
            String method = String.valueOf(row[0]);
            BigDecimal amount = (BigDecimal) row[1];
            expected.merge(method, amount == null ? BigDecimal.ZERO : amount, BigDecimal::add);
        }

        // N2/6.6 — Nequi eliminado: lo que llegue rotulado NEQUI (histórico o de
        // un APK viejo) se PLIEGA dentro de QR antes de cualquier cálculo, para
        // que el cierre no arrastre una categoría que ya no existe.
        BigDecimal nequiHeredado = expected.remove("NEQUI");
        if (nequiHeredado != null && nequiHeredado.compareTo(BigDecimal.ZERO) != 0) {
            expected.merge("QR", nequiHeredado, BigDecimal::add);
            log.info("Cierre: ${} rotulados NEQUI se contabilizan como QR (Nequi eliminado)", nequiHeredado);
        }

        BigDecimal pureSales = expected.getOrDefault("CASH", BigDecimal.ZERO)
                .add(expected.getOrDefault("CARD", BigDecimal.ZERO))
                .add(expected.getOrDefault("QR", BigDecimal.ZERO));

        // Con lo que arrancó este turno: la base que dejó el cierre anterior;
        // sin cierre anterior, la configurada por el negocio; sin ella, 0.
        BigDecimal previousBase = cierreAnterior
                .map(DailyClosure::getBaseBalanceForNextDay)
                .orElseGet(() -> siteService.baseCajaConfigurada().orElse(BigDecimal.ZERO));

        BigDecimal salesCash = expected.getOrDefault("CASH", BigDecimal.ZERO);

        // Calcular gastos menores acumulados y sumarlos de forma automática en el backend
        BigDecimal totalPettyCashExpenses = BigDecimal.ZERO;
        if (request.pettyCashExpenses() != null) {
            for (com.suresell.orders.application.dto.request.PettyCashExpenseRequest expense : request.pettyCashExpenses()) {
                if (expense.amount() != null) {
                    totalPettyCashExpenses = totalPettyCashExpenses.add(expense.amount());
                }
            }
        }

        // Deducir el total de gastos menores de la caja esperada
        BigDecimal trueExpectedCash = salesCash.add(previousBase).subtract(totalPettyCashExpenses); // Ventas + Base Inicial - Gastos Menores

        expected.put("CASH", trueExpectedCash);

        // El valor de QR viaja con su procedencia (V34, reglas 5 y 6 de
        // LINEAMIENTOS): conciliado contra ms-core-app, tecleado por el cajero
        // porque no había nada registrado, o degradado por un fallo de
        // integración. Los tres casos completan el cierre; lo que cambia es lo
        // que queda escrito en la fila.
        // El QR del POS: la suma de las ventas del dia por ese medio. Ya esta
        // calculada arriba, en `expected` — es el unico de los tres hechos que
        // existe siempre, porque sale de las ventas mismas.
        BigDecimal qrDelPos = expected.getOrDefault("QR", BigDecimal.ZERO);
        ResultadoQr resultadoQr = conciliadorDeQr.resolver(
                closingTime.toLocalDate(), request.countedQr(), qrDelPos);
        BigDecimal countedQr = resultadoQr.monto();
        log.info("Valor QR a usar en cierre: {} (fuente={}, confianza={})",
                countedQr, resultadoQr.fuente(), resultadoQr.confianza());

        BigDecimal diffCash = calculatedTotalCash.subtract(trueExpectedCash);
        BigDecimal diffCard = request.countedCard().subtract(expected.getOrDefault("CARD", BigDecimal.ZERO));
        // Ya no hay diferencia propia de Nequi: la categoría desapareció.
        BigDecimal diffNequi = BigDecimal.ZERO;
        BigDecimal diffQr = countedQr.subtract(expected.getOrDefault("QR", BigDecimal.ZERO));

        BigDecimal totalDifference = diffCash.add(diffCard).add(diffNequi).add(diffQr);

        BigDecimal amountToDeposit = calculatedTotalCash.subtract(calculatedBase);
        if (amountToDeposit.compareTo(BigDecimal.ZERO) < 0) {
            amountToDeposit = BigDecimal.ZERO;
        }

        // División de cuenta: lo que el negocio DEJÓ DE COBRAR por redondeo al
        // repartir mesas entre comensales. Se SUMA AL VUELO desde la auditoría
        // de divisiones; no hay un total guardado que pueda discrepar del
        // detalle que lo explica.
        //
        // No es un faltante de caja ni una diferencia del cajero, así que NO
        // entra en `totalDifference`: se reporta aparte, como línea propia.
        // Meterlo en la diferencia haría que un cierre correcto apareciera con
        // novedades y acusaría al cajero de algo que decidió el negocio.
        BigDecimal roundingAdjustment = tableSessionService.ajustePorRedondeoEntre(openingTime, closingTime);

        DailyClosure savedClosure = saveClosureAudit(request, expected, totalDifference, openingTime, closingTime,
                userName, calculatedTotalCash, calculatedBase, diffCash, diffCard, diffNequi, diffQr, previousBase, pureSales, resultadoQr, totalPettyCashExpenses, turno);

        saveClosureToOutbox(savedClosure);

        Map<String, BigDecimal> shortages = new HashMap<>();
        if (diffCash.compareTo(BigDecimal.ZERO) < 0) shortages.put("Efectivo", diffCash);
        if (diffCard.compareTo(BigDecimal.ZERO) < 0) shortages.put("Tarjeta", diffCard);
        if (diffQr.compareTo(BigDecimal.ZERO) < 0) shortages.put("QR", diffQr);

        boolean hasNetShortage = totalDifference.compareTo(BigDecimal.ZERO) < 0;

        String message = (!hasNetShortage)
                ? "Cierre exitoso. Por favor ajuste la base."
                : "Cierre con novedades. Se detectaron faltantes. ¡Notificación enviada a Administrador!";

        return new CashierClosureResponse(
                (!hasNetShortage ? "SUCCESS" : "SHORTAGE"),
                message,
                shortages,
                calculatedBase,
                amountToDeposit,
                roundingAdjustment,
                turno
        );
    }

    private Map<String, BigDecimal> parseTotals(List<Object[]> queryResults) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Object[] result : queryResults) {
            String method = (String) result[0];
            BigDecimal amount = new BigDecimal(result[1].toString());
            map.put(method, amount);
        }
        return map;
    }

    private DailyClosure saveClosureAudit(ExecuteClosureRequest request,
                                  Map<String, BigDecimal> expected,
                                  BigDecimal totalDifference,
                                  LocalDateTime openingTime,
                                  LocalDateTime closingTime,
                                  String userName,
                                  BigDecimal calculatedTotalCash,
                                  BigDecimal calculatedBase,
                                  BigDecimal diffCash,
                                  BigDecimal diffCard,
                                  BigDecimal diffNequi,
                                  BigDecimal diffQr,
                                  BigDecimal previousBase,
                                  BigDecimal pureSales,
                                  ResultadoQr resultadoQr,
                                  BigDecimal pettyCashExpenses,
                                  int turno
                                          ) {

        DailyClosure entity = new DailyClosure();
        entity.setId(UUID.randomUUID());
        entity.setOpeningTime(openingTime);
        entity.setClosingTime(closingTime);
        entity.setClosureDate(closingTime.toLocalDate());
        entity.setTurno(turno);
        entity.setUserName(userName);
        entity.setNotes(request.notes());
        entity.setBaseBalanceForNextDay(calculatedBase != null ? calculatedBase : BigDecimal.ZERO);
        entity.setTotalSales(pureSales);
        entity.setPettyCashExpenses(pettyCashExpenses != null ? pettyCashExpenses : BigDecimal.ZERO);

        try {
            if (request.pettyCashExpenses() != null) {
                entity.setPettyCashExpensesAudit(objectMapper.writeValueAsString(request.pettyCashExpenses()));
            }
        } catch (JsonProcessingException e) {
            log.error("Error serializando auditoria de gastos menores", e);
            entity.setPettyCashExpensesAudit("ERROR_SERIALIZING_EXPENSES");
        }

        try {
            if (request.cashDetail() != null) {
                String jsonAudit = objectMapper.writeValueAsString(request.cashDetail());
                entity.setCashCountAudit(jsonAudit);
            }
        } catch (JsonProcessingException e) {
            log.error("Error serializando auditoría de billetes", e);
            entity.setCashCountAudit("ERROR_SERIALIZING_AUDIT");
        }

        entity.setTotalCountedCash(calculatedTotalCash != null ? calculatedTotalCash : BigDecimal.ZERO);
        entity.setTotalCountedCard(request.countedCard() != null ? request.countedCard() : BigDecimal.ZERO);
        entity.setTotalCountedQr(resultadoQr.monto());
        // Reglas 5 y 6: el monto no viaja solo; viaja con su fuente y su
        // confianza. Sin esto, un QR conciliado y uno degradado tras un 401 se
        // ven idénticos en la base — que es como el fallo del 2026-07-30 duró
        // tres semanas sin que nadie pudiera detectarlo.
        entity.registrarProcedenciaDelQr(resultadoQr);

        BigDecimal totalCounted = entity.getTotalCountedCash()
                .add(entity.getTotalCountedCard())
                .add(entity.getTotalCountedQr());
        entity.setTotalCounted(totalCounted);

        entity.setTotalExpectedCash(expected.getOrDefault("CASH", BigDecimal.ZERO));
        entity.setTotalExpectedCard(expected.getOrDefault("CARD", BigDecimal.ZERO));
        entity.setTotalExpectedQr(expected.getOrDefault("QR", BigDecimal.ZERO));

        BigDecimal totalExpected = entity.getTotalExpectedCash()
                .add(entity.getTotalExpectedCard())
                .add(entity.getTotalExpectedQr());
        entity.setTotalExpected(totalExpected);

        entity.setDifferenceCash(diffCash);
        entity.setDifferenceCard(diffCard);
        entity.setDifferenceQr(diffQr);

        entity.setDifferenceAmount(totalDifference);
        entity.setTotalDifference(totalDifference); // revisar si mejor quitar

        int comparison = totalDifference.compareTo(BigDecimal.ZERO);
        if (comparison == 0) {
            entity.setStatus("BALANCED");
            entity.setStatusMessage("Cierre Cuadrado");
        } else if (comparison > 0) {
            entity.setStatus("POSITIVE_DIFF");
            entity.setStatusMessage("Sobrante Detectado");
        } else {
            entity.setStatus("NEGATIVE_DIFF");
            entity.setStatusMessage("Faltante Detectado");
        }
        try {
            log.info("Datos del cierre a persistir: \n{}",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entity));
        } catch (Exception e) {
            log.warn("No se pudo imprimir la entidad en el log", e);
        }
        // Se vacía a la base AQUÍ y no al confirmar la transacción: si el
        // turno ya se cerró, el choque de unicidad tiene que salir dentro de
        // este método, donde se puede traducir a un 409 legible.
        try {
            closureRepository.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String detalle = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detalle.contains("uq_daily_closures_tenant_date_turno")) {
                throw new com.suresell.orders.shared.exception.TurnoYaCerradoException();
            }
            throw e;
        }

        return entity;
    }

    private void saveClosureToOutbox(DailyClosure closure) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "CLOSURE_CREATED");
            payload.put("closure", closure);
            SyncOutbox outbox = new SyncOutbox();
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
}
