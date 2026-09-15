package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * F4.5c por la API real: el preview del cierre y el cierre cuentan las MISMAS ventas por medio. Desde el multipago
 * (1eeb797, 2026-07-22) el cierre sumaba los pagos de las ventas MIXED y el preview las descartaba: con una venta
 * mixta en el turno, el esperado del preview no era el que cuadraba el cierre. La fila guardada siempre fue correcta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CierreConPagoMixtoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-cierre-mixto";
    static final String CAJA = "caja@qa-cierre-mixto.invalid";

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

    private static String bearer() {
        return "Bearer " + Jwts.builder().subject(CAJA).claim("tenant_id", T).claim("role", "cajero")
                .claim("modules", List.of("ventas", "cierre"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String tabla : new String[] {"public.inventario_intenciones", "order_payments", "order_delivery_tracking", "order_item", "orders",
                "tenant_order_counters", "daily_closures", "menu_products", "sites", "users"}) {
            dueno.update("DELETE FROM " + tabla + " WHERE tenant_id = ?", T);
        }
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Cierre mixto', 'pro') ON CONFLICT (id) DO NOTHING", T);
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, 'cajero', 'Caja')", CAJA, T);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                + "('mixto-hamburguesa', ?, 'Hamburguesa', 12000, true), ('mixto-gaseosa', ?, 'Gaseosa', 5000, true)", T, T);
    }

    private ResultActions vender(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode preview() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/closures/preview").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("🔴 F4.5c: con una venta mixta (10.000 efectivo + 14.000 tarjeta) el preview espera lo mismo que el cierre, y cerrar contando eso cuadra")
    void elPreviewCuentaLasVentasMixtas() throws Exception {
        vender("{\"pagerColor\":\"MESA\",\"pagerNumber\":\"1\",\"paymentMethod\":\"MIXED\",\"payments\":[{\"method\":\"CASH\",\"amount\":10000},"
                + "{\"method\":\"CARD\",\"amount\":14000}],\"items\":[{\"productId\":\"mixto-hamburguesa\",\"quantity\":2,\"unitPrice\":12000}],"
                + "\"idempotencyKey\":\"mixto-1\"}").andExpect(status().isCreated());
        vender("{\"pagerColor\":\"MESA\",\"pagerNumber\":\"2\",\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"mixto-gaseosa\",\"quantity\":1,"
                + "\"unitPrice\":5000}],\"idempotencyKey\":\"mixto-2\"}").andExpect(status().isCreated());

        JsonNode p = preview();
        assertThat(p.get("totalOrders").asInt()).isEqualTo(2);
        assertThat(p.get("totalExpectedCash").decimalValue()).as(p.toString()).isEqualByComparingTo("15000");
        assertThat(p.get("totalExpectedCard").decimalValue()).isEqualByComparingTo("14000");
        assertThat(p.get("totalExpectedQr").decimalValue()).isZero();
        assertThat(p.get("totalExpected").decimalValue()).isEqualByComparingTo("29000");

        // Se cierra contando exactamente lo que el preview dijo: 15.000 en efectivo (10.000 + 5.000) y 14.000 en tarjeta.
        String cuerpo = "{\"cashDetail\":{\"bill100k\":0,\"bill50k\":0,\"bill20k\":0,\"bill10k\":1,\"bill5k\":1,"
                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                + "\"countedCard\":14000,\"countedQr\":0,\"notes\":\"turno\",\"pettyCashExpenses\":[],\"baseForNextDay\":0}";
        JsonNode cierre = json.readTree(mockMvc.perform(post("/api/closures").header("Authorization", bearer()).header("X-User-Name", "Caja")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cierre.get("shortages").size()).as(cierre.toString()).isZero();
        assertThat(dueno.queryForObject("SELECT total_expected_cash FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .isEqualByComparingTo(p.get("totalExpectedCash").decimalValue());
        assertThat(dueno.queryForObject("SELECT total_expected_card FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .isEqualByComparingTo(p.get("totalExpectedCard").decimalValue());
    }
}
