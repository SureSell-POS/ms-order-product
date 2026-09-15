package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F4.11 (opción A de ECM) por la API real, con {@code app_user}: la
 * venta a crédito que hizo una caja a un cliente ya en insolvencia entra con su deuda y
 * queda por revisar (V72); sin caja, 409 como siempre. Y la lista y la decisión del admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class VentaAInsolventeTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-venta-insolvente";
    static final String OTRO = "qa-venta-insolvente-otro";
    static final String ADMIN = "admin@qa-venta-insolvente.invalid";
    static final String ADMIN_OTRO = "admin@qa-venta-insolvente-otro.invalid";
    static final String CAJA = "caja@qa-venta-insolvente.invalid";
    static final String INSOLVENTE = "901";
    static final String SOLVENTE = "902";
    static final String PRODUCTO = "arroz-x25-" + T;
    static final UUID TERMINAL = UUID.fromString("5a1e0000-0000-4000-8000-00000000f411");

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
    private long caja;

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
            dueno.update("DELETE FROM ventas_a_insolvente_resoluciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM ventas_a_insolvente WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM inventario_intenciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_delivery_tracking WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_item WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM orders WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM terminals WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        admin = usuario(ADMIN, T, "admin", "Admin");
        caja = usuario(CAJA, T, "cajero", "Caja");
        usuario(ADMIN_OTRO, OTRO, "admin", "Admin del otro");
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES (?, ?, 'Arroz x25', 90000, true)", PRODUCTO, T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por, en_insolvencia_desde) VALUES "
                + "(?, ?, 'Tienda en Ley 1116', 8, 's', (now() AT TIME ZONE 'America/Bogota')::date), "
                + "(?, ?, 'Tienda al día', 8, 's', NULL)", T, INSOLVENTE, T, SOLVENTE);
    }

    private long usuario(String email, String tenant, String rol, String nombre) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, tenant, rol, nombre);
    }

    private static int seq = 0;

    /** La venta a crédito del POS: con terminal si {@code conCaja}, como la manda su outbox. */
    private static String ventaACredito(String documento, String clave, boolean conCaja) {
        seq++;
        String procedencia = conCaja
                ? "\"terminalId\":\"" + TERMINAL + "\",\"epoch\":1,\"seq\":" + seq + ",\"ocurridoEn\":\"2026-09-15T08:00:00-05:00\","
                : "";
        return "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + (seq % 150 + 1) + "\",\"paymentMethod\":\"CREDITO\","
                + "\"clienteDocumento\":\"" + documento + "\"," + procedencia
                + "\"items\":[{\"productId\":\"" + PRODUCTO + "\",\"quantity\":2,\"unitPrice\":90000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions vender(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private int contar(String sql, Object... args) {
        return dueno.queryForObject(sql, Integer.class, args);
    }

    private JsonNode leer(String url, String email, String rol, String tenant) throws Exception {
        return json.readTree(mockMvc.perform(get(url).header("Authorization", bearer(email, rol, tenant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("🔴 F4.11: la caja le vende a crédito a un cliente ya en insolvencia: 201 con su DEBIT, marcada, y el reintento responde igual")
    void laCajaLeVendeAlInsolvente() throws Exception {
        vender(ventaACredito(INSOLVENTE, "caja-insolvente", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(true));

        UUID venta = dueno.queryForObject("SELECT uuid_id FROM orders WHERE tenant_id = ? AND idempotency_key = 'caja-insolvente'",
                UUID.class, T);
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ? AND order_uuid = ? AND type = 'DEBIT' "
                + "AND amount = 180000", T, venta)).isEqualTo(1);
        var marca = dueno.queryForMap("SELECT cliente_documento, total, terminal_id, operado_por, ocurrido_en, en_insolvencia_desde "
                + "FROM ventas_a_insolvente WHERE tenant_id = ? AND order_uuid = ?", T, venta);
        assertThat(marca).containsEntry("cliente_documento", INSOLVENTE).containsEntry("terminal_id", TERMINAL);
        assertThat(new BigDecimal(marca.get("total").toString())).isEqualByComparingTo("180000");
        assertThat(((Number) marca.get("operado_por")).longValue()).isEqualTo(caja);
        assertThat(marca.get("ocurrido_en")).isNotNull();

        // El outbox reintenta con la misma clave: misma respuesta, ni otra venta ni otra marca.
        vender(ventaACredito(INSOLVENTE, "caja-insolvente", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(true));
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ? AND cliente_documento = ?", T, INSOLVENTE)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM ventas_a_insolvente WHERE tenant_id = ?", T)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F4.11: la misma venta SIN terminal (API directa, panel): 409 CLIENTE_EN_INSOLVENCIA, sin venta, deuda ni marca")
    void sinCajaSeSigueRechazando() throws Exception {
        vender(ventaACredito(INSOLVENTE, "api-insolvente", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_EN_INSOLVENCIA"));
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ? AND idempotency_key = 'api-insolvente'", T)).isZero();
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ?", T)).isZero();
        assertThat(contar("SELECT count(*) FROM ventas_a_insolvente WHERE tenant_id = ?", T)).isZero();
    }

    @Test
    @DisplayName("control: la caja le vende a crédito a un cliente al día: revisionPorInsolvencia false y ninguna marca")
    void clienteAlDiaSinMarca() throws Exception {
        vender(ventaACredito(SOLVENTE, "caja-solvente", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(false));
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ? AND type = 'DEBIT'", T)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM ventas_a_insolvente WHERE tenant_id = ?", T)).isZero();
    }

    @Test
    @DisplayName("🔴 F4.11: Cuentas por cobrar lista la venta por revisar, el admin decide una vez, y el resumen avisa hasta entonces")
    void listaYDecision() throws Exception {
        vender(ventaACredito(INSOLVENTE, "caja-para-revisar", true)).andExpect(status().isCreated());

        JsonNode pendientes = leer("/api/cartera/ventas-a-insolvente", ADMIN, "admin", T);
        assertThat(pendientes).hasSize(1);
        JsonNode m = pendientes.get(0);
        assertThat(m.get("tipo").asText()).isEqualTo("VENTA_A_INSOLVENTE_POR_REVISAR");
        assertThat(m.get("clienteDocumento").asText()).isEqualTo(INSOLVENTE);
        assertThat(m.get("clienteNombre").asText()).isEqualTo("Tienda en Ley 1116");
        assertThat(m.get("idOrder").isNumber()).isTrue();
        assertThat(m.get("terminalId").asText()).isEqualTo(TERMINAL.toString());
        assertThat(m.get("operadoPor").asText()).isEqualTo("Caja");
        assertThat(m.get("resolucion").isNull()).isTrue();
        assertThat(leer("/api/cartera/resumen", ADMIN, "admin", T).get("ventasAInsolventePorRevisar").asLong()).isEqualTo(1);
        String id = m.get("id").asText();

        // Solo el admin la ve y la resuelve.
        mockMvc.perform(get("/api/cartera/ventas-a-insolvente").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isForbidden());
        // Otro negocio no la encuentra.
        mockMvc.perform(post("/api/cartera/ventas-a-insolvente/" + id + "/resolucion")
                        .header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DEJAR_COMO_DEUDA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("id"));
        assertThat(leer("/api/cartera/ventas-a-insolvente", ADMIN_OTRO, "admin", OTRO)).isEmpty();
        // Una decisión fuera del catálogo no entra.
        mockMvc.perform(post("/api/cartera/ventas-a-insolvente/" + id + "/resolucion").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ANULAR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("decision"));

        mockMvc.perform(post("/api/cartera/ventas-a-insolvente/" + id + "/resolucion").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"dejar_como_deuda\",\"nota\":\"Se reporta al promotor del acuerdo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolucion.decision").value("DEJAR_COMO_DEUDA"))
                .andExpect(jsonPath("$.resolucion.usuarioId").value(admin))
                .andExpect(jsonPath("$.resolucion.nota").value("Se reporta al promotor del acuerdo"));

        // La segunda decisión choca; la marca y la deuda siguen como estaban.
        mockMvc.perform(post("/api/cartera/ventas-a-insolvente/" + id + "/resolucion").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"COBRAR_DE_CONTADO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENTA_YA_RESUELTA"));
        assertThat(contar("SELECT count(*) FROM ventas_a_insolvente_resoluciones WHERE tenant_id = ?", T)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ? AND type = 'DEBIT'", T)).isEqualTo(1);

        assertThat(leer("/api/cartera/ventas-a-insolvente", ADMIN, "admin", T)).isEmpty();
        JsonNode todas = leer("/api/cartera/ventas-a-insolvente?pendientes=false", ADMIN, "admin", T);
        assertThat(todas).hasSize(1);
        assertThat(todas.get(0).get("resolucion").get("usuario").asText()).isEqualTo("Admin");
        assertThat(leer("/api/cartera/resumen", ADMIN, "admin", T).get("ventasAInsolventePorRevisar").asLong()).isZero();
    }
}
