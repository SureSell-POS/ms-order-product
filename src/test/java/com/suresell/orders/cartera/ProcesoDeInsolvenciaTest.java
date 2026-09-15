package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F4.13, corte (a), por la API real con {@code app_user} (RLS y permisos de verdad), sobre V75: las etapas
 * del proceso, la proyección en {@code clientes.en_insolvencia_desde}, la foto de la deuda al corte, la clasificación
 * ANTERIOR/POSTERIOR con la misma línea que la foto (§11.2, opción A), las cifras de B6 y el endpoint viejo de F4.4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class ProcesoDeInsolvenciaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-insolvencia";
    static final String OTRO = "qa-insolvencia-otro";
    static final String ADMIN = "admin@qa-insolvencia.invalid";
    static final String ADMIN_OTRO = "admin@qa-insolvencia-otro.invalid";
    static final String CAJA = "caja@qa-insolvencia.invalid";
    static final String VENDEDOR = "ana@qa-insolvencia.invalid";
    static final String VENDEDOR_OTRO = "luis@qa-insolvencia.invalid";
    static final String TIENDA = "913";
    static final String VECINA = "914";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "app_pw");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
        r.add("security.jwt.secret", () -> SECRET);
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate dueno;
    private String cuenta;
    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));

    private static String bearer(String email, String role, String tenant) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(String email, String role) {
        return bearer(email, role, T);
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            limpiarInsolvencia(dueno, t);
            dueno.update("DELETE FROM cartera_aplicaciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NOT NULL", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM contadores_de_recibos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        usuario(ADMIN, T, "admin", "Admin");
        usuario(CAJA, T, "cajero", "Caja");
        long ana = usuario(VENDEDOR, T, "vendedor", "Ana");
        long luis = usuario(VENDEDOR_OTRO, T, "vendedor", "Luis");
        usuario(ADMIN_OTRO, OTRO, "admin", "Admin del otro");
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda', ?, 8, 's'), (?, ?, 'Vecina', ?, 8, 's')", T, TIENDA, ana, T, VECINA, luis);
        cuenta = UUID.randomUUID().toString();
        dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                + "customer_name, status, total_debt, updated_at) VALUES (?, ?, now(), 1000000, ?, 'Tienda', 'ACTIVE', 0, now())",
                cuenta, T, TIENDA);
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
    }

    /** Lo que deja un proceso de insolvencia (V75), para las pruebas que borran el negocio de prueba. */
    static void limpiarInsolvencia(JdbcTemplate dueno, String tenant) {
        dueno.update("DELETE FROM insolvencia_credito_posterior WHERE tenant_id = ?", tenant);
        dueno.update("DELETE FROM insolvencia_foto_facturas WHERE tenant_id = ?", tenant);
        dueno.update("DELETE FROM insolvencia_fotos WHERE tenant_id = ?", tenant);
        dueno.update("DELETE FROM insolvencia_etapas WHERE tenant_id = ?", tenant);
        dueno.update("DELETE FROM insolvencia_procesos WHERE tenant_id = ?", tenant);
    }

    private long usuario(String email, String tenant, String rol, String nombre) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, tenant, rol, nombre);
    }

    // ---------------------------------------------------------------- ayudas

    /** Una factura a crédito de la Tienda, nacida el día {@code fecha} en el instante {@code creada}. */
    private String factura(int monto, LocalDate fecha, Instant creada) {
        String id = UUID.randomUUID().toString();
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, "
                + "type, vence_el, order_uuid) VALUES (?, ?, ?, ?, ?, 'Venta a credito', ?, 'DEBIT', ?, ?)", id, T, cuenta, monto,
                java.sql.Timestamp.from(creada), java.sql.Date.valueOf(fecha), java.sql.Date.valueOf(fecha.plusDays(8)), UUID.randomUUID());
        return id;
    }

    private ResultActions etapa(String email, String rol, String doc, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/clientes/" + doc + "/insolvencia/etapas").header("Authorization", bearer(email, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions etapa(String cuerpo) throws Exception {
        return etapa(ADMIN, "admin", TIENDA, cuerpo);
    }

    private static String cuerpo(String etapa, LocalDate fecha, String extra) {
        return "{\"etapa\":\"" + etapa + "\",\"fecha\":\"" + fecha + "\",\"documento\":\"Auto 400-12\",\"informadoPor\":\"el abogado del cliente\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions marcaAnterior(String desde) throws Exception {
        return mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia").header("Authorization", bearer(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"desde\":" + (desde == null ? "null" : "\"" + desde + "\"") + "}"));
    }

    private JsonNode proceso(String email, String rol, String doc) throws Exception {
        return leer(mockMvc.perform(get("/api/cartera/clientes/" + doc + "/insolvencia").header("Authorization", bearer(email, rol)))
                .andExpect(status().isOk()));
    }

    private JsonNode proceso() throws Exception {
        return proceso(ADMIN, "admin", TIENDA);
    }

    private JsonNode foto() throws Exception {
        return leer(mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia/foto").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()));
    }

    private JsonNode estadoDeCuenta() throws Exception {
        return leer(mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/estado-de-cuenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()));
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String columna() {
        return dueno.queryForList("SELECT en_insolvencia_desde::text FROM clientes WHERE tenant_id = ? AND documento = ?", String.class, T, TIENDA)
                .get(0);
    }

    private int contar(String sql, Object... args) {
        return dueno.queryForObject(sql, Integer.class, args);
    }

    private String clasificacion(JsonNode estado, String debitoTxId) {
        for (JsonNode d : estado.get("documentos")) {
            if (d.get("debitoTxId").asText().equals(debitoTxId)) {
                return d.get("clasificacion").isNull() ? null : d.get("clasificacion").asText();
            }
        }
        throw new AssertionError("no está en el estado de cuenta: " + debitoTxId);
    }

    // ---------------------------------------------------------------- etapas

    @Test
    @DisplayName("🔴 SOLICITUD → INICIO → ACUERDO → CUMPLIDO: la solicitud no proyecta, el inicio sí y toma la foto, el cumplido cierra")
    void recorridoCompleto() throws Exception {
        String vieja = factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));

        JsonNode solicitud = leer(etapa(cuerpo("SOLICITUD", hoy.minusDays(10), "\"regimen\":\"ley_1116\",\"numeroProceso\":\"2026-00077\""))
                .andExpect(status().isCreated()));
        assertThat(solicitud.get("abierto").asBoolean()).isTrue();
        assertThat(solicitud.get("enProceso").asBoolean()).as("entre solicitud e inicio nada se bloquea").isFalse();
        assertThat(solicitud.get("regimen").asText()).isEqualTo("LEY_1116");
        assertThat(solicitud.get("regimenPendiente").asBoolean()).isFalse();
        assertThat(solicitud.get("cifras").isNull()).isTrue();
        assertThat(columna()).isNull();

        JsonNode inicio = leer(etapa(cuerpo("INICIO", hoy.minusDays(5), "\"autoridad\":\"Superintendencia de Sociedades\""))
                .andExpect(status().isCreated()));
        assertThat(inicio.get("enProceso").asBoolean()).isTrue();
        assertThat(inicio.get("etapa").asText()).isEqualTo("INICIO");
        assertThat(inicio.get("inicio").asText()).isEqualTo(hoy.minusDays(5).toString());
        assertThat(Instant.parse(inicio.get("corte").asText())).as("retroactivo: el final del día anterior a la fecha del auto")
                .isEqualTo(hoy.minusDays(5).atStartOfDay(ZoneId.of("America/Bogota")).toInstant());
        assertThat(inicio.get("regimen").asText()).as("el régimen vigente es el último informado").isEqualTo("LEY_1116");
        assertThat(inicio.get("numeroProceso").asText()).isEqualTo("2026-00077");
        assertThat(inicio.get("foto").get("total").decimalValue()).isEqualByComparingTo("100000");
        assertThat(inicio.get("cifras").get("deudaAnteriorAlInicio").decimalValue()).isEqualByComparingTo("100000");
        assertThat(inicio.get("cifras").get("deudaPosteriorAlInicio").decimalValue()).isEqualByComparingTo("0");
        assertThat(inicio.get("etapas")).hasSize(2);
        assertThat(inicio.get("etapas").get(1).get("registradoPor").asText()).isEqualTo("Admin");
        assertThat(columna()).isEqualTo(hoy.minusDays(5).toString());
        assertThat(dueno.queryForList("SELECT usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'en_insolvencia_desde'", Long.class, T))
                .as("la proyección deja rastro con autor (V67)").hasSize(1).doesNotContainNull();

        // Una venta después del inicio: POSTERIOR en el estado de cuenta y en las cifras; la foto no cambia.
        String nueva = factura(50000, hoy, Instant.now());
        JsonNode estado = estadoDeCuenta();
        assertThat(clasificacion(estado, vieja)).isEqualTo("ANTERIOR");
        assertThat(clasificacion(estado, nueva)).isEqualTo("POSTERIOR");
        assertThat(estado.get("insolvencia").get("etapa").asText()).isEqualTo("INICIO");
        assertThat(estado.get("insolvencia").get("inicio").asText()).isEqualTo(hoy.minusDays(5).toString());
        JsonNode conVenta = proceso();
        assertThat(conVenta.get("cifras").get("deudaPosteriorAlInicio").decimalValue()).isEqualByComparingTo("50000");
        assertThat(conVenta.get("foto").get("total").decimalValue()).isEqualByComparingTo("100000");

        etapa(cuerpo("ACUERDO_CONFIRMADO", hoy.minusDays(1), null)).andExpect(status().isCreated());
        assertThat(columna()).as("en acuerdo sigue en proceso").isEqualTo(hoy.minusDays(5).toString());
        JsonNode cumplido = leer(etapa(cuerpo("CUMPLIDO_TERMINADO", hoy, null)).andExpect(status().isCreated()));
        assertThat(cumplido.get("abierto").asBoolean()).isFalse();
        assertThat(cumplido.get("enProceso").asBoolean()).isFalse();
        assertThat(cumplido.get("etapas")).hasSize(4);
        assertThat(columna()).isNull();
        JsonNode despues = estadoDeCuenta();
        assertThat(despues.get("insolvencia").isNull()).isTrue();
        assertThat(clasificacion(despues, vieja)).isNull();
    }

    @Test
    @DisplayName("🔴 corte del mismo día (§11.2 A): lo nacido antes del registro del INICIO es ANTERIOR y está en la foto; lo de después, POSTERIOR")
    void corteDelMismoDia() throws Exception {
        String deEstaManana = factura(30000, hoy, Instant.now().minusSeconds(60));
        String deAyer = factura(20000, hoy.minusDays(1), Instant.now().minusSeconds(86400));
        JsonNode inicio = leer(etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated()));
        Instant corte = Instant.parse(inicio.get("corte").asText());
        assertThat(corte).as("mismo día: el instante del registro").isAfter(Instant.now().minusSeconds(30));
        String deLaTarde = factura(70000, hoy, Instant.now().plusSeconds(1));

        JsonNode foto = foto();
        assertThat(foto.get("filas")).extracting(f -> f.get("debitoTxId").asText()).containsExactlyInAnyOrder(deEstaManana, deAyer);
        assertThat(foto.get("total").decimalValue()).isEqualByComparingTo("50000");
        JsonNode estado = estadoDeCuenta();
        assertThat(clasificacion(estado, deEstaManana)).isEqualTo("ANTERIOR");
        assertThat(clasificacion(estado, deAyer)).isEqualTo("ANTERIOR");
        assertThat(clasificacion(estado, deLaTarde)).isEqualTo("POSTERIOR");
    }

    @Test
    @DisplayName("🔴 corte retroactivo: una factura registrada antes del INICIO pero nacida después de la fecha del auto es POSTERIOR y no está en la foto")
    void corteRetroactivo() throws Exception {
        String anterior = factura(40000, hoy.minusDays(6), Instant.now().minusSeconds(86400L * 6));
        String despuesDelAuto = factura(25000, hoy.minusDays(3), Instant.now().minusSeconds(86400L * 3));
        etapa(cuerpo("INICIO", hoy.minusDays(5), null)).andExpect(status().isCreated());
        assertThat(foto().get("filas")).extracting(f -> f.get("debitoTxId").asText()).containsExactly(anterior);
        JsonNode estado = estadoDeCuenta();
        assertThat(clasificacion(estado, anterior)).isEqualTo("ANTERIOR");
        assertThat(clasificacion(estado, despuesDelAuto)).isEqualTo("POSTERIOR");
    }

    @Test
    @DisplayName("🔴 la foto se reproduce: después de anular un abono anterior y vender, recalcular al corte da la misma huella; y es solo anexa")
    void fotoInmutableYReproducible() throws Exception {
        factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        String recibo = leer(mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(CAJA, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"monto\":30000,\"medio\":\"EFECTIVO\",\"idempotencyKey\":\"foto-abono\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated());
        JsonNode foto = foto();
        assertThat(foto.get("total").decimalValue()).as("el abono anterior al corte cuenta").isEqualByComparingTo("70000");
        assertThat(foto.get("filas").get(0).get("aplicadoAlCorte").decimalValue()).isEqualByComparingTo("30000");

        mockMvc.perform(post("/api/cartera/recibos/" + recibo + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        factura(50000, hoy, Instant.now().plusSeconds(1));

        assertThat(dueno.queryForObject("SELECT fn_huella_de_deuda(?, ?, ?::timestamptz)", String.class, T, TIENDA, foto.get("corte").asText()))
                .isEqualTo(foto.get("huella").asText());
        assertThat(foto().get("total").decimalValue()).isEqualByComparingTo("70000");
        assertThat(foto().get("huella").asText()).isEqualTo(foto.get("huella").asText());

        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), "app_user", "app_pw"));
        for (String sql : List.of("UPDATE insolvencia_fotos SET total = 0", "DELETE FROM insolvencia_foto_facturas",
                "UPDATE insolvencia_etapas SET fecha = current_date", "DELETE FROM insolvencia_procesos")) {
            assertThatThrownBy(() -> app.update(sql))
                    .as(sql).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    @DisplayName("🔴 corregir la fecha del INICIO anexa una foto que reemplaza la vieja y mueve la proyección")
    void corregirLaFechaDelInicio() throws Exception {
        factura(100000, hoy.minusDays(40), Instant.now().minusSeconds(86400L * 40));
        factura(50000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        JsonNode inicio = leer(etapa(cuerpo("INICIO", hoy.minusDays(5), null)).andExpect(status().isCreated()));
        String fotoVieja = inicio.get("foto").get("id").asText();
        String idInicio = inicio.get("etapas").get(0).get("id").asText();

        JsonNode corregido = leer(etapa("{\"etapa\":\"CORRECCION_DE_ERROR\",\"fecha\":\"" + hoy.minusDays(30) + "\",\"documento\":\"El auto es del "
                + hoy.minusDays(30) + "\",\"informadoPor\":\"abogado\",\"corrigeEtapaId\":\"" + idInicio + "\"}").andExpect(status().isCreated()));
        assertThat(corregido.get("etapa").asText()).as("una corrección de fecha no cambia la etapa vigente").isEqualTo("INICIO");
        assertThat(corregido.get("inicio").asText()).isEqualTo(hoy.minusDays(30).toString());
        assertThat(corregido.get("foto").get("total").decimalValue()).isEqualByComparingTo("100000");
        assertThat(foto().get("reemplazaFotoId").asText()).isEqualTo(fotoVieja);
        assertThat(columna()).isEqualTo(hoy.minusDays(30).toString());
        assertThat(contar("SELECT count(*) FROM insolvencia_fotos WHERE tenant_id = ?", T)).as("la vieja se queda").isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 catálogo: lo que no sigue es 409 ETAPA_NO_PERMITIDA; en liquidación no se levanta (409 LIQUIDACION_NO_SE_LEVANTA); los datos malos, 400 con campo")
    void catalogoYValidaciones() throws Exception {
        etapa(cuerpo("ACUERDO_CONFIRMADO", hoy, null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("ETAPA_NO_PERMITIDA"));
        etapa(cuerpo("ETAPA_RARA", hoy, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("etapa"))
                .andExpect(jsonPath("$.message").value("La etapa no es válida."));
        etapa(cuerpo("INICIO", hoy.plusDays(1), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("fecha"))
                .andExpect(jsonPath("$.message").value(ProcesoDeInsolvencia.FECHA_POSTERIOR_A_HOY));
        etapa(cuerpo("INICIO", hoy, "\"regimen\":\"CONCORDATO\"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("regimen"))
                .andExpect(jsonPath("$.message").value("El régimen es Ley 1116 o Código General del Proceso."));
        etapa(cuerpo("SOLICITUD", hoy.plusDays(1), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ProcesoDeInsolvencia.FECHA_DE_ETAPA_POSTERIOR_A_HOY));
        etapa("{\"etapa\":\"INICIO\",\"fecha\":\"" + hoy + "\",\"informadoPor\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("documento"));
        etapa("{\"etapa\":\"INICIO\",\"fecha\":\"" + hoy + "\",\"documento\":\"Auto\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("informadoPor"));
        assertThat(contar("SELECT count(*) FROM insolvencia_procesos WHERE tenant_id = ?", T)).isZero();

        etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated());
        etapa(cuerpo("CUMPLIDO_TERMINADO", hoy, null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("ETAPA_NO_PERMITIDA"))
                .andExpect(jsonPath("$.message").value("Un proceso en «Proceso iniciado» no pasa a «Acuerdo cumplido, proceso terminado». No se registró nada."));
        etapa(cuerpo("CORRECCION_DE_ERROR", hoy, "\"corrigeEtapaId\":\"" + UUID.randomUUID() + "\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("corrigeEtapaId"));
        etapa(cuerpo("LIQUIDACION", hoy, null)).andExpect(status().isCreated());
        etapa(cuerpo("CORRECCION_DE_ERROR", hoy, null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("LIQUIDACION_NO_SE_LEVANTA"))
                .andExpect(jsonPath("$.message").value("En liquidación la insolvencia no se levanta."));
        etapa(cuerpo("CUMPLIDO_TERMINADO", hoy, null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("LIQUIDACION_NO_SE_LEVANTA"));
        assertThat(columna()).isEqualTo(hoy.toString());

        // SOLICITUD_NO_ADMITIDA es terminal.
        etapa(ADMIN, "admin", VECINA, cuerpo("SOLICITUD", hoy, null)).andExpect(status().isCreated());
        etapa(ADMIN, "admin", VECINA, cuerpo("SOLICITUD_NO_ADMITIDA", hoy, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.abierto").value(false));
        etapa(ADMIN, "admin", VECINA, cuerpo("INICIO", hoy, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.etapas.length()").value(1));
    }

    @Test
    @DisplayName("🔴 quién: informar y ver la foto es del admin; el vendedor ve el proceso solo de sus clientes; el otro negocio no encuentra al cliente")
    void permisos() throws Exception {
        etapa(CAJA, "cajero", TIENDA, cuerpo("INICIO", hoy, null)).andExpect(status().isForbidden());
        etapa(VENDEDOR, "vendedor", TIENDA, cuerpo("INICIO", hoy, null)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo("INICIO", hoy, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        assertThat(contar("SELECT count(*) FROM insolvencia_procesos WHERE tenant_id IN (?, ?)", T, OTRO)).isZero();

        etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated());
        assertThat(proceso(VENDEDOR, "vendedor", TIENDA).get("enProceso").asBoolean()).isTrue();
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia").header("Authorization", bearer(VENDEDOR_OTRO, "vendedor")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("clienteDocumento"));
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia/foto").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia").header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("🟢 la foto en CSV para presentar al proceso: corte, huella, una fila por factura y el total")
    void fotoEnCsv() throws Exception {
        factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        factura(23500, hoy.minusDays(9), Instant.now().minusSeconds(86400L * 9));
        etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated());
        String huella = foto().get("huella").asText();
        String csv = mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia/foto?formato=csv").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Huella;" + huella).contains("Venta N.;Fecha;Vence;Monto;Abonado al corte;Saldo al corte")
                .contains(";100000;0;100000").contains(";23500;0;23500").contains("Total;;;;;123500");
        mockMvc.perform(get("/api/cartera/clientes/" + VECINA + "/insolvencia/foto").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        JsonNode sinProceso = proceso(ADMIN, "admin", VECINA);
        assertThat(sinProceso.get("procesoId").isNull()).isTrue();
        assertThat(sinProceso.get("etapas")).isEmpty();
    }

    // ---------------------------------------------------------------- endpoint viejo (F4.4, R10)

    @Test
    @DisplayName("🔴 endpoint viejo: la fecha registra INICIO sin documento y régimen pendiente; otra fecha corrige; null con proceso 409 LEVANTAR_SIN_ETAPA (b); en liquidación 409; futura 400")
    void endpointViejo() throws Exception {
        marcaAnterior(hoy.plusDays(1).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("fecha"))
                .andExpect(jsonPath("$.message").value(ProcesoDeInsolvencia.FECHA_POSTERIOR_A_HOY));
        assertThat(contar("SELECT count(*) FROM insolvencia_procesos WHERE tenant_id = ?", T)).isZero();
        assertThat(columna()).isNull();

        marcaAnterior(hoy.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteDocumento").value(TIENDA))
                .andExpect(jsonPath("$.enInsolvenciaDesde").value(hoy.toString()));
        JsonNode p = proceso();
        assertThat(p.get("etapa").asText()).isEqualTo("INICIO");
        assertThat(p.get("regimenPendiente").asBoolean()).isTrue();
        assertThat(p.get("etapas").get(0).get("documento").asText()).isEqualTo("Sin documento (registrado desde la marca anterior)");
        assertThat(p.get("etapas").get(0).get("informadoPor").asText()).as("en pantalla, el nombre y no un id").isEqualTo("Admin");
        assertThat(p.get("foto").isNull()).isFalse();

        marcaAnterior(hoy.toString()).andExpect(status().isOk());
        assertThat(proceso().get("etapas")).as("la misma fecha no anexa nada").hasSize(1);
        marcaAnterior(hoy.minusDays(2).toString()).andExpect(status().isOk()).andExpect(jsonPath("$.enInsolvenciaDesde").value(hoy.minusDays(2).toString()));
        assertThat(proceso().get("etapas").get(1).get("etapa").asText()).isEqualTo("CORRECCION_DE_ERROR");
        assertThat(contar("SELECT count(*) FROM insolvencia_fotos WHERE tenant_id = ? AND reemplaza_foto_id IS NOT NULL", T)).isEqualTo(1);

        // F4.13 (b): con null ya no se levanta; se levanta informando la etapa (B8).
        marcaAnterior(null).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("LEVANTAR_SIN_ETAPA"))
                .andExpect(jsonPath("$.message").value("La insolvencia se levanta informando que el acuerdo se cumplió y el proceso "
                        + "terminó, o que se marcó por error. No se registró nada."));
        assertThat(columna()).isEqualTo(hoy.minusDays(2).toString());
        etapa(cuerpo("CORRECCION_DE_ERROR", hoy, null)).andExpect(status().isCreated());
        assertThat(columna()).isNull();
        assertThat(proceso().get("abierto").asBoolean()).isFalse();
        marcaAnterior(null).andExpect(status().isOk()).andExpect(jsonPath("$.enInsolvenciaDesde").isEmpty());

        marcaAnterior(hoy.toString()).andExpect(status().isOk());
        etapa(cuerpo("LIQUIDACION", hoy, null)).andExpect(status().isCreated());
        marcaAnterior(null).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("LIQUIDACION_NO_SE_LEVANTA"))
                .andExpect(jsonPath("$.message").value("En liquidación la insolvencia no se levanta."));
        assertThat(columna()).isEqualTo(hoy.toString());
    }

    // ---------------------------------------------------------------- (b) bloqueos del §5

    private ResultActions abonar(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(CAJA, "cajero"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static String abono(int monto, String clave, String extra) {
        return "{\"clienteDocumento\":\"" + TIENDA + "\",\"monto\":" + monto + ",\"medio\":\"EFECTIVO\",\"idempotencyKey\":\"" + clave + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private java.util.UUID ordenDe(String debito) {
        return dueno.queryForObject("SELECT order_uuid FROM debt_transactions WHERE id = ?", java.util.UUID.class, debito);
    }

    private static String fechaLarga(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", java.util.Locale.forLanguageTag("es-CO")));
    }

    @Test
    @DisplayName("🔴 (b) en proceso: abonar lo ANTERIOR es 409 ABONO_A_DEUDA_ANTERIOR (elegida o sin elegir); lo POSTERIOR se abona y la más antigua reparte solo entre posteriores")
    void abonosEnProceso() throws Exception {
        String anterior = factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        etapa(cuerpo("INICIO", hoy.minusDays(5), null)).andExpect(status().isCreated());
        String texto = "Esa factura es anterior al inicio del proceso (" + fechaLarga(hoy.minusDays(5))
                + "): se reclama dentro del proceso. No se registró el abono.";

        // Solo hay deuda anterior: sin elegir y sin excedente, 409; con excedente, todo a favor sin tocarla.
        abonar(abono(10000, "b-sin-posterior", null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("ABONO_A_DEUDA_ANTERIOR")).andExpect(jsonPath("$.message").value(texto));
        abonar(abono(10000, "b-anticipo", "\"excedente\":\"SALDO_A_FAVOR\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavor").value(10000));
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ?", T)).isZero();

        String posterior = factura(50000, hoy.minusDays(1), Instant.now().minusSeconds(86400));
        abonar(abono(20000, "b-elegida-anterior", "\"aplicaciones\":[{\"orderUuid\":\"" + ordenDe(anterior) + "\",\"monto\":20000}]"))
                .andExpect(status().isConflict()).andExpect(ConflictoConMensaje.de("ABONO_A_DEUDA_ANTERIOR"));
        abonar(abono(20000, "b-elegida-posterior", "\"aplicaciones\":[{\"orderUuid\":\"" + ordenDe(posterior) + "\",\"monto\":20000}]"))
                .andExpect(status().isCreated());
        // Sin elegir: se reparte solo entre posteriores; más que lo posterior sin excedente es el 400 de siempre con ese tope.
        abonar(abono(40000, "b-pasado", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.maximo").value(30000));
        abonar(abono(30000, "b-mas-antigua", null)).andExpect(status().isCreated());

        assertThat(dueno.queryForObject("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND debito_tx_id = ?",
                java.math.BigDecimal.class, T, anterior)).as("lo anterior no se tocó").isEqualByComparingTo("100000");
        assertThat(dueno.queryForObject("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND debito_tx_id = ?",
                java.math.BigDecimal.class, T, posterior)).isEqualByComparingTo("0");
        assertThat(contar("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", T)).as("ni un recibo de los 409").isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 (b) la base también: una aplicación de abono a lo ANTERIOR o un cruce de saldo a favor con el cliente en proceso abortan (V76)")
    void laBaseTambien() throws Exception {
        String anterior = factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        String recibo = leer(abonar(abono(10000, "base-recibo", null)).andExpect(status().isCreated())).get("id").asText();
        String credito = dueno.queryForObject("SELECT id FROM debt_transactions WHERE recibo_id = ?::uuid", String.class, recibo);
        etapa(cuerpo("INICIO", hoy.minusDays(5), null)).andExpect(status().isCreated());
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), "app_user", "app_pw"));
        for (String regla : List.of("ELEGIDA_POR_USUARIO", "MAS_ANTIGUA_PRIMERO", "SALDO_A_FAVOR_AUTOMATICO")) {
            assertThatThrownBy(() -> app.execute((org.springframework.jdbc.core.ConnectionCallback<Object>) c -> {
                c.setAutoCommit(false);
                try (var st = c.createStatement()) {
                    st.execute("SELECT set_config('app.tenant_id', '" + T + "', true)");
                    st.execute("INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla) VALUES ('"
                            + T + "', '" + recibo + "', '" + credito + "', '" + anterior + "', 1, '" + regla + "')");
                } finally {
                    c.rollback();
                }
                return null;
            })).as(regla).rootCause().hasMessageContaining("proceso");
        }
    }

    @Test
    @DisplayName("🔴 (b) la columna puesta sin proceso en curso conserva el 409 viejo de cobros; en SOLICITUD se abona como siempre")
    void columnaSinProcesoYSolicitud() throws Exception {
        factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        etapa(cuerpo("SOLICITUD", hoy.minusDays(2), null)).andExpect(status().isCreated());
        abonar(abono(10000, "sol-abono", null)).andExpect(status().isCreated());
        assertThat(estadoDeCuenta().get("frase").isNull()).as("en SOLICITUD la frase se ofrece").isFalse();

        dueno.update("UPDATE clientes SET en_insolvencia_desde = ? WHERE tenant_id = ? AND documento = ?",
                java.sql.Date.valueOf(hoy.minusDays(1)), T, TIENDA);
        abonar(abono(10000, "col-abono", null)).andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("CLIENTE_EN_INSOLVENCIA"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cobros")));
    }

    @Test
    @DisplayName("🔴 (b) en proceso el estado de cuenta no ofrece la frase de cobro: frase null y motivoSinFrase; cerrado el proceso, vuelve")
    void sinFraseEnProceso() throws Exception {
        factura(100000, hoy.minusDays(20), Instant.now().minusSeconds(86400L * 20));
        JsonNode antes = estadoDeCuenta();
        assertThat(antes.get("frase").asText()).contains("Tienda");
        assertThat(antes.get("motivoSinFrase").isNull()).isTrue();
        etapa(cuerpo("INICIO", hoy, null)).andExpect(status().isCreated());
        JsonNode enProceso = estadoDeCuenta();
        assertThat(enProceso.get("frase").isNull()).isTrue();
        assertThat(enProceso.get("motivoSinFrase").asText()).isEqualTo("La deuda anterior al inicio se reclama dentro del proceso.");
        etapa(cuerpo("CORRECCION_DE_ERROR", hoy, null)).andExpect(status().isCreated());
        assertThat(estadoDeCuenta().get("frase").asText()).contains("Tienda");
    }
}
