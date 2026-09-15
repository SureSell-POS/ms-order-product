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
import java.nio.charset.StandardCharsets;
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
 * Plan de mayoristas F4.13, corte (c), por la API real con {@code app_user}, sobre V77: el crédito después del inicio del
 * proceso de insolvencia. Habilitado, la venta a crédito entra (caja, API) sin la marca de F4.11, con vencimiento acotado y
 * la respuesta lo dice; en liquidación deja de valer y no se habilita; la ficha del mayorista (la que lee la caja) y el
 * estado de cuenta lo muestran.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CreditoPosteriorTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-credito-posterior";
    static final String ADMIN = "admin@qa-credito-posterior.invalid";
    static final String CAJA = "caja@qa-credito-posterior.invalid";
    static final String TIENDA = "931";
    static final String AL_DIA = "932";
    static final String PRODUCTO = "arroz-x25-" + T;
    static final UUID TERMINAL = UUID.fromString("5a1e0000-0000-4000-8000-00000000f413");

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
    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));

    private static String bearer(String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("DELETE FROM insolvencia_credito_posterior WHERE tenant_id = ?", T);
        ProcesoDeInsolvenciaTest.limpiarInsolvencia(dueno, T);
        dueno.update("DELETE FROM cartera_aplicaciones WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM ventas_a_insolvente_resoluciones WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM ventas_a_insolvente WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM inventario_intenciones WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM order_delivery_tracking WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM order_item WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM orders WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM terminals WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM users WHERE tenant_id = ?", T);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", T, T);
        usuario(ADMIN, "admin", "Admin");
        usuario(CAJA, "cajero", "Caja");
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES (?, ?, 'Arroz x25', 90000, true)", PRODUCTO, T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda en proceso', 20, 's'), (?, ?, 'Tienda al día', 8, 's')", T, TIENDA, T, AL_DIA);
    }

    private void usuario(String email, String rol, String nombre) {
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, ?, ?)", email, T, rol, nombre);
    }

    private static int seq = 0;

    private static String ventaACredito(String documento, String clave, boolean conCaja) {
        seq++;
        String procedencia = conCaja
                ? "\"terminalId\":\"" + TERMINAL + "\",\"epoch\":1,\"seq\":" + seq + ",\"ocurridoEn\":\"" + java.time.OffsetDateTime.now() + "\","
                : "";
        return "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + (seq % 150 + 1) + "\",\"paymentMethod\":\"CREDITO\","
                + "\"clienteDocumento\":\"" + documento + "\"," + procedencia
                + "\"items\":[{\"productId\":\"" + PRODUCTO + "\",\"quantity\":1,\"unitPrice\":90000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    private ResultActions vender(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private void etapa(String etapa) throws Exception {
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"etapa\":\"" + etapa + "\",\"fecha\":\"" + hoy + "\",\"documento\":\"Auto\",\"informadoPor\":\"abogado\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions credito(String email, String rol, String doc, String cuerpo) throws Exception {
        return mockMvc.perform(put("/api/cartera/clientes/" + doc + "/insolvencia/credito-posterior").header("Authorization", bearer(email, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions credito(String cuerpo) throws Exception {
        return credito(ADMIN, "admin", TIENDA, cuerpo);
    }

    private JsonNode leer(String url) throws Exception {
        return json.readTree(mockMvc.perform(get(url).header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int contar(String sql, Object... args) {
        return dueno.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("🔴 (c) habilitado: la venta a crédito sin caja entra sin marca, vence acotada a 8 y dice ventaPosteriorAlInicio; deshabilitado, vuelve el 409 y la caja se marca")
    void habilitarYVender() throws Exception {
        etapa("INICIO");
        vender(ventaACredito(TIENDA, "c-sin-habilitar", false)).andExpect(ConflictoConMensaje.de("CLIENTE_EN_INSOLVENCIA"))
                .andExpect(jsonPath("$.message").value("Cliente en proceso de insolvencia: véndele de contado, o habilita el crédito para este cliente."));

        JsonNode habilitado = json.readTree(credito("{\"habilitado\":true,\"motivo\":\"Acuerdo con el promotor\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(habilitado.get("creditoPosterior").get("habilitado").asBoolean()).isTrue();
        assertThat(habilitado.get("creditoPosterior").get("plazoMaximoDias").asInt()).as("8 si no llega").isEqualTo(8);
        assertThat(habilitado.get("creditoPosterior").get("registradoPor").asText()).isEqualTo("Admin");

        vender(ventaACredito(TIENDA, "c-habilitada", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ventaPosteriorAlInicio").value(true))
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(false))
                .andExpect(jsonPath("$.venceEl").value(hoy.plusDays(8).toString()));
        assertThat(contar("SELECT count(*) FROM ventas_a_insolvente WHERE tenant_id = ?", T)).isZero();
        // El reintento responde igual.
        vender(ventaACredito(TIENDA, "c-habilitada", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ventaPosteriorAlInicio").value(true)).andExpect(jsonPath("$.venceEl").value(hoy.plusDays(8).toString()));

        // Lo que lee la caja: la ficha y la lista del mayorista; en_insolvencia_desde igual que siempre.
        JsonNode ficha = leer("/api/mayorista/clientes/" + TIENDA);
        assertThat(ficha.get("en_insolvencia_desde").asText()).isEqualTo(hoy.toString());
        assertThat(ficha.get("insolvencia").get("enProceso").asBoolean()).isTrue();
        assertThat(ficha.get("insolvencia").get("etapa").asText()).isEqualTo("INICIO");
        assertThat(ficha.get("insolvencia").get("creditoPosterior").get("habilitado").asBoolean()).isTrue();
        assertThat(ficha.get("insolvencia").get("creditoPosterior").get("plazoMaximoDias").asInt()).isEqualTo(8);
        JsonNode lista = leer("/api/mayorista/clientes");
        for (JsonNode c : lista) {
            if (c.get("documento").asText().equals(TIENDA)) {
                assertThat(c.get("insolvencia").get("creditoPosterior").get("habilitado").asBoolean()).isTrue();
            } else {
                assertThat(c.get("insolvencia").isNull()).as("sin proceso: null").isTrue();
            }
        }
        assertThat(leer("/api/cartera/clientes/" + TIENDA + "/estado-de-cuenta").get("insolvencia").get("creditoPosterior")
                .get("plazoMaximoDias").asInt()).isEqualTo(8);

        // Deshabilitado: la API vuelve al 409 y la caja entra marcada, sin la leyenda.
        credito("{\"habilitado\":false,\"plazoMaximoDias\":8,\"motivo\":\"Se incumplio\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.creditoPosterior.habilitado").value(false));
        vender(ventaACredito(TIENDA, "c-deshabilitada", false)).andExpect(ConflictoConMensaje.de("CLIENTE_EN_INSOLVENCIA"));
        // La caja sin red pudo creerla habilitada e imprimir el vencimiento acotado: el servidor guarda y responde el SUYO
        // (el plazo del cliente, 20 días), para que la caja avise la diferencia (ECM, 2026-09-15). La venta no admite
        // que la caja mande un vencimiento (un venceEl en el cuerpo es 400), así que no hay otro que el del servidor.
        vender(ventaACredito(TIENDA, "c-caja-con-vencimiento", true).replace("\"items\"", "\"venceEl\":\"" + hoy.plusDays(8) + "\",\"items\""))
                .andExpect(status().isBadRequest());
        vender(ventaACredito(TIENDA, "c-caja", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(true))
                .andExpect(jsonPath("$.ventaPosteriorAlInicio").value(false))
                .andExpect(jsonPath("$.venceEl").value(hoy.plusDays(20).toString()));
        assertThat(dueno.queryForObject("SELECT d.vence_el FROM debt_transactions d JOIN orders o ON o.uuid_id = d.order_uuid "
                + "WHERE o.tenant_id = ? AND o.idempotency_key = 'c-caja' AND d.type = 'DEBIT'", java.sql.Date.class, T).toLocalDate())
                .isEqualTo(hoy.plusDays(20));
        assertThat(leer("/api/mayorista/clientes/" + TIENDA).get("insolvencia").get("creditoPosterior").get("plazoMaximoDias").isNull()).isTrue();
    }

    @Test
    @DisplayName("🔴 (c) en liquidación el crédito habilitado deja de valer sin escribir nada, no se vuelve a habilitar y la venta dice «Cliente en liquidación»")
    void enLiquidacion() throws Exception {
        etapa("INICIO");
        credito("{\"habilitado\":true,\"plazoMaximoDias\":15,\"motivo\":\"Acuerdo\"}").andExpect(status().isOk());
        etapa("LIQUIDACION");
        JsonNode ficha = leer("/api/mayorista/clientes/" + TIENDA);
        assertThat(ficha.get("insolvencia").get("etapa").asText()).isEqualTo("LIQUIDACION");
        assertThat(ficha.get("insolvencia").get("creditoPosterior").get("habilitado").asBoolean()).isFalse();
        vender(ventaACredito(TIENDA, "l-venta", false)).andExpect(ConflictoConMensaje.de("CLIENTE_EN_INSOLVENCIA"))
                .andExpect(jsonPath("$.message").value("Cliente en liquidación: véndele de contado."));
        credito("{\"habilitado\":true,\"motivo\":\"Otra vez\"}").andExpect(ConflictoConMensaje.de("CREDITO_POSTERIOR_EN_LIQUIDACION"))
                .andExpect(jsonPath("$.message").value("En liquidación no se habilita el crédito: véndele de contado. No se registró nada."));
        credito("{\"habilitado\":false,\"motivo\":\"Cierre\"}").andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM insolvencia_credito_posterior WHERE tenant_id = ?", T)).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 (c) habilitar pide proceso iniciado, plazo 0..30, motivo y admin")
    void validaciones() throws Exception {
        credito("{\"habilitado\":true,\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("SIN_PROCESO_EN_CURSO"))
                .andExpect(jsonPath("$.message").value("Solo se habilita el crédito cuando el proceso ya inició; antes, se le vende "
                        + "como a cualquier cliente. No se registró nada."));
        etapa("SOLICITUD");
        credito("{\"habilitado\":true,\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("SIN_PROCESO_EN_CURSO"));
        assertThat(leer("/api/mayorista/clientes/" + TIENDA).get("insolvencia").get("enProceso").asBoolean()).isFalse();
        etapa("INICIO");
        credito("{\"habilitado\":true,\"plazoMaximoDias\":31,\"motivo\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("plazoMaximoDias")).andExpect(jsonPath("$.message").value("El plazo máximo es de 0 a 30 días."));
        credito("{\"habilitado\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        credito("{\"motivo\":\"x\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("habilitado"));
        credito(CAJA, "cajero", TIENDA, "{\"habilitado\":true,\"motivo\":\"x\"}").andExpect(status().isForbidden());
        assertThat(contar("SELECT count(*) FROM insolvencia_credito_posterior WHERE tenant_id = ?", T)).isZero();
        assertThat(leer("/api/mayorista/clientes/" + AL_DIA).get("insolvencia").isNull()).isTrue();
    }
}
