package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.application.dto.dto.CashCountDetail;
import com.suresell.orders.application.dto.request.ExecuteClosureRequest;
import com.suresell.orders.domain.model.DailyClosure;
import com.suresell.orders.domain.model.ResultadoQr;
import com.suresell.orders.domain.port.out.SyncOutboxRepositoryPort;
import com.suresell.orders.domain.service.CashflowCalculator;
import com.suresell.orders.infrastructure.persistence.DailyClosureRepository;
import com.suresell.orders.infrastructure.persistence.OrderPaymentRepository;
import com.suresell.orders.infrastructure.persistence.OrderRepository;
import com.suresell.orders.shared.ZonaHoraria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * De cuándo a cuándo cuadra el cierre de caja, y qué se niega a guardar.
 *
 * <p>Estos tests existen por dos incidentes reales de shark-burger, y cada uno
 * fija el comportamiento que los habría evitado:
 *
 * <ol>
 *   <li><b>Viernes 2026-08-28.</b> No se cerró caja. El cierre siguiente cuadró
 *       contra una ventana que arrancaba a medianoche de SU día, así que las
 *       ventas del viernes —$1.920.600— no entraron en ningún cierre y salió un
 *       sobrante falso por el conteo completo del cajero.
 *   <li><b>Lunes 2026-08-31, 09:30.</b> Alguien envió el formulario del cierre
 *       vacío, antes de la primera venta del día (10:04). Se guardó, quemó el
 *       único cierre que admite el día y dejó la base del día siguiente en 0.
 * </ol>
 *
 * <p>La causa del primero no era la lógica de la ventana, que estaba escrita con
 * la intención correcta: era que el POS mandaba {@code sellerId: 'Angie'}
 * mientras los cierres se guardaban con {@code user_name = 'Cajero 1'}. Medido
 * el 2026-08-31 en producción: <b>103 de 104 cierres abrieron a las 00:00:00</b>
 * y ninguna fila tenía 'Angie'. Por eso el último test de aquí comprueba que el
 * {@code sellerId} ya no influye en NADA — para que no vuelva a haber un
 * literal del cliente decidiendo qué ventas entran en el cuadre.
 */
class VentanaDelCierreDeCajaTest {

    private OrderRepository orderRepository;
    private DailyClosureRepository closureRepository;
    private SyncOutboxRepositoryPort outbox;
    private OrderPaymentRepository orderPaymentRepository;
    private TableSessionService tableSessionService;
    private ConciliadorDeQr conciliadorDeQr;
    private ExecuteDailyClosureUseCase useCase;

    /** Un conteo cualquiera que NO es cero: dos billetes de 50k. */
    private static final CashCountDetail CONTEO_REAL =
            new CashCountDetail(0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private static final CashCountDetail CONTEO_EN_CEROS =
            new CashCountDetail(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    @BeforeEach
    void preparar() {
        orderRepository = Mockito.mock(OrderRepository.class);
        closureRepository = Mockito.mock(DailyClosureRepository.class);
        outbox = Mockito.mock(SyncOutboxRepositoryPort.class);
        orderPaymentRepository = Mockito.mock(OrderPaymentRepository.class);
        tableSessionService = Mockito.mock(TableSessionService.class);
        conciliadorDeQr = Mockito.mock(ConciliadorDeQr.class);

        when(tableSessionService.pendientesDeCobro()).thenReturn(List.of());
        when(tableSessionService.ajustePorRedondeoEntre(any(), any())).thenReturn(BigDecimal.ZERO);
        when(orderRepository.sumTotalsByPaymentMethodAndSeller(any(), any())).thenReturn(List.of());
        when(orderPaymentRepository.sumSplitsByMethod(any(), any())).thenReturn(List.of());
        when(conciliadorDeQr.resolver(any(), any(), any()))
                .thenAnswer(inv -> ResultadoQr.manual(inv.getArgument(1), inv.getArgument(2)));

        // V55: sin base configurada en el negocio (lo de antes: base 0).
        SiteService siteService = Mockito.mock(SiteService.class);
        when(siteService.baseCajaConfigurada()).thenReturn(Optional.empty());

        useCase = new ExecuteDailyClosureUseCase(
                orderRepository, closureRepository, new CashflowCalculator(), new ObjectMapper(),
                outbox, Mockito.mock(DailyPaymentRecordService.class), orderPaymentRepository,
                tableSessionService, conciliadorDeQr, siteService);
    }

    private ExecuteClosureRequest peticion(CashCountDetail conteo, String sellerId) {
        return new ExecuteClosureRequest(conteo, null, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "notas", sellerId, List.of(), null);
    }

    /** El cierre anterior, con la hora a la que se cerró y la base que dejó. */
    private DailyClosure cierreAnterior(LocalDateTime cerroA, BigDecimal base) {
        DailyClosure anterior = new DailyClosure();
        anterior.setClosingTime(cerroA);
        anterior.setBaseBalanceForNextDay(base);
        return anterior;
    }

    /** El instante desde el que el caso de uso pidió las ventas. */
    private LocalDateTime ventanaUsada() {
        ArgumentCaptor<LocalDateTime> desde = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).sumTotalsByPaymentMethodAndSeller(desde.capture(), any());
        return desde.getValue();
    }

    // =====================================================================

    @Test
    @DisplayName("la ventana arranca en el cierre anterior, no a medianoche")
    void arrancaEnElCierreAnterior() {
        LocalDateTime cerroElViernes = LocalDateTime.of(2026, 8, 28, 17, 24);
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(cerroElViernes, new BigDecimal("200000"))));

        useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1");

        assertThat(ventanaUsada()).isEqualTo(cerroElViernes);
    }

    @Test
    @DisplayName("saltarse un día ya no deja esas ventas fuera de todo cierre")
    void elDiaSaltadoEntraEnElSiguienteCierre() {
        // El caso exacto del 2026-08-28: se cerró el viernes por la tarde, no se
        // cerró el sábado ni el domingo, y el lunes se cierra. La ventana tiene
        // que cubrir TODO lo que pasó desde el viernes, no solo el lunes.
        LocalDateTime cerroElViernes = LocalDateTime.of(2026, 8, 28, 17, 24);
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(cerroElViernes, BigDecimal.ZERO)));

        useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1");

        LocalDateTime desde = ventanaUsada();
        assertThat(desde).isEqualTo(cerroElViernes);
        assertThat(desde.toLocalDate())
                .as("una ventana que arranque el día del propio cierre deja fuera el día saltado")
                .isNotEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("sin ningún cierre previo, arranca a medianoche de hoy")
    void elPrimerCierreDeLaHistoria() {
        // Un negocio recién dado de alta no tiene cierre anterior. Ahí sí es
        // medianoche: es el único momento defendible para empezar a contar.
        when(closureRepository.findFirstByOrderByClosingTimeDesc()).thenReturn(Optional.empty());

        useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1");

        assertThat(ventanaUsada()).isEqualTo(ZonaHoraria.hoy().atStartOfDay());
    }

    @Test
    @DisplayName("la ventana y la base salen del MISMO cierre anterior")
    void laVentanaYLaBaseNoPuedenDiscrepar() {
        // Esta es la forma del defecto original: la base venía del último cierre
        // y la ventana de otra consulta distinta. Mientras salgan de la misma
        // fila no pueden volver a describir dos días diferentes.
        LocalDateTime cerroA = LocalDateTime.of(2026, 8, 30, 22, 16);
        BigDecimal baseQueDejo = new BigDecimal("300000");
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(cerroA, baseQueDejo)));

        useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1");

        ArgumentCaptor<DailyClosure> guardado = ArgumentCaptor.forClass(DailyClosure.class);
        verify(closureRepository).saveAndFlush(guardado.capture());

        assertThat(guardado.getValue().getOpeningTime()).isEqualTo(cerroA);
        // La base del cierre anterior se suma al efectivo esperado: 100.000 de
        // ventas no hay, así que el esperado es exactamente la base.
        assertThat(guardado.getValue().getTotalExpectedCash()).isEqualByComparingTo(baseQueDejo);
        // Y se consultó UNA sola vez: no hay dos fuentes que puedan divergir.
        verify(closureRepository, Mockito.times(1)).findFirstByOrderByClosingTimeDesc();
    }

    @Test
    @DisplayName("un cierre con el conteo entero en ceros se rechaza y no se guarda")
    void elFormularioVacioNoSeGuarda() {
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(
                        LocalDateTime.of(2026, 8, 30, 22, 16), new BigDecimal("200000"))));

        assertThatThrownBy(() -> useCase.execute(peticion(CONTEO_EN_CEROS, "Cajero 1"), "Cajero 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ceros");

        // Lo que de verdad importa no es la excepción: es que no quede la fila.
        // Hasta V55, con ella guardada el cierre real de la tarde ya no cabía
        // —el índice único era (negocio, fecha)—; hoy sigue dejando un turno
        // basura en el historial y la base del siguiente turno en 0.
        verify(closureRepository, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("el conteo en ceros se rechaza también con ventas del día por medio")
    void elFormularioVacioTampocoPasaPorLaTarde() {
        // Comprobar "no hubo ventas" en vez de "no se contó nada" dejaría pasar
        // el mismo formulario vacío enviado al final del día, que hace el mismo
        // daño: quema el cupo y pone la base en 0.
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(
                        LocalDateTime.of(2026, 8, 30, 22, 16), BigDecimal.ZERO)));
        when(orderRepository.sumTotalsByPaymentMethodAndSeller(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {"CASH", new BigDecimal("1425100")}));

        assertThatThrownBy(() -> useCase.execute(peticion(CONTEO_EN_CEROS, "Cajero 1"), "Cajero 1"))
                .isInstanceOf(IllegalStateException.class);

        verify(closureRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("el sellerId que manda el POS ya no decide la ventana")
    void elSellerIdYaNoInfluye() {
        // El literal 'Angie' vivía en el POS y no coincidía con ningún
        // `user_name` guardado. Mientras el cliente pueda cambiar la ventana
        // mandando un nombre, el cuadre depende de una cadena de texto.
        LocalDateTime cerroA = LocalDateTime.of(2026, 8, 30, 22, 16);
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(cerroA, BigDecimal.ZERO)));

        useCase.execute(peticion(CONTEO_REAL, "Angie"), "Cajero 1");
        assertThat(ventanaUsada())
                .as("un sellerId que no existe en ningún cierre daba 'desde medianoche'")
                .isEqualTo(cerroA);
    }

    @Test
    @DisplayName("V55: el turno es el último de hoy + 1, y la ventana sigue saliendo del cierre anterior aunque sea de hoy")
    void elTurnoEsElUltimoDeHoyMasUno() {
        // Tres turnos ya cerrados hoy; el anterior cerró hace un rato, HOY.
        LocalDateTime cerroHoy = ZonaHoraria.ahora().minusMinutes(5);
        when(closureRepository.findFirstByOrderByClosingTimeDesc())
                .thenReturn(Optional.of(cierreAnterior(cerroHoy, new BigDecimal("120000"))));
        when(closureRepository.ultimoTurnoDelDia(any())).thenReturn(3);

        var respuesta = useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1");

        ArgumentCaptor<DailyClosure> guardado = ArgumentCaptor.forClass(DailyClosure.class);
        verify(closureRepository).saveAndFlush(guardado.capture());
        assertThat(guardado.getValue().getTurno()).isEqualTo(4);
        assertThat(respuesta.turno()).isEqualTo(4);
        assertThat(ventanaUsada()).isEqualTo(cerroHoy);
    }

    @Test
    @DisplayName("V55: si otra terminal cerró ese mismo turno a la vez, sale TurnoYaCerradoException (409), no un 500")
    void elChoqueDeTurnoEsUnConflictoLegible() {
        when(closureRepository.findFirstByOrderByClosingTimeDesc()).thenReturn(Optional.empty());
        when(closureRepository.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint "
                        + "\"uq_daily_closures_tenant_date_turno\"")));

        assertThatThrownBy(() -> useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1"))
                .isInstanceOf(com.suresell.orders.shared.exception.TurnoYaCerradoException.class)
                .hasMessage("Ese turno ya se cerró; recarga para ver el turno actual");
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("V55: el 409 del turno lleva el texto del contrato en `error` y no lleva `alreadyClosed`")
    void elCuerpoDelChoqueDeTurno() {
        var r = new com.suresell.orders.shared.exception.GlobalExceptionHandler()
                .handleTurnoYaCerrado(new com.suresell.orders.shared.exception.TurnoYaCerradoException());
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody())
                .containsEntry("error", "Ese turno ya se cerró; recarga para ver el turno actual")
                .containsEntry("mensaje", "Ese turno ya se cerró; recarga para ver el turno actual")
                .containsEntry("codigo", "TURNO_YA_CERRADO")
                .doesNotContainKey("alreadyClosed");
    }

    @Test
    @DisplayName("V55: una base negativa es un 400 con campo, y no se guarda nada")
    void laBaseNegativaNoPasa() {
        when(closureRepository.findFirstByOrderByClosingTimeDesc()).thenReturn(Optional.empty());
        ExecuteClosureRequest conBaseNegativa = new ExecuteClosureRequest(CONTEO_REAL, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "notas", "Cajero 1", List.of(), new BigDecimal("-1"));

        assertThatThrownBy(() -> useCase.execute(conBaseNegativa, "Cajero 1"))
                .isInstanceOf(CodigosDeProducto.CampoInvalido.class)
                .satisfies(e -> assertThat(((CodigosDeProducto.CampoInvalido) e).campo()).isEqualTo("baseForNextDay"));
        verify(closureRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("no se puede cerrar con mesas sin cobrar, y sigue sin poderse")
    void lasMesasAbiertasSiguenBloqueando() {
        // Red de seguridad: la guarda nueva se insertó ANTES de esta, así que
        // hay que fijar que no se la ha comido — un conteo en ceros con mesas
        // abiertas tiene que seguir avisando de las mesas, que es lo accionable.
        var mesa = Mockito.mock(com.suresell.orders.domain.model.TableSession.class);
        when(mesa.getTableId()).thenReturn(7L);
        when(tableSessionService.pendientesDeCobro()).thenReturn(List.of(mesa));

        assertThatThrownBy(() -> useCase.execute(peticion(CONTEO_REAL, "Cajero 1"), "Cajero 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mesa");
    }
}
