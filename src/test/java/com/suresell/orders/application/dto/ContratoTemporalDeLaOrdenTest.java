package com.suresell.orders.application.dto;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.suresell.orders.application.usecase.CorduraDelRelojDelDispositivo;
import com.suresell.orders.application.usecase.CorduraDelRelojDelDispositivo.Veredicto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El contrato de creación de orden acepta la procedencia temporal SIN romper a
 * los clientes viejos.
 *
 * <h3>Por qué este archivo importa más de lo que parece</h3>
 *
 * Aquí se cruzan las dos cosas que pueden romper producción en esta fase:
 *
 * <ol>
 *   <li><b>Compatibilidad hacia atrás.</b> Hay terminales que pueden llevar
 *       semanas sin actualizar. Si un campo nuevo fuera obligatorio, dejarían de
 *       vender el día del despliegue.</li>
 *   <li><b>{@code FAIL_ON_UNKNOWN_PROPERTIES}.</b> Al activarlo apareció que el
 *       POS manda ocho campos que el DTO no declara. Sin tratarlos, la
 *       validación estricta devolvería 400 en TODAS las ventas.</li>
 * </ol>
 *
 * <p>Se usa un {@link ObjectMapper} configurado igual que el de la aplicación
 * ({@code application.yml}, {@code fail-on-unknown-properties: true}) para que
 * lo que se prueba aquí sea lo que pasa en producción.
 */
class ContratoTemporalDeLaOrdenTest {

    private ObjectMapper mapper;

    @BeforeEach
    void preparar() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * El payload EXACTO que manda hoy el POS: la orden completa de
     * {@code StoredOrder} ({@code db.ts:20-47}), tal cual la mete
     * {@code offline-order.repository.ts:25} en el evento del outbox.
     */
    private static final String PAYLOAD_DEL_POS_ACTUAL = """
            {
              "idempotencyKey": "0f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10",
              "tenantId": "shark-burger",
              "idOrder": null,
              "createdAt": "2026-08-20T18:05:11.000Z",
              "paymentMethod": "CASH",
              "subtotal": 25000,
              "total": 25000,
              "discountCode": null,
              "discountAmount": 0,
              "pagerColor": "AMARILLO",
              "pagerNumber": "7",
              "items": [],
              "payments": null,
              "tableSessionId": null,
              "preparadoEnComanda": false,
              "status": "pending",
              "synced": false
            }
            """;

    // =====================================================================
    @Nested
    @DisplayName("Compatibilidad con el POS actual")
    class ClienteViejo {

        @Test
        @DisplayName("el payload que manda HOY el POS se deserializa sin romperse")
        void elPayloadActualNoRompe() throws Exception {
            // Si esto falla, desplegar el backend deja al negocio sin vender.
            OrderRequestRecord dto = mapper.readValue(PAYLOAD_DEL_POS_ACTUAL, OrderRequestRecord.class);

            assertThat(dto.pagerColor()).isEqualTo("AMARILLO");
            assertThat(dto.paymentMethod()).isEqualTo("CASH");
            assertThat(dto.idempotencyKey()).isEqualTo("0f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10");
        }

        @Test
        @DisplayName("🔴 un campo que el POS YA manda no puede tumbar la venta: facturaElectronica")
        void loQueElPosYaMandaNoTumbaLaVenta() throws Exception {
            // 2026-09-11: el POS empezo a mandar `facturaElectronica` al marcar
            // «Requiere factura electronica» y este DTO no lo declaraba. Con
            // FAIL_ON_UNKNOWN_PROPERTIES eso no es «se ignora»: es 400, la venta
            // no se crea, y el outbox del POS marca los 4xx como FAILED
            // definitivo — esa venta no se sincroniza nunca. Medido el
            // 2026-09-12 antes de arreglarlo.
            //
            // Este test es la guarda para que no vuelva a pasar con el
            // siguiente campo: lo que el POS manda hoy se declara aqui.
            String conFactura = PAYLOAD_DEL_POS_ACTUAL.strip().replaceFirst("\\}\\s*$", """
                    ,"facturaElectronica":{"nombre":"Distribuciones del Valle SAS",
                     "documento":"901234567-8","correo":"facturacion@distrivalle.co",
                     "telefono":"3001234567"}}""");

            OrderRequestRecord dto = mapper.readValue(conFactura, OrderRequestRecord.class);

            assertThat(dto.facturaElectronica()).isNotNull();
            assertThat(dto.facturaElectronica().nombre()).isEqualTo("Distribuciones del Valle SAS");
            assertThat(dto.facturaElectronica().documento()).isEqualTo("901234567-8");
            assertThat(dto.facturaElectronica().correo()).isEqualTo("facturacion@distrivalle.co");
            assertThat(dto.facturaElectronica().telefono()).isEqualTo("3001234567");
            // Y lo de siempre sigue igual: aceptar el campo nuevo no cambia la venta.
            assertThat(dto.paymentMethod()).isEqualTo("CASH");
            assertThat(dto.totalDeclaradoPorElCliente()).isEqualByComparingTo("25000");
        }

        @Test
        @DisplayName("el telefono de la factura es opcional, igual que en el POS")
        void elTelefonoDeLaFacturaEsOpcional() throws Exception {
            String sinTelefono = """
                    {"pagerColor":"AZUL","pagerNumber":"3","items":[],"paymentMethod":"CASH",
                     "facturaElectronica":{"nombre":"Ana Perez","documento":"1090123456",
                     "correo":"ana@correo.co"}}
                    """;
            OrderRequestRecord dto = mapper.readValue(sinTelefono, OrderRequestRecord.class);

            assertThat(dto.facturaElectronica().nombre()).isEqualTo("Ana Perez");
            assertThat(dto.facturaElectronica().telefono()).isNull();
        }

        @Test
        @DisplayName("`createdAt` del POS actual SÍ se aprovecha: puebla ocurridoEn")
        void elCreatedAtDelPosSeAprovecha() throws Exception {
            OrderRequestRecord dto = mapper.readValue(PAYLOAD_DEL_POS_ACTUAL, OrderRequestRecord.class);

            // Esta es la ganancia grande: desplegar el backend basta para que
            // TODOS los POS ya instalados empiecen a aportar la hora real de la
            // venta, sin actualizarse. Cada dia que se adelanta es un dia de
            // serie buena que no se pierde.
            assertThat(dto.ocurridoEn())
                    .as("el alias createdAt -> ocurridoEn es lo que hace que esto sirva desde el dia uno")
                    .isEqualTo(OffsetDateTime.of(2026, 8, 20, 18, 5, 11, 0, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("sin ningún campo nuevo, la orden se construye igual y la procedencia queda nula")
        void sinCamposNuevos() throws Exception {
            String minimo = """
                    {"pagerColor":"AZUL","pagerNumber":"3","items":[],"paymentMethod":"CARD"}
                    """;
            OrderRequestRecord dto = mapper.readValue(minimo, OrderRequestRecord.class);

            assertThat(dto.ocurridoEn()).isNull();
            assertThat(dto.terminalId()).isNull();
            assertThat(dto.epoch()).isNull();
            assertThat(dto.seq()).isNull();
            assertThat(dto.hashAnterior()).isNull();
        }

        @Test
        @DisplayName("los seis campos que calcula el servidor se ignoran de forma DELIBERADA")
        void losCamposDelServidorSeIgnoran() throws Exception {
            // No es que "no se lean": es que aceptarlos permitiria a un POS
            // manipulado fijar el importe de su propia venta.
            String conTotalesFalsos = """
                    {"pagerColor":"AZUL","pagerNumber":"3","items":[],"paymentMethod":"CARD",
                     "subtotal":1,"discountAmount":99999,
                     "status":"pagado","synced":true,"idOrder":42,"tenantId":"otro-negocio"}
                    """;
            // No lanza: estan en la lista explicita de @JsonIgnoreProperties.
            OrderRequestRecord dto = mapper.readValue(conTotalesFalsos, OrderRequestRecord.class);
            assertThat(dto.pagerColor()).isEqualTo("AZUL");
            // El record no tiene donde guardarlos, que es justo la garantia.
        }

        @Test
        @DisplayName("`total` SI se lee, pero como senal: el record lo expone aparte")
        void elTotalSeLeeComoSenal() throws Exception {
            OrderRequestRecord dto = mapper.readValue(PAYLOAD_DEL_POS_ACTUAL, OrderRequestRecord.class);

            // El nombre del campo dice lo que es. No hay un `total()` que alguien
            // pueda usar por error creyendo que es el total de la orden.
            assertThat(dto.totalDeclaradoPorElCliente()).isEqualByComparingTo("25000");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Cliente nuevo, con procedencia completa")
    class ClienteNuevo {

        @Test
        @DisplayName("las dos fechas se guardan por separado y son distintas")
        void lasDosFechasSonDistintas() throws Exception {
            String payload = """
                    {"pagerColor":"AZUL","pagerNumber":"3","items":[],"paymentMethod":"CASH",
                     "ocurridoEn":"2026-08-20T13:00:00-05:00",
                     "terminalId":"3f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10",
                     "epoch":2,"seq":1435,
                     "hashAnterior":"%s"}
                    """.formatted("a".repeat(64));

            OrderRequestRecord dto = mapper.readValue(payload, OrderRequestRecord.class);

            assertThat(dto.ocurridoEn())
                    .isEqualTo(OffsetDateTime.of(2026, 8, 20, 13, 0, 0, 0, ZoneOffset.ofHours(-5)));
            assertThat(dto.terminalId()).isEqualTo("3f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10");
            assertThat(dto.epoch()).isEqualTo(2);
            assertThat(dto.seq()).isEqualTo(1435L);
            assertThat(dto.hashAnterior()).hasSize(64);
        }

        /**
         * ⚠️ PELIGRO DOCUMENTADO, no comportamiento deseado.
         *
         * <p>Si el payload trae {@code createdAt} Y {@code ocurridoEn}, Jackson
         * los mapea al MISMO campo y <b>gana el último que aparece en el JSON</b>,
         * en silencio. Verificado empíricamente contra jackson-databind 2.19.1.
         *
         * <p>Eso es exactamente la clase de defecto que esta fase persigue: un
         * resultado que depende del orden y no avisa. Por eso <b>el POS debe
         * enviar un solo nombre</b> — y envía {@code createdAt}, que ya venía
         * enviando. T4 NO añade {@code ocurridoEn} al payload.
         *
         * <p>Este test existe para que quien algún día lo añada vea aquí por qué
         * no debe.
         */
        @Test
        @DisplayName("PELIGRO: mandar createdAt Y ocurridoEn es ambiguo — gana el último del JSON")
        void mandarLosDosEsAmbiguo() throws Exception {
            String base = "\"pagerColor\":\"AZUL\",\"pagerNumber\":\"3\",\"items\":[],\"paymentMethod\":\"CASH\"";

            OrderRequestRecord aliasPrimero = mapper.readValue(
                    "{" + base + ",\"createdAt\":\"2026-08-20T18:05:11Z\","
                            + "\"ocurridoEn\":\"2026-08-20T13:00:00-05:00\"}",
                    OrderRequestRecord.class);
            OrderRequestRecord campoPrimero = mapper.readValue(
                    "{" + base + ",\"ocurridoEn\":\"2026-08-20T13:00:00-05:00\","
                            + "\"createdAt\":\"2026-08-20T18:05:11Z\"}",
                    OrderRequestRecord.class);

            // El MISMO contenido, en distinto orden, da resultados distintos.
            assertThat(aliasPrimero.ocurridoEn())
                    .as("gana `ocurridoEn` porque va segundo")
                    .isEqualTo(OffsetDateTime.of(2026, 8, 20, 18, 0, 0, 0, ZoneOffset.UTC));
            assertThat(campoPrimero.ocurridoEn())
                    .as("gana `createdAt` porque va segundo")
                    .isEqualTo(OffsetDateTime.of(2026, 8, 20, 18, 5, 11, 0, ZoneOffset.UTC));
            assertThat(aliasPrimero.ocurridoEn()).isNotEqualTo(campoPrimero.ocurridoEn());
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Un campo desconocido falla RUIDOSAMENTE")
    class CamposDesconocidos {

        @Test
        @DisplayName("un campo que nadie declara ni ignora revienta la petición")
        void campoDesconocido() {
            String conBasura = """
                    {"pagerColor":"AZUL","pagerNumber":"3","items":[],"paymentMethod":"CASH",
                     "campoQueNadieDeclaro":"se-perderia-en-silencio"}
                    """;
            // Es EL punto de FAIL_ON_UNKNOWN_PROPERTIES: preferimos que un
            // despliegue falle en staging a que un dato se pierda callado durante
            // meses, que es lo que paso con `createdAt`.
            assertThatThrownBy(() -> mapper.readValue(conBasura, OrderRequestRecord.class))
                    .isInstanceOf(UnrecognizedPropertyException.class)
                    .hasMessageContaining("campoQueNadieDeclaro");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Cordura del reloj del dispositivo")
    class Reloj {

        private final CorduraDelRelojDelDispositivo cordura = new CorduraDelRelojDelDispositivo();
        private final OffsetDateTime ahora = OffsetDateTime.of(2026, 8, 20, 19, 0, 0, 0, ZoneOffset.UTC);

        @BeforeEach
        void configurar() {
            cordura.configurar(7, 120);
        }

        @Test
        @DisplayName("sin fecha: no es un problema, es un cliente viejo")
        void sinFecha() {
            assertThat(cordura.evaluar(null, ahora)).isEqualTo(Veredicto.sin_fecha);
        }

        @Test
        @DisplayName("reloj adelantado un día: se ACEPTA y se marca")
        void relojAdelantadoUnDia() {
            // Nada puede ocurrir despues de que el servidor lo supo.
            assertThat(cordura.evaluar(ahora.plusDays(1), ahora)).isEqualTo(Veredicto.adelantado);
        }

        @Test
        @DisplayName("reloj atrasado TRES días: se acepta SIN marca — es sincronización tardía real")
        void atrasoDentroDeLaVentana() {
            // Es el caso normal del local-first: una venta que estuvo dias en la
            // cola porque no habia internet. Marcarla seria declarar sospechosa
            // la operacion que este modelo existe para poder registrar.
            assertThat(cordura.evaluar(ahora.minusDays(3), ahora)).isEqualTo(Veredicto.creible);
        }

        @Test
        @DisplayName("atraso de treinta días: fuera de lo que cualquier cola justifica")
        void atrasoExcesivo() {
            assertThat(cordura.evaluar(ahora.minusDays(30), ahora)).isEqualTo(Veredicto.muy_atrasado);
        }

        @Test
        @DisplayName("unos segundos de adelanto no se marcan: es desincronización normal")
        void margenDeAdelanto() {
            assertThat(cordura.evaluar(ahora.plusSeconds(30), ahora)).isEqualTo(Veredicto.creible);
        }

        @Test
        @DisplayName("la ventana es configurable: depende de si el local tiene internet estable")
        void ventanaConfigurable() {
            cordura.configurar(1, 120);
            assertThat(cordura.evaluar(ahora.minusDays(3), ahora)).isEqualTo(Veredicto.muy_atrasado);
            cordura.configurar(30, 120);
            assertThat(cordura.evaluar(ahora.minusDays(3), ahora)).isEqualTo(Veredicto.creible);
        }

        @Test
        @DisplayName("NUNCA lanza: una venta no se rechaza por la hora del dispositivo")
        void nuncaRechaza() {
            // Un equipo con la pila de la BIOS agotada no puede dejar sin
            // facturar a un negocio. La deriva es un dato, no un error.
            assertThatCode(() -> {
                cordura.evaluar(ahora.plusYears(50), ahora);
                cordura.evaluar(ahora.minusYears(50), ahora);
                cordura.evaluar(null, ahora);
            }).doesNotThrowAnyException();
        }
    }
}
