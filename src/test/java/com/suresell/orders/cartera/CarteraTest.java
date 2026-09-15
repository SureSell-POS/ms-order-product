package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Plan de mayoristas F4.4 por la API real (contrato §7.4), sobre V64-V66, con
 * {@code app_user} (RLS y permisos de verdad). El caso del plan: la Tienda A debe
 * 110.000 en dos facturas (60.000 vencida hace 40 días y 50.000 que vence en 10).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CarteraTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-cartera";
    static final String OTRO = "qa-cartera-otro";
    static final String ADMIN = "admin@qa-cartera.invalid";
    static final String CAJA = "caja@qa-cartera.invalid";
    static final String ANA = "ana@qa-cartera.invalid";
    static final String PEDRO = "pedro@qa-cartera.invalid";
    static final String TIENDA_A = "900";
    static final String TIENDA_B = "800";

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
    private long admin;
    private long ana;
    private UUID ventaVieja;
    private UUID ventaNueva;
    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));

    private static String bearer(String email, String role, String tenant, String... modulos) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", role)
                .claim("modules", modulos.length == 0 ? List.of("ventas", "mayorista", "cartera") : List.of(modulos))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(String email, String role) {
        return bearer(email, role, T);
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            ProcesoDeInsolvenciaTest.limpiarInsolvencia(dueno, t);
            dueno.update("DELETE FROM cartera_aplicaciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NOT NULL", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM contadores_de_recibos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM daily_closures WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM sites WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING",
                    t, t.equals(T) ? "Distribuidora QA" : "Otra");
        }
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                + "VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        admin = usuario(ADMIN, "admin", "Admin");
        usuario(CAJA, "cajero", "Caja");
        ana = usuario(ANA, "vendedor", "Ana");
        long pedro = usuario(PEDRO, "vendedor", "Pedro");
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda A', ?, 8, 's'), (?, ?, 'Tienda B', ?, 8, 's')", T, TIENDA_A, ana, T, TIENDA_B, pedro);
        dueno.update("UPDATE clientes SET whatsapp = '3001234567' WHERE tenant_id = ? AND documento = ?", T, TIENDA_A);
        String cuentaA = cuenta(T, TIENDA_A, "Tienda A", 500000, 110000);
        ventaVieja = UUID.randomUUID();
        ventaNueva = UUID.randomUUID();
        debito(T, cuentaA, 60000, hoy.minusDays(48), hoy.minusDays(40), ventaVieja);
        debito(T, cuentaA, 50000, hoy.minusDays(5), hoy.plusDays(10), ventaNueva);
        String cuentaB = cuenta(T, TIENDA_B, "Tienda B", 100000, 30000);
        debito(T, cuentaB, 30000, hoy.minusDays(2), hoy.plusDays(6), UUID.randomUUID());
        // Otro negocio con el MISMO documento de la Tienda A y su propia deuda.
        String cuentaOtra = cuenta(OTRO, TIENDA_A, "La de otro negocio", 0, 999000);
        debito(OTRO, cuentaOtra, 999000, hoy.minusDays(100), hoy.minusDays(90), UUID.randomUUID());
        // Abrir las cuentas con cupo deja su evento (V66); las pruebas cuentan desde aquí.
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id IN (?, ?)", T, OTRO);
    }

    private long usuario(String email, String rol, String nombre) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, T, rol, nombre);
    }

    private String cuenta(String tenant, String doc, String nombre, int cupo, int deuda) {
        String id = UUID.randomUUID().toString();
        dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                + "customer_name, status, total_debt, updated_at) VALUES (?, ?, now(), ?, ?, ?, 'ACTIVE', ?, now())",
                id, tenant, cupo, doc, nombre, deuda);
        return id;
    }

    private void debito(String tenant, String cuenta, int monto, LocalDate fecha, LocalDate vence, UUID orden) {
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, "
                + "transaction_date, type, vence_el, order_uuid) VALUES (?, ?, ?, ?, now(), 'Venta a credito', ?, 'DEBIT', ?, ?)",
                UUID.randomUUID().toString(), tenant, cuenta, monto, java.sql.Date.valueOf(fecha),
                java.sql.Date.valueOf(vence), orden);
    }

    private ResultActions abonar(String quien, String rol, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static String abono(String doc, int monto, String medio, String clave) {
        return "{\"clienteDocumento\":\"" + doc + "\",\"monto\":" + monto + ",\"medio\":\"" + medio
                + "\",\"idempotencyKey\":\"" + clave + "\"}";
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private BigDecimal totalDebt(String doc) {
        return dueno.queryForObject("SELECT total_debt FROM accounts_receivable WHERE tenant_id = ? AND customer_document = ?",
                BigDecimal.class, T, doc);
    }

    private BigDecimal libro(String doc) {
        return dueno.queryForObject("SELECT COALESCE(sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END), 0) "
                + "FROM debt_transactions d JOIN accounts_receivable a ON a.id = d.account_id "
                + "WHERE a.tenant_id = ? AND a.customer_document = ?", BigDecimal.class, T, doc);
    }

    @Test
    @DisplayName("🔴 abono de 20.000 sobre 110.000: recibo 1, a la más antigua, saldo 90.000, el libro conserva las filas y total_debt cuadra")
    void abonoALaMasAntigua() throws Exception {
        JsonNode recibo = leer(abonar(CAJA, "cajero", abono(TIENDA_A, 20000, "EFECTIVO", "a-1"))
                .andExpect(status().isCreated()));
        assertThat(recibo.get("numero").asLong()).isEqualTo(1);
        assertThat(recibo.get("repetido").asBoolean()).isFalse();
        assertThat(recibo.get("saldoCliente").decimalValue()).isEqualByComparingTo("90000");
        assertThat(recibo.get("cobradoPorNombre").asText()).isEqualTo("Caja");
        assertThat(recibo.get("aplicaciones")).hasSize(1);
        assertThat(recibo.get("aplicaciones").get(0).get("orderUuid").asText()).isEqualTo(ventaVieja.toString());
        assertThat(recibo.get("aplicaciones").get(0).get("regla").asText()).isEqualTo("MAS_ANTIGUA_PRIMERO");

        assertThat(dueno.queryForObject("SELECT count(*) FROM debt_transactions d JOIN accounts_receivable a ON a.id = d.account_id "
                + "WHERE a.tenant_id = ? AND a.customer_document = ?", Integer.class, T, TIENDA_A)).isEqualTo(3);
        // R16: el abono es CREDIT sin payment_method; el medio real está en el recibo.
        assertThat(dueno.queryForMap("SELECT type, payment_method FROM debt_transactions WHERE tenant_id = ? AND recibo_id IS NOT NULL", T))
                .containsEntry("type", "CREDIT").containsEntry("payment_method", null);
        assertThat(totalDebt(TIENDA_A)).isEqualByComparingTo(libro(TIENDA_A)).isEqualByComparingTo("90000");

        // Estado de cuenta: cuadra con el libro, la mora sigue en la vieja (40.000 vencidos) y la frase lo dice.
        JsonNode estado = leer(mockMvc.perform(get("/api/cartera/clientes/" + TIENDA_A + "/estado-de-cuenta")
                .header("Authorization", bearer(ADMIN, "admin"))).andExpect(status().isOk()));
        assertThat(estado.get("cliente").get("saldo").decimalValue()).isEqualByComparingTo(libro(TIENDA_A));
        assertThat(estado.get("cliente").get("vencido").decimalValue()).isEqualByComparingTo("40000");
        assertThat(estado.get("documentos")).hasSize(2);
        assertThat(estado.get("documentos").get(0).get("saldo").decimalValue()).isEqualByComparingTo("40000");
        assertThat(estado.get("documentos").get(0).get("edad").asText()).isEqualTo("31_60");
        assertThat(estado.get("recibos")).hasSize(1);
        assertThat(estado.get("frase").asText()).contains("Tienda A", "Distribuidora QA", "$90.000", "$40.000", "vencidos");
        assertThat(estado.get("cliente").get("whatsapp").asText()).isEqualTo("3001234567");
        // El WhatsApp es dato personal: la lista no lo lleva.
        JsonNode lista = leer(mockMvc.perform(get("/api/cartera/clientes").header("Authorization", bearer(ADMIN, "admin"))));
        assertThat(lista.get(0).has("whatsapp")).isFalse();
    }

    @Test
    @DisplayName("🔴 idempotencia: el reintento devuelve el mismo recibo (200) sin escribir; la clave con otro monto es 409")
    void idempotencia() throws Exception {
        abonar(CAJA, "cajero", abono(TIENDA_A, 20000, "EFECTIVO", "idem-1")).andExpect(status().isCreated());
        abonar(CAJA, "cajero", abono(TIENDA_A, 20000, "EFECTIVO", "idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value(1))
                .andExpect(jsonPath("$.repetido").value(true));
        abonar(CAJA, "cajero", abono(TIENDA_A, 25000, "EFECTIVO", "idem-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_REUTILIZADA"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Integer.class, T)).isEqualTo(1);
        assertThat(totalDebt(TIENDA_A)).isEqualByComparingTo("90000");
    }

    @Test
    @DisplayName("🔴 números 1, 2, 3 sin huecos: un abono rechazado no gasta número; el de otro negocio lleva su propia cuenta")
    void numerosSinHuecos() throws Exception {
        abonar(CAJA, "cajero", abono(TIENDA_A, 10000, "EFECTIVO", "n-1")).andExpect(jsonPath("$.numero").value(1));
        abonar(CAJA, "cajero", abono(TIENDA_A, 200000, "EFECTIVO", "n-malo"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("monto"))
                .andExpect(jsonPath("$.message").value("El cliente debe $100.000; no se puede abonar más."))
                .andExpect(jsonPath("$.maximo").value(100000));
        abonar(CAJA, "cajero", abono(TIENDA_A, 1000, "PAGARE", "n-medio"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("medio"));
        abonar(CAJA, "cajero", abono(TIENDA_B, 5000, "BRE_B", "n-2")).andExpect(jsonPath("$.numero").value(2));
        abonar(CAJA, "cajero", abono(TIENDA_A, 5000, "TRANSFERENCIA", "n-3")).andExpect(jsonPath("$.numero").value(3));
        assertThat(dueno.queryForList("SELECT numero FROM recibos_de_caja WHERE tenant_id = ? ORDER BY numero", Long.class, T))
                .containsExactly(1L, 2L, 3L);
        // El otro negocio no vio nada de esto y su deuda sigue entera.
        assertThat(dueno.queryForObject("SELECT total_debt FROM accounts_receivable WHERE tenant_id = ?", BigDecimal.class, OTRO))
                .isEqualByComparingTo("999000");
    }

    @Test
    @DisplayName("aplicaciones elegidas: a la factura nueva por orderUuid; suma distinta, venta ajena o de más → 400 con campo")
    void aplicacionesElegidas() throws Exception {
        String elegida = "{\"clienteDocumento\":\"" + TIENDA_A + "\",\"monto\":30000,\"medio\":\"QR\",\"idempotencyKey\":\"e-%s\","
                + "\"aplicaciones\":[{\"orderUuid\":\"%s\",\"monto\":%d}]}";
        abonar(ADMIN, "admin", String.format(elegida, "suma", ventaNueva, 10000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("aplicaciones"));
        abonar(ADMIN, "admin", String.format(elegida, "ajena", UUID.randomUUID(), 30000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("aplicaciones[0].orderUuid"));
        JsonNode recibo = leer(abonar(ADMIN, "admin", String.format(elegida, "ok", ventaNueva, 30000))
                .andExpect(status().isCreated()));
        assertThat(recibo.get("numero").asLong()).isEqualTo(1);
        assertThat(recibo.get("aplicaciones").get(0).get("orderUuid").asText()).isEqualTo(ventaNueva.toString());
        assertThat(recibo.get("aplicaciones").get(0).get("regla").asText()).isEqualTo("ELEGIDA_POR_USUARIO");
        // La vieja sigue entera y vencida: la mora es por factura.
        mockMvc.perform(get("/api/cartera/clientes").header("Authorization", bearer(ADMIN, "admin")).param("q", "Tienda A"))
                .andExpect(jsonPath("$[0].vencido").value(60000))
                .andExpect(jsonPath("$[0].saldo").value(80000));
    }

    @Test
    @DisplayName("🔴 anular: solo admin; otro recibo con el motivo, la deuda vuelve, total_debt = libro; ni dos veces ni la anulación")
    void anular() throws Exception {
        String id = leer(abonar(CAJA, "cajero", abono(TIENDA_A, 20000, "CHEQUE", "an-1"))).get("id").asText();
        mockMvc.perform(post("/api/cartera/recibos/" + id + "/anular").header("Authorization", bearer(CAJA, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"CHEQUE_DEVUELTO\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/cartera/recibos/" + id + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"SE_ARREPINTIO\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        JsonNode anulacion = leer(mockMvc.perform(post("/api/cartera/recibos/" + id + "/anular")
                        .header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"CHEQUE_DEVUELTO\"}"))
                .andExpect(status().isCreated()));
        assertThat(anulacion.get("numero").asLong()).isEqualTo(2);
        assertThat(anulacion.get("anulaReciboId").asText()).isEqualTo(id);
        assertThat(anulacion.get("saldoCliente").decimalValue()).isEqualByComparingTo("110000");
        assertThat(totalDebt(TIENDA_A)).isEqualByComparingTo(libro(TIENDA_A)).isEqualByComparingTo("110000");
        // R16: la anulación es un DEBIT sin payment_method.
        assertThat(dueno.queryForObject("SELECT type FROM debt_transactions WHERE tenant_id = ? AND recibo_id = ?::uuid",
                String.class, T, anulacion.get("id").asText())).isEqualTo("DEBIT");

        mockMvc.perform(post("/api/cartera/recibos/" + id + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"DUPLICADO\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("RECIBO_YA_ANULADO"));
        mockMvc.perform(post("/api/cartera/recibos/" + anulacion.get("id").asText() + "/anular")
                        .header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"DUPLICADO\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("RECIBO_ES_ANULACION"));
        // El recibo original muestra quién lo anuló.
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA_A + "/estado-de-cuenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(jsonPath("$.recibos[0].anuladoPor.numero").value(2))
                .andExpect(jsonPath("$.documentos[0].saldo").value(60000));
    }

    @Test
    @DisplayName("🔴 el estado de cuenta de un cliente SIN WhatsApp responde 200 con whatsapp null (daba 500 desde 239c5b7)")
    void estadoDeCuentaSinWhatsapp() throws Exception {
        assertThat(dueno.queryForObject("SELECT whatsapp FROM clientes WHERE tenant_id = ? AND documento = ?", String.class, T, TIENDA_B))
                .as("precondición: la Tienda B no tiene WhatsApp").isNull();
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA_B + "/estado-de-cuenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cliente.clienteDocumento").value(TIENDA_B))
                .andExpect(jsonPath("$.cliente.whatsapp").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("🔴 un vendedor ve y cobra solo a sus clientes, aunque pida los de otro")
    void vendedorSoloLosSuyos() throws Exception {
        mockMvc.perform(get("/api/cartera/clientes").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].clienteDocumento").value(TIENDA_A));
        mockMvc.perform(get("/api/cartera/clientes").param("vendedorId", "999").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA_B + "/estado-de-cuenta").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isBadRequest());
        abonar(ANA, "vendedor", abono(TIENDA_B, 1000, "EFECTIVO", "v-ajeno")).andExpect(status().isBadRequest());
        abonar(ANA, "vendedor", abono(TIENDA_A, 1000, "EFECTIVO", "v-suyo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cobradoPor").value(ana));
        mockMvc.perform(get("/api/cartera/resumen").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cupo: solo admin; deja evento con autor (V66) y cambia excedeCupo; insolvencia suspende los cobros con 409")
    void cupoEInsolvencia() throws Exception {
        mockMvc.perform(put("/api/cartera/clientes/" + TIENDA_A + "/cupo").header("Authorization", bearer(CAJA, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":100000}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/cartera/clientes/" + TIENDA_A + "/cupo").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cupo").value(100000))
                .andExpect(jsonPath("$.excedeCupo").value(true));
        assertThat(dueno.queryForMap("SELECT valor_anterior, valor_nuevo, usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'cupo'", T))
                .containsEntry("valor_anterior", "500000.00").containsEntry("valor_nuevo", "100000.00")
                .containsEntry("usuario_id", admin);

        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA_A + "/insolvencia").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"desde\":\"" + hoy + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enInsolvenciaDesde").value(hoy.toString()));
        abonar(CAJA, "cajero", abono(TIENDA_A, 1000, "EFECTIVO", "ins-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_EN_INSOLVENCIA"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("cobros")));
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA_A + "/insolvencia").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"desde\":null}"))
                .andExpect(status().isOk());
        abonar(CAJA, "cajero", abono(TIENDA_A, 1000, "EFECTIVO", "ins-2")).andExpect(status().isCreated());
        assertThat(dueno.queryForList("SELECT campo FROM clientes_eventos WHERE tenant_id = ? ORDER BY ocurrido_en", String.class, T))
                .containsExactly("cupo", "en_insolvencia_desde", "en_insolvencia_desde");
    }

    @Test
    @DisplayName("resumen del negocio y filtro por edad: solo lo de este negocio, con el tramo de cada factura")
    void resumenYEdad() throws Exception {
        mockMvc.perform(get("/api/cartera/resumen").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porCobrar").value(140000))
                .andExpect(jsonPath("$.vencido").value(60000))
                .andExpect(jsonPath("$.porEdad.31_60").value(60000))
                .andExpect(jsonPath("$.porEdad.CORRIENTE").value(80000))
                .andExpect(jsonPath("$.topDeudores[0].clienteDocumento").value(TIENDA_A));
        mockMvc.perform(get("/api/cartera/clientes").param("edad", "31_60").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Tienda A"));
        mockMvc.perform(get("/api/cartera/clientes").param("edad", "vieja").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("edad"));
    }

    @Test
    @DisplayName("🔴 sin el módulo cartera en el token: 403 MODULO_NO_INCLUIDO y nada escrito")
    void sinModulo() throws Exception {
        mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(CAJA, "cajero", T, "ventas", "mayorista"))
                        .contentType(MediaType.APPLICATION_JSON).content(abono(TIENDA_A, 1000, "EFECTIVO", "mod-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("MODULO_NO_INCLUIDO"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Integer.class, T)).isZero();
    }

    private JsonNode preview() throws Exception {
        return leer(mockMvc.perform(get("/api/closures/preview").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isOk()));
    }

    @Test
    @DisplayName("🔴 F4.5: el abono en efectivo sube el esperado del cajón; la transferencia no; el cierre hecho no se toca y la anulación cae en el turno siguiente")
    void recaudoEnElCierre() throws Exception {
        JsonNode antes = preview();
        assertThat(antes.get("recaudoCarteraEfectivo").decimalValue()).isZero();
        BigDecimal efectivoAntes = antes.get("totalExpectedCash").decimalValue();

        String efectivo = leer(abonar(CAJA, "cajero", abono(TIENDA_A, 50000, "EFECTIVO", "cierre-efectivo"))
                .andExpect(status().isCreated())).get("id").asText();
        abonar(CAJA, "cajero", abono(TIENDA_A, 30000, "TRANSFERENCIA", "cierre-transferencia")).andExpect(status().isCreated());

        // Sumado UNA vez: si el recaudo entrara dos veces (en la consulta y en la respuesta), esto daría +100.000.
        JsonNode despues = preview();
        assertThat(despues.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("50000");
        assertThat(despues.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("50000")));
        assertThat(despues.get("totalExpected").decimalValue())
                .isEqualByComparingTo(antes.get("totalExpected").decimalValue().add(new BigDecimal("50000")));

        // Se cierra contando exactamente los 50.000 del abono: cuadra, sin faltante de efectivo.
        String cuerpo = "{\"cashDetail\":{\"bill100k\":0,\"bill50k\":1,\"bill20k\":0,\"bill10k\":0,\"bill5k\":0,"
                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                + "\"countedCard\":0,\"countedQr\":0,\"notes\":\"turno\",\"pettyCashExpenses\":[],\"baseForNextDay\":0}";
        JsonNode cierre = leer(mockMvc.perform(post("/api/closures").header("Authorization", bearer(CAJA, "cajero"))
                        .header("X-User-Name", "Caja").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk()));
        assertThat(cierre.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("50000");
        assertThat(cierre.get("shortages").has("Efectivo")).as(cierre.toString()).isFalse();
        assertThat(dueno.queryForObject("SELECT total_expected_cash FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .isEqualByComparingTo("50000");
        assertThat(dueno.queryForObject("SELECT sales_of_day FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .as("un abono no es venta").isZero();

        // El turno nuevo arranca sin el recaudo del anterior; anular aquel recibo resta aquí y no toca el cierre hecho.
        assertThat(preview().get("recaudoCarteraEfectivo").decimalValue()).isZero();
        mockMvc.perform(post("/api/cartera/recibos/" + efectivo + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        assertThat(preview().get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("-50000");
        assertThat(dueno.queryForObject("SELECT total_expected_cash FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .isEqualByComparingTo("50000");
    }

    // ================================================================ caras restrictivas

    private BigDecimal deudaDelOtro(String doc) {
        return dueno.queryForObject("SELECT COALESCE(sum(CASE WHEN type = 'DEBIT' THEN amount ELSE -amount END), 0) "
                + "FROM debt_transactions WHERE tenant_id = ? AND account_id = (SELECT id FROM accounts_receivable "
                + "WHERE tenant_id = ? AND customer_document = ?)", BigDecimal.class, OTRO, OTRO, doc);
    }

    @Test
    @DisplayName("🔴 escrituras cruzadas: un cliente, un recibo o un cupo de OTRO negocio no existen para este, y su libro no se toca")
    void escriturasCruzadas() throws Exception {
        // Un cliente que solo existe en el otro negocio, con su cuenta y un recibo suyo.
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES (?, '777', 'Solo del otro', 's')", OTRO);
        String cuentaAjena = cuenta(OTRO, "777", "Solo del otro", 100000, 40000);
        debito(OTRO, cuentaAjena, 40000, hoy.minusDays(3), hoy.plusDays(5), UUID.randomUUID());
        String reciboAjeno = dueno.queryForObject("""
                INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
                VALUES (?, 0, '777', 1000, 'EFECTIVO', now(), 'ajeno-1') RETURNING id::text""", String.class, OTRO);
        BigDecimal deudaAntes = deudaDelOtro("777");
        long recibosAntes = dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, OTRO);
        // Abrir su cuenta con cupo ya dejó un evento `cupo` (V66): se cuenta desde aquí.
        long eventosAntes = dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Long.class, OTRO);

        abonar(ADMIN, "admin", abono("777", 1000, "EFECTIVO", "cruzado-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("clienteDocumento"));
        mockMvc.perform(post("/api/cartera/recibos/" + reciboAjeno + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"DUPLICADO\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("id"));
        mockMvc.perform(put("/api/cartera/clientes/777/cupo").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cupo\":999999}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        mockMvc.perform(post("/api/cartera/clientes/777/insolvencia").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"desde\":\"" + hoy + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        mockMvc.perform(get("/api/cartera/clientes/777/estado-de-cuenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest());

        assertThat(deudaDelOtro("777")).isEqualByComparingTo(deudaAntes);
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, OTRO)).isEqualTo(recibosAntes);
        assertThat(dueno.queryForMap("SELECT credit_limit, total_debt FROM accounts_receivable WHERE tenant_id = ? AND customer_document = '777'", OTRO))
                .satisfies(m -> {
                    assertThat(new BigDecimal(m.get("credit_limit").toString())).isEqualByComparingTo("100000");
                    assertThat(new BigDecimal(m.get("total_debt").toString())).isEqualByComparingTo("40000");
                });
        assertThat(dueno.queryForObject("SELECT en_insolvencia_desde FROM clientes WHERE tenant_id = ? AND documento = '777'",
                java.sql.Date.class, OTRO)).isNull();
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Long.class, OTRO)).isEqualTo(eventosAntes);
        // Y en este negocio tampoco se escribió nada.
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 un token sin rol no lee ni escribe cartera: 403 en cada ruta")
    void sinRol() throws Exception {
        String sinRol = "Bearer " + Jwts.builder().subject(CAJA).claim("tenant_id", T)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        mockMvc.perform(get("/api/cartera/clientes").header("Authorization", sinRol)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cartera/clientes/" + TIENDA_A + "/estado-de-cuenta").header("Authorization", sinRol))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/cartera/recibos").header("Authorization", sinRol).contentType(MediaType.APPLICATION_JSON)
                .content(abono(TIENDA_A, 1000, "EFECTIVO", "sin-rol"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/cartera/resumen").header("Authorization", sinRol)).andExpect(status().isForbidden());
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 un vendedor cuyo token no es de ningún usuario no ve ni cobra nada (el servidor filtra por -1)")
    void vendedorFantasma() throws Exception {
        String fantasma = "fantasma@qa-cartera.invalid";
        mockMvc.perform(get("/api/cartera/clientes").header("Authorization", bearer(fantasma, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        abonar(fantasma, "vendedor", abono(TIENDA_A, 1000, "EFECTIVO", "fantasma-1")).andExpect(status().isBadRequest());
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 sin RLS (el dueño la salta): el filtro de negocio ESCRITO impide anular, cobrar o cambiar el cupo de otro negocio")
    void filtroExplicitoSinRls() {
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES (?, '777', 'Solo del otro', 's')", OTRO);
        String cuentaAjena = cuenta(OTRO, "777", "Solo del otro", 100000, 40000);
        debito(OTRO, cuentaAjena, 40000, hoy.minusDays(3), hoy.plusDays(5), UUID.randomUUID());
        UUID reciboAjeno = dueno.queryForObject("""
                INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key)
                VALUES (?, 0, '777', 1000, 'EFECTIVO', now(), 'ajeno-sin-rls') RETURNING id""", UUID.class, OTRO);
        // Con el dueño de la base RLS no aplica: solo queda lo que el código escribe.
        Cartera sinRls = new Cartera(dueno);
        Cartera.Quien admin = new Cartera.Quien(T, "admin", this.admin);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinRls.anular(admin, reciboAjeno, "DUPLICADO"))
                .isInstanceOf(com.suresell.orders.shared.exception.DatoInvalidoException.class)
                .satisfies(e -> assertThat(((com.suresell.orders.shared.exception.DatoInvalidoException) e).campo()).isEqualTo("id"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinRls.registrarRecibo(admin, new Cartera.NuevoRecibo(
                        "777", new BigDecimal("1000"), "EFECTIVO", null, null, null, "sin-rls-1", null, null)))
                .isInstanceOf(com.suresell.orders.shared.exception.DatoInvalidoException.class)
                // La primera capa (la cuenta, buscada con el negocio escrito), no la segunda (las facturas del negocio).
                .satisfies(e -> assertThat(((com.suresell.orders.shared.exception.DatoInvalidoException) e).campo())
                        .isEqualTo("clienteDocumento"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinRls.cambiarCupo(admin, "777", new BigDecimal("999999")))
                .isInstanceOf(com.suresell.orders.shared.exception.DatoInvalidoException.class)
                .satisfies(e -> assertThat(((com.suresell.orders.shared.exception.DatoInvalidoException) e).campo()).isEqualTo("documento"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE anula_recibo_id = ?", Long.class, reciboAjeno)).isZero();
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Long.class, T)).isZero();
        assertThat(dueno.queryForObject("SELECT credit_limit FROM accounts_receivable WHERE tenant_id = ? AND customer_document = '777'",
                BigDecimal.class, OTRO)).isEqualByComparingTo("100000");
    }
}
