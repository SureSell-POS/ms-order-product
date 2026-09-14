package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.suresell.orders.domain.model.FuenteQr;
import com.suresell.orders.domain.model.ResultadoQr;
import com.suresell.orders.infrastructure.web.TokenDeLaPeticion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * La conciliación del QR del cierre de caja: qué monto se usa y, sobre todo, qué
 * queda registrado sobre su procedencia.
 *
 * <p>Estos tests existen por un incidente concreto: entre el 2026-07-30 y el
 * 2026-08-20 el cierre se cuadró con el valor manual del cajero porque
 * `ms-core-app` devolvía 401 y el llamador se tragaba la excepción. En la base no
 * quedaba ninguna diferencia entre un cierre conciliado y uno degradado.
 *
 * <p>Por eso casi todos los casos comprueban dos cosas distintas: que el cierre
 * <b>se completa</b> (la operación del local no se puede romper) y que el dato
 * queda <b>marcado</b> con lo que de verdad pasó.
 */
class ConciliadorDeQrTest {

    private static final String URL_CORE = "http://core-de-prueba/api/core";
    private static final LocalDate FECHA = LocalDate.of(2026, 8, 20);
    private static final BigDecimal VALOR_DEL_CAJERO = new BigDecimal("150000");
    /** Suma de ventas del dia por QR. El unico de los tres que existe siempre. */
    private static final BigDecimal QR_DEL_POS = new BigDecimal("148000");

    private RestTemplate restTemplate;
    private MockRestServiceServer servidor;
    private TokenDeLaPeticion token;

    @BeforeEach
    void preparar() {
        restTemplate = new RestTemplate();
        servidor = MockRestServiceServer.createServer(restTemplate);
        token = Mockito.mock(TokenDeLaPeticion.class);
        Mockito.when(token.cabeceraAuthorization()).thenReturn(Optional.of("Bearer token-del-cajero"));
    }

    private ConciliadorDeQr conciliador() {
        ConciliadorDeQr c = new ConciliadorDeQr(token, restTemplate);
        c.fijarUrlDeCore(URL_CORE);
        return c;
    }

    private String urlEsperada() {
        return URL_CORE + "/qr-payments/by-date?date=" + FECHA;
    }

    // =====================================================================
    @Nested
    @DisplayName("Precedencia: la cuenta del cajero manda")
    class Conciliacion {

        @Test
        @DisplayName("🔴 core reporta MÁS que el cajero: el total sigue siendo el del cajero")
        void elConciliadoNoSustituyeAlCajero() {
            // El caso que el arreglo viene a cerrar. Antes core mandaba con solo
            // ser distinto de cero, así que el total del cierre dejaba de ser lo
            // que el cajero contó y aparecía un faltante inventado.
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"amount\": 275000, \"paymentDate\": \"2026-08-20\"}",
                            MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.monto())
                    .as("el total del cierre es SIEMPRE la cuenta del cajero")
                    .isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.fuente()).isEqualTo(FuenteQr.manual_cajero);
            assertThat(r.confianza()).isEqualTo(ResultadoQr.CONFIANZA_SIN_CONCILIAR);
            assertThat(r.confianza()).isEqualTo((short) 0);
            assertThat(r.detalle()).isNull();
            // El conciliado NO se pierde: pierde la autoridad, no la existencia.
            assertThat(r.qrConciliado())
                    .as("lo que dijo core se sigue guardando como información de control")
                    .isEqualByComparingTo("275000");
            assertThat(r.qrManual()).isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.qrPos()).isEqualByComparingTo(QR_DEL_POS);
            servidor.verify();
        }

        @Test
        @DisplayName("🔴 el caso PARCIAL: core reporta menos, y no aparece un faltante que no existe")
        void elRegistroParcialNoInventaUnFaltante() {
            // El escenario concreto del encargo: alguien registra $200.000 de un
            // día de $600.000 reales. Con la precedencia vieja el cierre mostraba
            // un faltante de $400.000. Lo que hace este test distinto del
            // anterior es que aquí se comprueba el NÚMERO de la diferencia, no
            // solo la fuente: es dinero, y un faltante inventado se reclama a un
            // cajero.
            BigDecimal contadoPorElCajero = new BigDecimal("600000");
            BigDecimal registradoEnCore = new BigDecimal("200000");
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"amount\": 200000}", MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, contadoPorElCajero, contadoPorElCajero);

            assertThat(r.monto()).isEqualByComparingTo(contadoPorElCajero);
            assertThat(r.monto().subtract(contadoPorElCajero))
                    .as("faltante aparente contra lo que el POS vendió por QR")
                    .isEqualByComparingTo("0");
            assertThat(r.fuente()).isEqualTo(FuenteQr.manual_cajero);
            assertThat(r.qrConciliado()).isEqualByComparingTo(registradoEnCore);
        }

        @Test
        @DisplayName("core dice 0 pero el cajero contó dinero: manda el cajero")
        void ceroDelExternoConDineroContado() {
            // Este caso ya estaba cubierto por la regla dura. Ahora lo cubre la
            // regla general, y por eso deja de ser un caso especial: lo único
            // que cambia respecto a antes es la etiqueta —`manual_cajero` en vez
            // de `sin_registro_externo`—, porque el total salió del teclado del
            // cajero en los dos casos y eso es lo que hay que poder leer.
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"amount\": 0}", MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.monto())
                    .as("el total usa el del cajero, JAMAS el cero del externo")
                    .isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.fuente()).isEqualTo(FuenteQr.manual_cajero);
            assertThat(r.confianza()).isEqualTo((short) 0);
            // Y los tres hechos quedan guardados, ninguno destruido.
            assertThat(r.qrConciliado()).isEqualByComparingTo("0");
            assertThat(r.qrManual()).isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.qrPos()).isEqualByComparingTo(QR_DEL_POS);
        }

        @Test
        @DisplayName("los dos en cero: eso sí es una conciliación, y ahí sí confianza 2")
        void ceroDelExternoSinDineroContado() {
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"amount\": 0}", MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, BigDecimal.ZERO, QR_DEL_POS);

            // Dos fuentes coinciden en que no hubo QR: eso es una conciliacion.
            assertThat(r.fuente()).isEqualTo(FuenteQr.conciliado_core);
            assertThat(r.confianza()).isEqualTo(ResultadoQr.CONFIANZA_CONCILIADO);
            assertThat(r.monto()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("sin cuenta del cajero, el conciliado sí manda: no hay nada a lo que sobreponerse")
        void sinCuentaDelCajeroElConciliadoSigueValiendo() {
            // El límite de la regla. "El conciliado no manda nunca" significa
            // "no sustituye a la cuenta del cajero". Si el cajero no contó, el
            // registro externo es la mejor fuente que hay y descartarlo sería
            // perder dinero del cierre, que es el mismo error al revés.
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"amount\": 275000}", MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, BigDecimal.ZERO, QR_DEL_POS);

            assertThat(r.monto()).isEqualByComparingTo("275000");
            assertThat(r.fuente()).isEqualTo(FuenteQr.conciliado_core);
            assertThat(r.confianza()).isEqualTo(ResultadoQr.CONFIANZA_CONCILIADO);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Sin pagos QR en el día (404)")
    class SinPagosRegistrados {

        /**
         * `ms-core-app` responde 404 cuando no hay registro
         * (`QrPaymentController.java:35-37`). Es la respuesta correcta a "¿hay
         * algo?" cuando no hay nada, no un fallo de integración.
         */
        @Test
        @DisplayName("usa el valor del cajero y lo marca manual_cajero, NO fallo_integracion")
        void sinRegistroEsManualNoFallo() {
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.monto()).isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.fuente()).isEqualTo(FuenteQr.manual_cajero);
            assertThat(r.confianza()).isEqualTo(ResultadoQr.CONFIANZA_SIN_CONCILIAR);
            // No hubo fallo que explicar.
            assertThat(r.detalle()).isNull();
        }

        @Test
        @DisplayName("si el cajero tampoco puso nada, el monto es cero pero la fuente sigue siendo manual")
        void cajeroSinValor() {
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            ResultadoQr r = conciliador().resolver(FECHA, null, QR_DEL_POS);

            // Sin valor del cajero, el del POS es mejor que cero: sale de las
            // ventas mismas. Y queda dicho que vino de ahi.
            assertThat(r.monto()).isEqualByComparingTo(QR_DEL_POS);
            assertThat(r.fuente()).isEqualTo(FuenteQr.pos);
        }

        @Test
        @DisplayName("sin cajero y sin ventas por QR: cero honesto, no conciliacion inventada")
        void niCajeroNiPos() {
            servidor.expect(requestTo(urlEsperada())).andRespond(withStatus(HttpStatus.NOT_FOUND));

            ResultadoQr r = conciliador().resolver(FECHA, null, BigDecimal.ZERO);

            assertThat(r.monto()).isEqualByComparingTo("0");
            assertThat(r.fuente()).isEqualTo(FuenteQr.manual_cajero);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Fallos de integración")
    class Fallos {

        @Test
        @DisplayName("401: el cierre se completa, queda marcado y el detalle dice el código real")
        void error401() {
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            // El cierre puede seguir: no se rompe la operación del local.
            assertThat(r.monto()).isEqualByComparingTo(VALOR_DEL_CAJERO);
            assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
            assertThat(r.confianza()).isEqualTo((short) 0);
            // Y el motivo REAL, no "posible falta de internet".
            assertThat(r.detalle()).contains("401");
            assertThat(r.detalle()).doesNotContain("internet");
        }

        @Test
        @DisplayName("500 también es fallo_integracion")
        void error500() {
            servidor.expect(requestTo(urlEsperada())).andRespond(withServerError());

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
            assertThat(r.detalle()).contains("500");
        }

        @Test
        @DisplayName("un 200 sin el campo 'amount' no se toma por bueno")
        void respuestaIlegible() {
            servidor.expect(requestTo(urlEsperada()))
                    .andRespond(withSuccess("{\"otraCosa\": 1}", MediaType.APPLICATION_JSON));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
            assertThat(r.detalle()).contains("amount");
            assertThat(r.monto()).isEqualByComparingTo(VALOR_DEL_CAJERO);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Propagación del JWT")
    class Jwt {

        @Test
        @DisplayName("la petición saliente lleva la cabecera Authorization de la petición en curso")
        void propagaElToken() {
            servidor.expect(requestTo(urlEsperada()))
                    .andExpect(method(org.springframework.http.HttpMethod.GET))
                    .andExpect(header("Authorization", "Bearer token-del-cajero"))
                    .andRespond(withSuccess("{\"amount\": 100}", MediaType.APPLICATION_JSON));

            conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            // Si la cabecera no viaja, verify() falla. Este es EL test que
            // impide que la regresión del 2026-07-30 vuelva a pasar.
            servidor.verify();
        }

        @Test
        @DisplayName("sin token no se inventa uno: la llamada sale sin cabecera y el fallo queda registrado")
        void sinTokenNoInventaNada() {
            Mockito.when(token.cabeceraAuthorization()).thenReturn(Optional.empty());
            servidor.expect(requestTo(urlEsperada()))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            ResultadoQr r = conciliador().resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            servidor.verify();
            assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
            assertThat(r.detalle()).contains("401");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Timeouts")
    class Timeouts {

        /**
         * No se mide el tiempo real —un test que duerme 5 s es un test que nadie
         * corre—. Se comprueba que la configuración existe y es acotada, que es
         * lo que impide que un `ms-core-app` lento cuelgue el cierre.
         */
        @Test
        @DisplayName("hay timeout de conexión y de lectura, y son cortos")
        void hayTimeoutsAcotados() {
            assertThat(ConciliadorDeQr.TIMEOUT_CONEXION).isPositive();
            assertThat(ConciliadorDeQr.TIMEOUT_LECTURA).isPositive();
            assertThat(ConciliadorDeQr.TIMEOUT_CONEXION.plus(ConciliadorDeQr.TIMEOUT_LECTURA))
                    .as("el cierre no puede quedarse esperando a ms-core-app mas de 10 s")
                    .isLessThanOrEqualTo(java.time.Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("cuando la conexión falla, el resultado es fallo_integracion con el motivo técnico")
        void fallaDeConexion() {
            // Puerto cerrado: reproduce el fallo de red sin esperar al timeout.
            RestTemplate real = new RestTemplate();
            ConciliadorDeQr c = new ConciliadorDeQr(token, real);
            c.fijarUrlDeCore("http://127.0.0.1:1/api/core");

            ResultadoQr r = c.resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);

            assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
            assertThat(r.confianza()).isEqualTo((short) 0);
            assertThat(r.detalle()).isNotBlank();
            assertThat(r.monto()).isEqualByComparingTo(VALOR_DEL_CAJERO);
        }
    }

    // =====================================================================
    @Test
    @DisplayName("🔴 sin SYNC_CLOUD_CORE_URL: no llama a nadie, el cierre se completa y queda fallo_integracion con el motivo")
    void sinUrlDeCore() {
        ConciliadorDeQr c = new ConciliadorDeQr(token, restTemplate);
        c.fijarUrlDeCore("");
        c.avisarSiFaltaLaUrl();   // solo registra: no lanza
        ResultadoQr r = c.resolver(FECHA, VALOR_DEL_CAJERO, QR_DEL_POS);
        servidor.verify();        // ninguna petición esperada, ninguna hecha
        assertThat(r.fuente()).isEqualTo(FuenteQr.fallo_integracion);
        assertThat(r.monto()).isEqualByComparingTo(VALOR_DEL_CAJERO);
        assertThat(r.detalle()).contains("SYNC_CLOUD_CORE_URL");
    }
}
