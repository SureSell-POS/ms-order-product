package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F1.3 por la API real (perfil cloud, RLS y disparadores de
 * verdad): la venta sabe quién la vendió, con qué condición, y la primera venta a
 * crédito de un cliente registrado abre su cuenta.
 *
 * <p>Es el contrato que desbloquea al POS (F1.8-F1.10): {@code vendedorId} y
 * {@code condicionPago} se ACEPTAN (antes, con {@code fail-on-unknown-properties},
 * eran 400 y venta perdida).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class VentaConVendedorYCreditoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-venta-vendedor";
    static final String OTRO = "qa-venta-vendedor-otro";
    static final String ANA = "ana@qa-venta-vendedor.invalid";
    static final String LUIS = "luis@qa-venta-vendedor.invalid";
    static final String DOC = "900123456";

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
    private JdbcTemplate dueno;
    private long ana;
    private long luis;
    private long ajeno;

    private static String bearer(String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", role)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static int rastreador = 0;

    private static String venta(String extra, String clave) {
        rastreador = rastreador % 150 + 1;
        return "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + rastreador + "\"," + extra
                + "\"items\":[{\"productId\":\"aceite-x12-" + T + "\",\"quantity\":3,\"unitPrice\":1}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_item WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM orders WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        ana = dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, 'vendedor', 'Ana') RETURNING id", Long.class, ANA, T);
        luis = dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, 'cajero', 'Luis') RETURNING id", Long.class, LUIS, T);
        ajeno = dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role) "
                + "VALUES ('ajeno@otro.invalid', '!', ?, 'vendedor') RETURNING id", Long.class, OTRO);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES (?, ?, 'Aceite x12', 112000, true)", "aceite-x12-" + T, T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, telefono, creado_por) "
                + "VALUES (?, ?, 'Tienda La Esquina', '3001234567', 'semilla')", T, DOC);
    }

    private Map<String, Object> laVenta(String clave) {
        return dueno.queryForMap("SELECT vendedor_id, created_by, condicion_pago, origen, payment_method, "
                + "cliente_documento, excede_cupo, total FROM orders WHERE tenant_id = ? AND idempotency_key = ?", T, clave);
    }

    @Test
    @DisplayName("🔴 la cajera registra a crédito una venta de Ana: vendedor Ana, operó Luis, CREDITO, caja, y la cuenta se abre")
    void ventaACreditoAtribuidaAUnVendedor() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CREDITO\",\"clienteDocumento\":\"" + DOC + "\","
                                + "\"vendedorId\":" + ana + ",\"condicionPago\":\"CREDITO\",", "credito-1")))
                .andExpect(status().isCreated());

        Map<String, Object> v = laVenta("credito-1");
        assertThat(((Number) v.get("vendedor_id")).longValue()).isEqualTo(ana);
        assertThat(((Number) v.get("created_by")).longValue()).isEqualTo(luis);
        assertThat(v).containsEntry("condicion_pago", "CREDITO").containsEntry("origen", "caja")
                .containsEntry("cliente_documento", DOC).containsEntry("excede_cupo", true);

        // La cuenta nació con cupo 0 y la deuda de la venta, y el débito está en el libro.
        Map<String, Object> cuenta = dueno.queryForMap(
                "SELECT credit_limit, total_debt, customer_name FROM accounts_receivable WHERE tenant_id = ? AND customer_document = ?",
                T, DOC);
        assertThat(new java.math.BigDecimal(cuenta.get("credit_limit").toString())).isZero();
        assertThat(new java.math.BigDecimal(cuenta.get("total_debt").toString()))
                .isEqualByComparingTo(new java.math.BigDecimal(v.get("total").toString()));
        assertThat(cuenta.get("customer_name")).isEqualTo("Tienda La Esquina");
        assertThat(dueno.queryForObject("SELECT count(*) FROM debt_transactions WHERE tenant_id = ? AND type = 'DEBIT'",
                Integer.class, T)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin los campos nuevos, la venta es como siempre: sin vendedor, CONTADO, caja")
    void loDeSiempreSigueIgual() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",", "contado-1")))
                .andExpect(status().isCreated());
        Map<String, Object> v = laVenta("contado-1");
        assertThat(v.get("vendedor_id")).isNull();
        assertThat(v).containsEntry("condicion_pago", "CONTADO").containsEntry("origen", "caja");
    }

    @Test
    @DisplayName("🔴 un vendedor vende a su nombre: sin vendedorId es él; con el de otro, 400 y nada escrito")
    void elVendedorVendeASuNombre() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(ANA, "vendedor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",", "de-ana")))
                .andExpect(status().isCreated());
        assertThat(((Number) laVenta("de-ana").get("vendedor_id")).longValue()).isEqualTo(ana);

        mockMvc.perform(post("/orders/create").header("Authorization", bearer(ANA, "vendedor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",\"vendedorId\":" + luis + ",", "ana-como-luis")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campo").value("vendedorId"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'ana-como-luis'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("🔴 un vendedor de otro negocio: 400 DATOS_INVALIDOS en vendedorId y nada escrito")
    void vendedorDeOtroNegocio() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",\"vendedorId\":" + ajeno + ",", "ajeno-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campo").value("vendedorId"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'ajeno-1'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("🔴 condición incoherente con el medio, o inventada: 400 en condicionPago")
    void condicionIncoherente() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",\"condicionPago\":\"CREDITO\",", "incoherente")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("condicionPago"));
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CASH\",\"condicionPago\":\"FIADO\",", "inventada")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("condicionPago"));
    }

    @Test
    @DisplayName("un documento que no está en clientes no abre cuenta: la venta a crédito se sigue negando")
    void clienteNoRegistradoNoAbreCuenta() throws Exception {
        int estado = mockMvc.perform(post("/orders/create").header("Authorization", bearer(LUIS, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(venta("\"paymentMethod\":\"CREDITO\",\"clienteDocumento\":\"111\",", "sin-cliente")))
                .andReturn().getResponse().getStatus();
        assertThat(estado).isGreaterThanOrEqualTo(400);
        assertThat(dueno.queryForObject("SELECT count(*) FROM accounts_receivable WHERE tenant_id = ?",
                Integer.class, T)).isZero();
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'sin-cliente'",
                Integer.class)).isZero();
    }
}
