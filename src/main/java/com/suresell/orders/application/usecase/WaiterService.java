package com.suresell.orders.application.usecase;

import com.suresell.orders.application.dto.OrderRequestRecord;
import com.suresell.orders.application.dto.WaiterDtos.CloseShiftRequest;
import com.suresell.orders.application.dto.WaiterDtos.WaiterOrderTracking;
import com.suresell.orders.application.dto.WaiterDtos.CreateWaiterRequest;
import com.suresell.orders.application.dto.WaiterDtos.MenuCategoryDto;
import com.suresell.orders.application.dto.WaiterDtos.MenuProductDto;
import com.suresell.orders.application.dto.WaiterDtos.OpenShiftRequest;
import com.suresell.orders.application.dto.WaiterDtos.ShiftSummaryResponse;
import com.suresell.orders.application.dto.WaiterDtos.WaiterOrderItem;
import com.suresell.orders.application.dto.WaiterDtos.WaiterOrderRequest;
import com.suresell.orders.application.dto.WaiterDtos.WaiterOrderResponse;
import com.suresell.orders.domain.model.MenuProduct;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderStatus;
import com.suresell.orders.domain.model.Waiter;
import com.suresell.orders.domain.model.WaiterSession;
import com.suresell.orders.domain.port.in.OrderPort;
import com.suresell.orders.infrastructure.persistence.MenuCategoryRepository;
import com.suresell.orders.infrastructure.persistence.MenuProductRepository;
import com.suresell.orders.infrastructure.persistence.OrderPaymentRepository;
import com.suresell.orders.infrastructure.persistence.OrderRepository;
import com.suresell.orders.infrastructure.persistence.WaiterRepository;
import com.suresell.orders.infrastructure.persistence.WaiterSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Módulo meseros del backend multi-tenant (F4 Inc.3, docs/200). Mismo
 * comportamiento que MobileWaiterHandler/WaiterShiftHandler del ms-order-waiter
 * legacy, scopeado por tenant vía RLS. La creación de órdenes REUSA el flujo del
 * POS (OrderPort: numeración por-tenant, tracking de cocina, descuentos) y le
 * añade idempotencia + autoría del mesero.
 */
@Service
public class WaiterService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WaiterService.class);
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    public static final String CASH = "CASH";
    private static final String MIXED = "MIXED";

    private final WaiterRepository waiterRepository;
    private final WaiterSessionRepository sessionRepository;
    private final SiteService siteService;
    private final TableSessionService tableSessionService;
    /** Clave del mesero (#20). Sin PIN configurado no cambia nada. */
    private final PinDeMeseroService pinService;
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuProductRepository menuProductRepository;
    private final OrderPort orderPort;
    /** T2 — encadenado del lado del servidor para el camino del mesero. */
    private final CadenaDelServidor cadena;
    private final RegistroDeTerminales registroDeTerminales;
    /** El mismo evaluador que usa el camino del POS: un solo criterio. */
    private final CorduraDelRelojDelDispositivo corduraDelReloj;

    public WaiterService(WaiterRepository waiterRepository,
                         WaiterSessionRepository sessionRepository,
                         OrderRepository orderRepository,
                         OrderPaymentRepository orderPaymentRepository,
                         MenuCategoryRepository menuCategoryRepository,
                         MenuProductRepository menuProductRepository,
                         OrderPort orderPort,
                         SiteService siteService,
                         TableSessionService tableSessionService,
                         PinDeMeseroService pinService,
                         CadenaDelServidor cadena,
                         RegistroDeTerminales registroDeTerminales,
                         CorduraDelRelojDelDispositivo corduraDelReloj) {
        this.cadena = cadena;
        this.registroDeTerminales = registroDeTerminales;
        this.corduraDelReloj = corduraDelReloj;
        this.waiterRepository = waiterRepository;
        this.sessionRepository = sessionRepository;
        this.pinService = pinService;
        this.orderRepository = orderRepository;
        this.orderPaymentRepository = orderPaymentRepository;
        this.menuCategoryRepository = menuCategoryRepository;
        this.menuProductRepository = menuProductRepository;
        this.orderPort = orderPort;
        this.siteService = siteService;
        this.tableSessionService = tableSessionService;
    }

    /**
     * Traduce el número de mesa a la cuenta viva de esa mesa.
     *
     * <p>Si la mesa no tiene cuenta abierta, se abre. El mesero no debería tener
     * que acordarse de "abrir la mesa" antes de tomar el pedido: en la práctica
     * el pedido ES la apertura.
     *
     * <p>Devuelve {@code null} cuando no hay que ligar a ninguna mesa: sin número,
     * o en modo Plazoleta —donde no hay mesas y el pedido va con rastreador—.
     */
    private String resolverCuentaDeMesa(Integer numeroMesa) {
        if (numeroMesa == null || !siteService.enModoRestaurante()) {
            return null;
        }
        return tableSessionService.abrirOReusar(numeroMesa, "meseros").getId().toString();
    }

    // ------------------------------------------------------------------
    // Meseros y sesiones
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Waiter> getActiveWaiters() {
        return waiterRepository.findByActiveTrueOrderByNameAsc();
    }

    /** Lista completa para el admin (incluye desactivados). */
    @Transactional(readOnly = true)
    public List<Waiter> getAllWaiters() {
        return waiterRepository.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    @Transactional
    public Waiter createWaiter(CreateWaiterRequest request) {
        if (request == null || request.name() == null || request.name().trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre del mesero es obligatorio");
        }
        Waiter waiter = new Waiter();
        waiter.setName(request.name().trim());
        waiter.setActive(true);
        waiter.setDailySaleGoal(request.dailySaleGoal());
        waiter.setDefaultCashBase(request.defaultCashBase());
        return waiterRepository.save(waiter);
    }

    /** Edición parcial desde el admin (F5): null = campo sin cambio. */
    @Transactional
    public Waiter updateWaiter(Long id, com.suresell.orders.application.dto.WaiterDtos.UpdateWaiterRequest request) {
        Waiter waiter = waiterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mesero no encontrado: " + id));
        if (request == null) {
            return waiter;
        }
        if (request.name() != null && !request.name().trim().isEmpty()) {
            waiter.setName(request.name().trim());
        }
        if (request.active() != null) {
            waiter.setActive(request.active());
        }
        if (request.dailySaleGoal() != null) {
            waiter.setDailySaleGoal(request.dailySaleGoal());
        }
        if (request.defaultCashBase() != null) {
            waiter.setDefaultCashBase(request.defaultCashBase());
        }
        return waiterRepository.save(waiter);
    }

    /**
     * Login de mesero (selección, sin clave — como el legacy). Si hay una sesión
     * ACTIVE con turno operativo (base de caja declarada) se REUTILIZA para no
     * destruir el turno; una ACTIVE sin turno se cierra y se abre una fresca.
     */
    @Transactional
    public WaiterSession login(Long waiterId) {
        return login(waiterId, null);
    }

    /**
     * Entra como ese mesero, comprobando su clave si la configuró.
     *
     * <p>Sin PIN configurado se entra directo, como siempre: la feature se
     * despliega sin dejar a nadie afuera y cada mesero decide cuándo ponerse el
     * suyo.
     */
    public WaiterSession login(Long waiterId, String pin) {
        Waiter waiter = waiterRepository.findById(waiterId)
                .orElseThrow(() -> new IllegalArgumentException("Mesero no encontrado: " + waiterId));
        pinService.verificar(waiter, pin);

        var existing = sessionRepository
                .findFirstByWaiterIdAndStatusOrderByLoginTimeDesc(waiterId, WaiterSession.STATUS_ACTIVE);
        if (existing.isPresent()) {
            WaiterSession session = existing.get();
            if (session.getOpeningCashBase() != null) {
                return session;
            }
            session.setStatus(WaiterSession.STATUS_CLOSED);
            session.setLogoutTime(LocalDateTime.now(BOGOTA_ZONE));
            sessionRepository.save(session);
        }
        return newSession(waiter, null);
    }

    @Transactional
    public void logout(UUID sessionId) {
        WaiterSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Sesión no encontrada: " + sessionId));
        session.setStatus(WaiterSession.STATUS_CLOSED);
        session.setLogoutTime(LocalDateTime.now(BOGOTA_ZONE));
        sessionRepository.save(session);
    }

    // ------------------------------------------------------------------
    // Turnos (base de caja / cierre con efectivo declarado)
    // ------------------------------------------------------------------

    @Transactional
    public WaiterSession openShift(OpenShiftRequest request) {
        if (request == null || request.waiterId() == null || request.openingCashBase() == null) {
            throw new IllegalArgumentException("waiterId y openingCashBase son obligatorios");
        }
        Waiter waiter = waiterRepository.findById(request.waiterId())
                .orElseThrow(() -> new IllegalArgumentException("Mesero no encontrado: " + request.waiterId()));

        var existing = sessionRepository
                .findFirstByWaiterIdAndStatusOrderByLoginTimeDesc(waiter.getId(), WaiterSession.STATUS_ACTIVE);
        if (existing.isPresent()) {
            WaiterSession session = existing.get();
            if (session.getOpeningCashBase() != null) {
                throw new IllegalStateException("El mesero ya tiene un turno abierto");
            }
            session.setOpeningCashBase(request.openingCashBase());
            return sessionRepository.save(session);
        }
        return newSession(waiter, request.openingCashBase());
    }

    @Transactional(readOnly = true)
    public ShiftSummaryResponse getShiftSummary(UUID sessionId) {
        WaiterSession session = requireSession(sessionId);
        return buildSummary(session, session.getDeclaredCash());
    }

    @Transactional
    public ShiftSummaryResponse closeShift(UUID sessionId, CloseShiftRequest request) {
        if (request == null || request.declaredCash() == null) {
            throw new IllegalArgumentException("declaredCash es obligatorio");
        }
        WaiterSession session = requireSession(sessionId);
        ShiftSummaryResponse summary = buildSummary(session, request.declaredCash());
        session.setDeclaredCash(request.declaredCash());
        session.setExpectedCash(summary.expectedCash());
        session.setDifference(summary.difference());
        session.setStatus(WaiterSession.STATUS_CLOSED);
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);
        session.setClosedAt(now);
        session.setLogoutTime(now);
        sessionRepository.save(session);
        return buildSummary(session, request.declaredCash());
    }

    // ------------------------------------------------------------------
    // Menú y órdenes
    // ------------------------------------------------------------------

    /** Menú anidado con los mismos nombres de campo del legacy (id/name/products). */
    @Transactional(readOnly = true)
    public List<MenuCategoryDto> getMenu() {
        // F5.8e: el mismo método del catálogo, ahora con el negocio escrito.
        Map<String, List<MenuProduct>> byCategory = menuProductRepository.findAllWithCategory(
                        com.suresell.orders.multitenant.TenantContext.get()).stream()
                .filter(p -> p.getCategory() != null)
                .collect(Collectors.groupingBy(p -> p.getCategory().getIdCategory(),
                        LinkedHashMap::new, Collectors.toList()));
        // ORDENADAS COMO LAS QUIERE EL NEGOCIO. Antes se usaba `findAll()`, que
        // no garantiza ningún orden, y la app lo arreglaba reordenando con una
        // lista de nombres de un cliente ("hamburguesas primero…"). El orden es
        // del negocio, así que sale de sus datos y llega ya resuelto.
        return menuCategoryRepository.findAllOrdenadas(com.suresell.orders.multitenant.TenantContext.get()).stream()
                .map(c -> new MenuCategoryDto(
                        c.getIdCategory(),
                        c.getNameCategory(),
                        c.getIcon(),
                        byCategory.getOrDefault(c.getIdCategory(), List.of()).stream()
                                .map(p -> new MenuProductDto(p.getIdProduct(), p.getNameProduct(),
                                        p.getPrice(), p.getActive()))
                                .toList()))
                .toList();
    }

    /**
     * Crea la orden del mesero reutilizando el flujo del POS. Si ya existe una
     * orden con la misma idempotencyKey (reintento del móvil), la devuelve.
     */
    @Transactional
    public WaiterOrderResponse createOrder(WaiterOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("El pedido debe tener items");
        }
        if (hasText(request.idempotencyKey())) {
            var existing = orderRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent()) {
                return toOrderResponse(existing.get());
            }
        }

        // Autoría del mesero: NUNCA se rechaza una venta por una sesión vieja/rota
        // (bug prod 2026-07-22: la app guarda la sesión localmente y una sesión
        // inexistente para RLS tumbaba la orden con 400). Se degrada con la mejor
        // atribución posible:
        //  - sesión ACTIVE → normal.
        //  - sesión CLOSED → se atribuye al mesero y a su sesión ACTIVE más
        //    reciente si existe (para que el turno cuadre).
        //  - sesión inexistente o id malformado → orden sin autoría + warn.
        Long waiterId = null;
        UUID sessionUuid = null;
        if (hasText(request.waiterSessionId())) {
            try {
                UUID requested = UUID.fromString(request.waiterSessionId().trim());
                var sessionOpt = sessionRepository.findById(requested);
                if (sessionOpt.isEmpty()) {
                    log.warn("Orden de mesero con sesión inexistente {} — se crea sin autoría", requested);
                } else {
                    WaiterSession session = sessionOpt.get();
                    waiterId = session.getWaiterId();
                    if (WaiterSession.STATUS_ACTIVE.equals(session.getStatus())) {
                        sessionUuid = session.getId();
                    } else {
                        sessionUuid = sessionRepository
                                .findFirstByWaiterIdAndStatusOrderByLoginTimeDesc(waiterId, WaiterSession.STATUS_ACTIVE)
                                .map(WaiterSession::getId)
                                .orElse(null);
                        log.warn("Orden de mesero con sesión CERRADA {} — reatribuida a mesero {} (sesión activa: {})",
                                requested, waiterId, sessionUuid);
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("waiterSessionId malformado '{}' — la orden se crea sin autoría", request.waiterSessionId());
            }
        }

        // N2/D1: la clave viaja DENTRO de la creación para que el dedupe ocurra en
        // el mismo paso que el insert. Antes se tageaba después (check-then-act):
        // dos envíos simultáneos del móvil podían pasar ambos la verificación
        // previa y el segundo insert chocaba contra el índice único.
        String key = hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : null;

        // Mesa real (modo Restaurante). Antes la app mandaba siempre el mismo
        // rastreador quemado y la cocina veía todas las comandas iguales; con el
        // número de mesa el pedido se liga a la cuenta de esa mesa y se acumula
        // con las rondas anteriores.
        String cuentaDeMesa = resolverCuentaDeMesa(request.mesaNumero());

        // `preparadoEnComanda` va en false: el pedido del mesero SÍ tiene que
        // entrar a la cola de cocina, que es justamente para lo que lo manda.
        // El chequeo del rastreador lo decide LA APP, no una constante acá.
        //
        // Estaba en `true` fijo porque el cliente mandaba siempre el mismo
        // rastreador quemado y validarlo mataba la segunda orden del turno con
        // un 409. Una app que elige un rastreador real puede —y debe— pedir que
        // se valide: es lo único que impide que la tablet y el POS de PC le
        // entreguen el mismo rastreador a dos clientes distintos.
        //
        // Sin el campo se conserva el comportamiento anterior, así que los APK
        // ya instalados no se enteran.
        // La orden se crea SIEMPRE, traiga o no procedencia. Un cliente viejo
        // —que no conoce `terminalId`— sigue vendiendo exactamente igual que
        // antes: `ocurrido_en` nulo y sin cadena. No peor que hoy.
        Order created = orderPort.createOrUpdateOrder(OrderRequestRecord.sinProcedencia(
                request.pagerColor(), request.pagerNumber(), request.items(),
                request.discountCode(), request.paymentMethod(), request.payments(),
                key, request.omitirChequeoDeRastreador(), cuentaDeMesa, false));
        created.setIdempotencyKey(key);
        created.setWaiterId(waiterId);
        created.setWaiterSessionId(sessionUuid);
        orderRepository.tagWaiterOrder(created.getUuidId(), key, waiterId, sessionUuid);

        // T2 — Encadenado del lado del servidor. Va DESPUÉS de crear la orden
        // porque el hash cubre los importes ya calculados y la clave de
        // idempotencia definitiva.
        //
        // Todo lo de aquí abajo es best-effort: si falla, la venta ya está
        // registrada. Perder un pedido por no poder firmarlo sería el peor
        // intercambio posible.
        encadenarSiSePuede(request, created);
        return toOrderResponse(created);
    }

    /**
     * Firma la orden del mesero, si la app declaró terminal.
     *
     * <p>Sin {@code terminalId} no se hace nada y la orden queda como siempre:
     * es el contrato viejo, que sigue valiendo. Con él, el servidor le asigna su
     * posición en la cadena de ese dispositivo y marca
     * {@code cadena_origen = 'servidor'} — porque esta cadena y la del POS no
     * prueban lo mismo y un auditor tiene que poder distinguirlas.
     */
    private void encadenarSiSePuede(WaiterOrderRequest request, Order created) {
        java.util.UUID terminal = CadenaDelServidor.terminalDe(request.terminalId());
        if (terminal == null) {
            if (request.terminalId() != null && !request.terminalId().isBlank()) {
                // Llegó algo pero no era un UUID. Se vende igual, pero que quede
                // dicho: un hueco por cliente viejo y uno por dato malformado se
                // ven igual en la fila.
                log.warn("[cadena] terminalId ilegible en la orden {}; se registra "
                        + "sin cadena", created.getIdempotencyKey());
            }
            return;
        }
        try {
            java.time.Instant ocurrido = CadenaDelServidor.ocurridoDe(request.ocurridoEn());
            // El servidor NUNCA rechaza un terminal desconocido: lo da de alta.
            // Rechazarlo convertiría un problema de registro en una venta
            // perdida (V35, §Identidad).
            registroDeTerminales.asegurarRegistrado(terminal, CadenaDelServidor.EPOCH_DEL_SERVIDOR);
            CadenaDelServidor.Eslabon eslabon = cadena.encadenar(
                    terminal, ocurrido, created,
                    com.suresell.orders.multitenant.TenantContext.get());
            if (eslabon != null) {
                // El veredicto del reloj se recalcula porque la orden nació sin
                // fecha del dispositivo —y por tanto como `sin_fecha`— y aquí se
                // le está poniendo una. Dejarlo como estaba viola
                // ck_orders_reloj_coherente (V36:315) y la base rechaza el
                // UPDATE entero. Se usa el MISMO evaluador que el camino del
                // POS: un solo criterio para los dos.
                java.time.OffsetDateTime ocurridoOffset = ocurrido == null ? null
                        : java.time.OffsetDateTime.ofInstant(ocurrido, java.time.ZoneOffset.UTC);
                String veredicto = corduraDelReloj.evaluarYRegistrar(
                        ocurridoOffset, java.time.OffsetDateTime.now(), terminal).name();
                cadena.sellar(created.getUuidId(),
                        com.suresell.orders.multitenant.TenantContext.get(),
                        eslabon, terminal, ocurrido, veredicto);
                created.setRelojVeredicto(veredicto);
                created.setTerminalId(terminal);
                created.setEpoch(CadenaDelServidor.EPOCH_DEL_SERVIDOR);
                created.setSeq(eslabon.seq());
                created.setHashAnterior(eslabon.hashAnterior());
                created.setOcurridoEn(ocurrido == null ? null
                        : java.time.OffsetDateTime.ofInstant(ocurrido, java.time.ZoneOffset.UTC));
            }
        } catch (RuntimeException e) {
            log.error("[cadena] fallo encadenando la orden {}; queda registrada "
                    + "SIN cadena:", created.getIdempotencyKey(), e);
        }
    }

    public java.util.Optional<WaiterOrderResponse> findByIdempotencyKey(String idempotencyKey) {
        if (!hasText(idempotencyKey)) {
            return java.util.Optional.empty();
        }
        return orderRepository.findByIdempotencyKey(idempotencyKey.trim()).map(this::toOrderResponse);
    }

    /** Historial del día actual (zona Bogotá) con filtros opcionales — como el legacy. */
    @Transactional(readOnly = true)
    public List<WaiterOrderResponse> getHistory(Long idOrder, String pagerNumber, String pagerColor, Long waiterId) {
        LocalDateTime start = LocalDateTime.now(BOGOTA_ZONE).with(LocalTime.MIN);
        LocalDateTime end = LocalDateTime.now(BOGOTA_ZONE).with(LocalTime.MAX);
        return orderRepository.findWaiterHistory(start, end, idOrder, pagerNumber, pagerColor, waiterId)
                .stream().map(this::toOrderResponse).toList();
    }

    // ------------------------------------------------------------------

    private WaiterSession newSession(Waiter waiter, BigDecimal openingCashBase) {
        WaiterSession session = new WaiterSession();
        session.setWaiterId(waiter.getId());
        session.setWaiterName(waiter.getName());
        session.setStatus(WaiterSession.STATUS_ACTIVE);
        session.setLoginTime(LocalDateTime.now(BOGOTA_ZONE));
        session.setOpeningCashBase(openingCashBase);
        return sessionRepository.save(session);
    }

    private WaiterSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Sesión no encontrada: " + sessionId));
    }

    /**
     * Resumen del turno. El numero que importa es `expectedCash`: es lo que la
     * cajera le va a pedir al mesero, y el faltante se lo cobran a el.
     *
     * <p>Tenia dos errores que lo movian en direcciones opuestas, los dos contra
     * el mesero segun el dia:
     *
     * <ul>
     *   <li><b>Las MIXED se perdian.</b> Una orden mixta metia su total entero
     *       bajo la etiqueta "MIXED", y `cashSales` solo leia "CASH". La plata en
     *       efectivo de esa venta —que el mesero tiene en la mano— no entraba en
     *       lo esperado: entregaba de mas y le figuraba como sobrante.</li>
     *   <li><b>Las cuentas abiertas contaban como venta.</b> Una orden de mesa
     *       nace `abierta` pero ya con metodo de pago, asi que una mesa todavia
     *       consumiendo inflaba lo esperado y le generaba un faltante por plata
     *       que nunca recibio. Con modo Restaurante encendido esto pasa todos los
     *       dias.</li>
     * </ul>
     *
     * <p>La regla, ahora igual en los dos lados del mostrador (aca y en
     * {@code WaiterSalesQueryService}, que es lo que ve la cajera): <b>solo lo
     * cobrado es venta, y una MIXED vale por sus splits reales.</b>
     */
    private ShiftSummaryResponse buildSummary(WaiterSession session, BigDecimal declaredCash) {
        // Una cuenta abierta no es una venta: todavia no se cobro nada.
        List<Order> cobradas = orderRepository.findByWaiterSessionId(session.getId()).stream()
                .filter(o -> !OrderStatus.abierta.equals(o.getStatus()))
                .toList();

        Map<String, BigDecimal> salesByMethod = new LinkedHashMap<>();
        Map<String, Long> ordersByMethod = new LinkedHashMap<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        for (Order o : cobradas) {
            String method = normalizarMetodo(o.getPaymentMethod());
            BigDecimal total = o.getTotal() == null ? BigDecimal.ZERO : o.getTotal();
            // Las MIXED NO suman aca: sus montos entran abajo, repartidos por
            // metodo real. Si sumaran, la venta se contaria dos veces.
            if (!MIXED.equals(method)) {
                salesByMethod.merge(method, total, BigDecimal::add);
            }
            ordersByMethod.merge(method, 1L, Long::sum);
            totalSales = totalSales.add(total);
        }
        for (Object[] fila : orderPaymentRepository.sumSplitsByWaiterSession(session.getId())) {
            salesByMethod.merge(normalizarMetodo((String) fila[0]), monto(fila[1]), BigDecimal::add);
        }

        BigDecimal cashSales = salesByMethod.getOrDefault(CASH, BigDecimal.ZERO);
        BigDecimal base = session.getOpeningCashBase() == null ? BigDecimal.ZERO : session.getOpeningCashBase();
        BigDecimal expectedCash = base.add(cashSales);
        BigDecimal difference = declaredCash == null ? null : declaredCash.subtract(expectedCash);
        BigDecimal dailySaleGoal = waiterRepository.findById(session.getWaiterId())
                .map(Waiter::getDailySaleGoal).orElse(null);
        return new ShiftSummaryResponse(
                session.getId(), session.getWaiterId(), session.getWaiterName(), session.getStatus(),
                session.getLoginTime(), session.getClosedAt(), session.getOpeningCashBase(),
                cashSales, expectedCash, declaredCash, difference,
                salesByMethod, ordersByMethod, totalSales, cobradas.size(), dailySaleGoal);
    }

    /** NEQUI se pliega en QR, igual que el cierre de caja y las ventas por mesero. */
    private static String normalizarMetodo(String metodo) {
        if (metodo == null || metodo.isBlank()) {
            return "OTRO";
        }
        String m = metodo.trim().toUpperCase();
        return "NEQUI".equals(m) ? "QR" : m;
    }

    private static BigDecimal monto(Object valor) {
        if (valor == null) {
            return BigDecimal.ZERO;
        }
        return valor instanceof BigDecimal bd ? bd : new BigDecimal(valor.toString());
    }

    private WaiterOrderResponse toOrderResponse(Order order) {
        // Nombres de producto resueltos por lote DENTRO de la sesión (RLS scopea
        // al tenant): no se cachean en memoria porque los ids legibles pueden
        // repetirse entre negocios.
        Set<String> ids = order.getItems() == null ? Set.of()
                : order.getItems().stream().map(i -> i.getProductId())
                        .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> names = ids.isEmpty() ? Map.of()
                : menuProductRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(MenuProduct::getIdProduct, MenuProduct::getNameProduct));
        List<WaiterOrderItem> items = order.getItems() == null ? List.of()
                : order.getItems().stream().map(i -> new WaiterOrderItem(
                        i.getProductId(),
                        i.getProductId() == null ? null : names.getOrDefault(i.getProductId(), i.getProductId()),
                        i.getQuantity(), i.getUnitPrice(), i.getTotalPrice(),
                        i.getInstructions()))
                .toList();
        return new WaiterOrderResponse(
                order.getIdOrder(),
                order.getUuidId() == null ? null : order.getUuidId().toString(),
                order.getPagerColor(), order.getPagerNumber(), order.getCreatedAt(),
                order.getStatus() == null ? null : order.getStatus().name(),
                order.getPaymentMethod(), order.getSubtotal(), order.getTotal(),
                order.getWaiterId(), order.getIdempotencyKey(), items,
                toTracking(order));
    }

    /** N2 — el estado de entrega que la app usa para saber si ya salió el pedido. */
    private static WaiterOrderTracking toTracking(Order order) {
        var t = order.getDeliveryTracking();
        if (t == null) {
            return new WaiterOrderTracking(false, false, null);
        }
        return new WaiterOrderTracking(
                Boolean.TRUE.equals(t.getDelivered()),
                Boolean.TRUE.equals(t.getPagerReturned()),
                t.getPreparationDurationSeconds());
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
