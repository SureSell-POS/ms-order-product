package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
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
 * Bloqueo #4 de la fase 0, lado servidor: una venta guardada con el negocio A no
 * se registra como de B porque se sincronice con la sesión de B.
 *
 * <p>Integridad de datos, no control de acceso: el negocio lo decide el token
 * igual. Lo que se mide es que la venta mal etiquetada no se escribe en NINGÚN
 * negocio, y que lo que hoy vende (sin {@code tenantId}, con el propio o con
 * {@code "demo"}) sigue vendiendo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class NegocioDeOtraSesionTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String A = "negocio-sesion-a";
    static final String B = "negocio-sesion-b";

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

    private static String bearer(String tenant) {
        return "Bearer " + Jwts.builder().claim("tenant_id", tenant)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String venta(String tenantIdDeclarado, String clave) {
        String tenant = tenantIdDeclarado == null ? "" : "\"tenantId\":\"" + tenantIdDeclarado + "\",";
        // Un rastreador por venta: con el mismo, la segunda chocaría con PAGER_OCUPADO.
        String rastreador = String.valueOf(Math.abs(clave.hashCode() % 150) + 1);
        return "{" + tenant + "\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + rastreador + "\",\"paymentMethod\":\"CASH\","
                + "\"items\":[{\"productId\":\"P-S-" + B + "\",\"quantity\":1,\"unitPrice\":10000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("DELETE FROM order_item");
        dueno.update("DELETE FROM orders");
        dueno.update("DELETE FROM menu_products WHERE tenant_id IN (?, ?)", A, B);
        for (String t : new String[] {A, B}) {
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES (?, ?, 'Hamburguesa', 10000, true)", "P-S-" + B, B);
    }

    private int ordenesDe(String tenant) {
        return dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ?", Integer.class, tenant);
    }

    @Test
    @DisplayName("🔴 una venta de A enviada con la sesión de B: 409 NEGOCIO_DE_OTRA_SESION y no se escribe en ninguno")
    void laVentaDeOtroNegocioNoSeEscribe() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(venta(A, "otra-sesion-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NEGOCIO_DE_OTRA_SESION"))
                .andExpect(jsonPath("$.error").value("NEGOCIO_DE_OTRA_SESION"))
                .andExpect(jsonPath("$.negocioDeLaVenta").value(A))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());
        assertThat(ordenesDe(B)).isZero();
        assertThat(ordenesDe(A)).isZero();

        // El reintento del POS viejo, con la misma clave: el mismo rechazo, y sigue sin escribirse nada.
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(venta(A, "otra-sesion-1")))
                .andExpect(status().isConflict());
        assertThat(ordenesDe(B)).isZero();
    }

    @Test
    @DisplayName("lo que vende hoy sigue vendiendo: con su propio negocio, sin tenantId y con 'demo'")
    void loLegitimoSigueVendiendo() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(venta(B, "propia")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(venta(null, "sin-tenant")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(venta("demo", "demo")))
                .andExpect(status().isCreated());
        assertThat(ordenesDe(B)).isEqualTo(3);
    }
}
