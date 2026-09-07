package com.suresell.orders.application.usecase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.suresell.orders.application.dto.OrderItemRequestRecord;
import com.suresell.orders.application.dto.OrderRequestRecord;
import com.suresell.orders.application.dto.OrderResponseRecord;
import com.suresell.orders.application.dto.ProductResponse;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderDeliveryTracking;
import com.suresell.orders.domain.model.SyncOutbox;
import com.suresell.orders.domain.model.OrderItem;
import com.suresell.orders.domain.model.OrderStatus;
import com.suresell.orders.domain.port.in.DiscountPort;
import com.suresell.orders.domain.port.out.OrderDeliveryTrackingRepositoryPort;
import com.suresell.orders.domain.port.out.OrderEditHistoryRepositoryPort;
import com.suresell.orders.domain.port.out.OrderItemRepositoryPort;
import com.suresell.orders.domain.port.out.OrderRepositoryPort;
import com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort;
import com.suresell.orders.domain.port.out.ProductCatalogPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class OrderHandlerTest {
    @Mock
    private OrderRepositoryPort orderRepositoryPort;
    @Mock
    private OrderDeliveryTrackingRepositoryPort orderDeliveryTrackingRepositoryPort;
    @Mock
    private SyncOutboxRepositoryPort syncOutboxRepositoryPort;
    @Mock
    private OrderItemRepositoryPort orderItemRepositoryPort;
    @Mock
    private ProductCatalogPort productCatalogPort;
    @Mock
    private DiscountPort discountPort;
    @Mock
    private OrderEditHistoryRepositoryPort orderEditHistoryRepositoryPort;
    private ObjectMapper objectMapper;
    private OrderHandler orderHandler;
    /** N2/6.7: la disponibilidad de rastreadores sale de la config del negocio. */
    private com.suresell.orders.application.usecase.PagerConfigService pagerConfigService;
    /** N3/#1: el historial resuelve la mesa contra las cuentas. */
    private com.suresell.orders.infrastructure.persistence.TableSessionRepository tableSessionRepository;
    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        pagerConfigService = org.mockito.Mockito.mock(
                com.suresell.orders.application.usecase.PagerConfigService.class);
        org.mockito.Mockito.lenient().when(pagerConfigService.getGroups()).thenReturn(List.of(
                new com.suresell.orders.application.dto.PagerGroupDto("AMARILLO", "Amarillo", "#eab308", 16),
                new com.suresell.orders.application.dto.PagerGroupDto("AZUL", "Azul", "#3b82f6", 16)));
        tableSessionRepository = org.mockito.Mockito.mock(
                com.suresell.orders.infrastructure.persistence.TableSessionRepository.class);
        orderHandler = new OrderHandler(
                orderRepositoryPort,
                orderDeliveryTrackingRepositoryPort,
                syncOutboxRepositoryPort,
                orderItemRepositoryPort,
                productCatalogPort,
                discountPort,
                orderEditHistoryRepositoryPort,
                objectMapper,
                org.mockito.Mockito.mock(com.suresell.orders.infrastructure.persistence.WaiterRepository.class),
                org.mockito.Mockito.mock(com.suresell.orders.infrastructure.persistence.OrderPaymentRepository.class),
                pagerConfigService,
                tableSessionRepository,
                // V35/V36: el registro de terminales y la cordura del reloj no
                // afectan a estas pruebas —ninguna manda procedencia— pero el
                // constructor los exige.
                org.mockito.Mockito.mock(RegistroDeTerminales.class),
                new CorduraDelRelojDelDispositivo(),
                // V37: ninguna de estas pruebas comprueba autoria, pero el
                // constructor la exige. Un mock devuelve Optional.empty(), que
                // es el caso "no se pudo resolver el autor" — y verifica de paso
                // que eso NO tumba la venta.
                org.mockito.Mockito.mock(
                        com.suresell.orders.multitenant.UsuarioDeLaPeticion.class),
                // La intención de inventario: un mock que no hace nada, igual
                // que el componente real cuando `inventario.intenciones.enabled`
                // está apagado — que es como nace. Estas pruebas comprueban la
                // venta, y el hecho de que sigan pasando fija algo que importa:
                // la venta NO depende del inventario para completarse.
                org.mockito.Mockito.mock(RegistroDeIntencionDeInventario.class), null);
    }
    @Test
    void getAllOrdersCallsProductServiceOncePerDistinctProductId() {
        Order order = new Order();
        order.setIdOrder(99L);
        order.setPagerColor("AZUL");
        order.setPagerNumber("3");
        order.setStatus(OrderStatus.pagado);
        order.setPaymentMethod("CASH");
        order.setCreatedAt(LocalDateTime.now());
        order.setSubtotal(BigDecimal.valueOf(10000));
        order.setTotal(BigDecimal.valueOf(10000));
        OrderItem item1 = new OrderItem();
        item1.setOrder(order);
        item1.setProductId("101");
        item1.setQuantity(1);
        item1.setUnitPrice(BigDecimal.valueOf(5000));
        item1.setTotalPrice(BigDecimal.valueOf(5000));
        OrderItem item2 = new OrderItem();
        item2.setOrder(order);
        item2.setProductId("101");
        item2.setQuantity(1);
        item2.setUnitPrice(BigDecimal.valueOf(5000));
        item2.setTotalPrice(BigDecimal.valueOf(5000));
        order.setItems(List.of(item1, item2));
        when(orderRepositoryPort.findAllWithItems()).thenReturn(List.of(order));
        Map<String, ProductResponse> products = new LinkedHashMap<>();
        products.put("101", new ProductResponse("101", "Pizza", "Food"));
        when(productCatalogPort.findProductsByIds(any())).thenReturn(products);
        List<OrderResponseRecord> response = orderHandler.getAllOrders();
        assertEquals(1, response.size());
        assertEquals("Pizza", response.get(0).items().get(0).nameProduct());
        assertEquals("Pizza", response.get(0).items().get(1).nameProduct());
        verify(productCatalogPort, times(1)).findProductsByIds(any());
    }
    @Test
    void createOrUpdateOrderSavesOrderWithCalculatedSubtotalAndTotal() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL",
                "10",
                List.of(
                        new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null),
                        new OrderItemRequestRecord("102", 2, BigDecimal.valueOf(2000), null, null)),
                null,
                "CASH", null, null, null, null, null);
        when(orderRepositoryPort.findOccupiedPagerOrder(
                "AZUL", "10", OrderStatus.pagado)).thenReturn(Optional.empty());
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order toSave = invocation.getArgument(0);
            toSave.setIdOrder(501L);
            return toSave;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(501L));
        when(syncOutboxRepositoryPort.save(any(SyncOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Order created = orderHandler.createOrUpdateOrder(request);
        assertEquals(BigDecimal.valueOf(9000), created.getSubtotal());
        assertEquals(BigDecimal.valueOf(9000), created.getTotal());
        assertEquals(2, created.getItems().size());
        verify(orderRepositoryPort, times(1)).save(any(Order.class));
        verify(syncOutboxRepositoryPort, times(1)).save(any(SyncOutbox.class));
    }

    // --- N2/D1 y N2/D2 ------------------------------------------------------

    /**
     * Regresión del historial duplicado: el POS empujaba la misma venta dos veces
     * (checkout + SyncScheduler drenando el mismo evento del outbox) y el servidor
     * creaba DOS órdenes con folios distintos. Con la clave de idempotencia, el
     * segundo POST devuelve la orden que ya existe y NO inserta nada.
     */
    @Test
    void createOrUpdateOrderConClaveRepetidaDevuelveLaExistenteYNoInserta() {
        Order previa = new Order();
        previa.setIdOrder(301871L);
        previa.setIdempotencyKey("clave-repetida");
        when(orderRepositoryPort.findByIdempotencyKey("clave-repetida"))
                .thenReturn(Optional.of(previa));

        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL", "10",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, "clave-repetida", null, null, null);

        Order resultado = orderHandler.createOrUpdateOrder(request);

        assertEquals(301871L, resultado.getIdOrder());
        verify(orderRepositoryPort, never()).save(any(Order.class));
        // El dedupe corre ANTES de validar el pager: en un reintento el pager ya
        // está ocupado por la primera orden y si no, respondería 400.
        verify(orderRepositoryPort, never())
                .findOccupiedPagerOrder(any(), any(), any());
    }

    /**
     * N2 — REGRESIÓN del 409 en cadena de la app de meseros.
     *
     * La app manda SIEMPRE el mismo rastreador (lo trae quemado), así que la
     * primera orden lo ocupaba y TODAS las siguientes morían con 409 "ya está
     * en uso". Un mesero lleva el pedido a la mesa: no entrega con rastreador,
     * así que su camino no valida disponibilidad.
     */
    @Test
    void lasOrdenesDeMeseroNoValidanDisponibilidadDelRastreador() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "Azul", "1",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, null, true, null, null);
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order toSave = invocation.getArgument(0);
            toSave.setIdOrder(507L);
            return toSave;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(507L));
        when(syncOutboxRepositoryPort.save(any(SyncOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        orderHandler.createOrUpdateOrder(request);

        // Ni siquiera se consulta si el rastreador está ocupado.
        verify(orderRepositoryPort, never()).findOccupiedPagerOrder(any(), any(), any());
    }

    /**
     * N2 — REGRESIÓN del bug que impedía enviar comandas desde la app de
     * meseros: la app manda el medio de pago en español ("Efectivo",
     * "Tarjeta"...) y el backend solo aceptaba los códigos canónicos, así que
     * TODO pedido que no fuera QR moría con 400.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            // "Nequi, QR" salió de aquí: desde la retirada de N2/6.6 se
            // RECHAZA en vez de normalizarse. Ver nequiSeRechazaConMensajePropio.
            "Efectivo, CASH", "EFECTIVO, CASH", "Tarjeta, CARD", "Datafono, CARD",
            "QR, QR", "CASH, CASH"})
    void aceptaLasEtiquetasEnEspanolQueMandaLaAppDeMeseros(String enviado, String esperado) {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL", "16",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, enviado, null, null, null, null, null);
        when(orderRepositoryPort.findOccupiedPagerOrder("AZUL", "16", OrderStatus.pagado))
                .thenReturn(Optional.empty());
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order toSave = invocation.getArgument(0);
            toSave.setIdOrder(506L);
            return toSave;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(506L));
        when(syncOutboxRepositoryPort.save(any(SyncOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Order created = orderHandler.createOrUpdateOrder(request);

        assertEquals(esperado, created.getPaymentMethod());
    }

    /**
     * 🔴 Un split de multipago con NEQUI también se rechaza.
     *
     * <p>Antes se persistía normalizado a QR. Con la retirada, `NEQUI` deja de
     * estar en `SPLIT_METHODS` y el pago mixto entero se rechaza — que es lo
     * coherente: si el medio no existe, no existe ni suelto ni dentro de un
     * reparto.
     */
    @Test
    void multipagoConSplitNequiSeRechaza() {
        OrderRequestRecord request = new OrderRequestRecord(
                "AZUL", "14",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(10000), null, null)),
                null, "MIXED",
                List.of(new OrderRequestRecord.PaymentSplitRecord("CASH", BigDecimal.valueOf(4000)),
                        new OrderRequestRecord.PaymentSplitRecord("NEQUI", BigDecimal.valueOf(6000))),
                null, null, null, null, null, null, null, null, null, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> orderHandler.createOrUpdateOrder(request));
        verify(orderRepositoryPort, org.mockito.Mockito.never()).save(any(Order.class));
    }

    /**
     * El multipago que no cuadra debe RECHAZARSE. Es la defensa contra que una
     * venta quede registrada por un monto distinto al que entró a la caja.
     */
    @Test
    void multipagoQueNoSumaElTotalSeRechaza() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL", "15",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(10000), null, null)),
                null, "MIXED",
                List.of(new OrderRequestRecord.PaymentSplitRecord("CASH", BigDecimal.valueOf(4000)),
                        new OrderRequestRecord.PaymentSplitRecord("CARD", BigDecimal.valueOf(5000))),
                null, null, null, null);
        when(orderRepositoryPort.findOccupiedPagerOrder("AZUL", "15", OrderStatus.pagado))
                .thenReturn(Optional.empty());
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order toSave = invocation.getArgument(0);
            toSave.setIdOrder(505L);
            return toSave;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(505L));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> orderHandler.createOrUpdateOrder(request));
    }

    /**
     * N2/6.6 — Nequi se eliminó, pero hay APKs viejos en campo que todavía lo
     * mandan. Rechazarlos con 400 tumbaría ventas en dispositivos que no
     * controlamos, así que se normaliza a QR en vez de fallar.
     */
    /**
     * 🔴 NEQUI se RECHAZA. Este test decía lo contrario.
     *
     * <p>Se normalizaba a QR "porque hay APKs viejos en campo y rechazarlos
     * tumbaría ventas en dispositivos que no controlamos". Ese argumento
     * caducó: la última orden con NEQUI en Producción es del <b>2026-07-23</b>,
     * ninguna interfaz lo ofrece desde N2/6.6, y la ronda presencial deja a
     * todos los dispositivos al día.
     *
     * <p>Y normalizar en silencio tenía un coste que no se veía: la venta
     * quedaba como QR sin que nadie supiera que el cliente había mandado otra
     * cosa, así que un APK viejo podía seguir en campo indefinidamente sin que
     * ninguna señal lo delatara.
     */
    @Test
    void nequiSeRechazaConMensajePropio() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL", "12",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "NEQUI", null, null, null, null, null);

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> orderHandler.createOrUpdateOrder(request));

        // El mensaje importa: quien lo reciba tiene que saber QUÉ pasa, no
        // creer que escribió mal un medio de pago.
        assertTrue(e.getMessage().contains("Nequi ya no es un medio de pago"),
                "el mensaje no dice qué pasa: " + e.getMessage());
        verify(orderRepositoryPort, org.mockito.Mockito.never()).save(any(Order.class));
    }

    /**
     * En el perfil `cloud` (sync.cloud.enabled=false, el default del test) este
     * backend ES la nube: no hay SyncOutboxScheduler que marque después, así que
     * la orden nace ya sincronizada. Antes quedaba en false para siempre y el
     * historial la mostraba como "No sincronizada" aunque estuviera en la BD.
     */
    @Test
    void createOrUpdateOrderMarcaLaOrdenComoSincronizadaEnPerfilCloud() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AZUL", "11",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, "clave-nueva", null, null, null);
        when(orderRepositoryPort.findByIdempotencyKey("clave-nueva")).thenReturn(Optional.empty());
        when(orderRepositoryPort.findOccupiedPagerOrder("AZUL", "11", OrderStatus.pagado))
                .thenReturn(Optional.empty());
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(invocation -> {
            Order toSave = invocation.getArgument(0);
            toSave.setIdOrder(502L);
            return toSave;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(502L));
        when(syncOutboxRepositoryPort.save(any(SyncOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Order created = orderHandler.createOrUpdateOrder(request);

        assertEquals(Boolean.TRUE, created.getSynced());
        assertEquals("clave-nueva", created.getIdempotencyKey());
    }

    // ------------------------------------------------------------------
    // N3/#2 — Una ronda nueva devuelve la comanda a cocina
    // ------------------------------------------------------------------

    @Test
    void rondaNuevaSobreMesaYaEntregadaReabreLaComandaEnCocina() {
        java.util.UUID cuenta = java.util.UUID.randomUUID();
        java.util.UUID uuidOrden = java.util.UUID.randomUUID();

        Order abierta = new Order();
        abierta.setIdOrder(555L);
        abierta.setUuidId(uuidOrden);
        abierta.setStatus(OrderStatus.abierta);
        abierta.setTableSessionId(cuenta);
        abierta.setSubtotal(BigDecimal.valueOf(5000));
        abierta.setTotal(BigDecimal.valueOf(5000));

        when(orderRepositoryPort.findByTableSessionId(cuenta)).thenReturn(List.of(abierta));
        OrderItem yaHabia = new OrderItem();
        yaHabia.setTotalPrice(BigDecimal.valueOf(5000));
        OrderItem nuevo = new OrderItem();
        nuevo.setTotalPrice(BigDecimal.valueOf(5000));
        when(orderItemRepositoryPort.findByOrderIds(List.of(555L))).thenReturn(List.of(yaHabia, nuevo));

        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AMARILLO", "1",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, null, null, cuenta.toString(), null);

        orderHandler.createOrUpdateOrder(request);

        // Sin esto la orden queda `delivered=true` desde que cocina le dio
        // "listo" y los platos de la ronda nueva no vuelven a aparecer nunca.
        verify(orderDeliveryTrackingRepositoryPort).reabrirParaCocina(uuidOrden);
        // Y NO se crea una orden aparte: la ronda se suma a la cuenta.
        verify(orderRepositoryPort, never()).save(any(Order.class));
        verify(orderRepositoryPort).actualizarTotales(555L, BigDecimal.valueOf(10000), BigDecimal.valueOf(10000));
    }

    // --- Comanda impresa: la orden se registra pero NO va a cocina ------------

    /**
     * Cuando el POS trabaja sin internet, el cajero imprime la comanda en papel y
     * la cocina prepara el pedido con ese papel. Al volver la conexión la orden se
     * sincroniza — la venta tiene que quedar registrada — pero mandarla a cocina
     * duplicaría el plato, y dejaría el rastreador ocupado sin que nadie pueda
     * liberarlo, porque la cocina nunca vio esa orden en pantalla.
     */
    @Test
    void ordenPreparadaEnComandaNaceEntregadaYFueraDeLaColaDeCocina() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AMARILLO", "7",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, null, null, null, true);
        prepararGuardado();

        Order creada = orderHandler.createOrUpdateOrder(request);

        // `isPrinted` la saca de la cola de cocina
        // (findActiveKitchenOrders filtra por isPrinted = false).
        assertEquals(Boolean.TRUE, creada.getIsPrinted());

        // `delivered` libera el rastreador: un pager esta libre cuando
        // delivered = true O pager_returned = true.
        ArgumentCaptor<OrderDeliveryTracking> captor =
                ArgumentCaptor.forClass(OrderDeliveryTracking.class);
        verify(orderDeliveryTrackingRepositoryPort).save(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getDelivered());
    }

    /** Una venta normal SI tiene que entrar a la cola de cocina. */
    @Test
    void ordenNormalEntraALaColaDeCocinaYOcupaElRastreador() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AMARILLO", "8",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, null, null, null, false);
        prepararGuardado();

        Order creada = orderHandler.createOrUpdateOrder(request);

        assertNotEquals(Boolean.TRUE, creada.getIsPrinted());

        ArgumentCaptor<OrderDeliveryTracking> captor =
                ArgumentCaptor.forClass(OrderDeliveryTracking.class);
        verify(orderDeliveryTrackingRepositoryPort).save(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getDelivered());
    }

    /** Sin el campo (cliente viejo) se comporta como una venta normal. */
    @Test
    void sinElCampoSeComportaComoVentaNormal() {
        OrderRequestRecord request = OrderRequestRecord.sinProcedencia(
                "AMARILLO", "9",
                List.of(new OrderItemRequestRecord("101", 1, BigDecimal.valueOf(5000), null, null)),
                null, "CASH", null, null, null, null, null);
        prepararGuardado();

        orderHandler.createOrUpdateOrder(request);

        ArgumentCaptor<OrderDeliveryTracking> captor =
                ArgumentCaptor.forClass(OrderDeliveryTracking.class);
        verify(orderDeliveryTrackingRepositoryPort).save(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getDelivered());
    }

    private void prepararGuardado() {
        when(orderRepositoryPort.findOccupiedPagerOrder(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(orderRepositoryPort.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setIdOrder(777L);
            return o;
        });
        when(orderRepositoryPort.findNumericIdByUuid(any(java.util.UUID.class)))
                .thenReturn(Optional.of(777L));
        when(syncOutboxRepositoryPort.save(any(SyncOutbox.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

}
