package com.suresell.orders.multitenant;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La cola de «vendidos sin registrar»: líneas con product_id `sin-registrar:`
 * y el nombre (y el código entre corchetes) en `instructions`, agrupadas.
 * El otro negocio no ve nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class VentasSinRegistrarEndpointTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-sin-registrar";
    static final String OTRO = "otro-sin-registrar";

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

    private String bearer(String tenant) {
        return "Bearer " + Jwts.builder().claim("tenant_id", tenant)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private void linea(Statement s, String tenant, long orden, String productId, String instrucciones, int precio) throws Exception {
        // La orden y su línea van unidas por el UUID además del número (así las lee la entidad).
        UUID uuid = UUID.nameUUIDFromBytes((tenant + "-" + orden).getBytes(StandardCharsets.UTF_8));
        s.execute("INSERT INTO orders (uuid_id, tenant_id, id_order, total, status, payment_method) VALUES ('" + uuid
                + "','" + tenant + "'," + orden + "," + precio + ",'pagado','CASH') ON CONFLICT DO NOTHING");
        s.execute("INSERT INTO order_item (uuid_id, tenant_id, order_id, order_uuid_id, product_id, quantity, unit_price, total_price, instructions, created_at, precio_origen) "
                + "VALUES ('" + UUID.randomUUID() + "','" + tenant + "'," + orden + ",'" + uuid + "','" + productId + "',1," + precio + "," + precio
                + "," + (instrucciones == null ? "NULL" : "'" + instrucciones + "'") + ", now(), 'POS')");
    }

    @Test
    @DisplayName("🔴 agrupa por nombre, código y precio; cuenta las veces; el otro negocio no ve nada; una línea normal no entra")
    void colaAgrupada() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String t : new String[]{TENANT, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','S','pro') ON CONFLICT (id) DO NOTHING");
            }
            linea(s, TENANT, 9001, "sin-registrar:a1", "Galleta importada [7702001234561]", 3500);
            linea(s, TENANT, 9002, "sin-registrar:a2", "Galleta importada [7702001234561]", 3500);
            linea(s, TENANT, 9003, "sin-registrar:a3", "Bolsa hielo", 2000);
            linea(s, TENANT, 9004, "P-NORMAL", "sin cebolla", 15000);
            linea(s, OTRO, 9005, "sin-registrar:b1", "Ajeno", 100);
        }

        mockMvc.perform(get("/api/menu/sin-registrar").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].codigo").value("7702001234561"))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].veces").value(2))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].precio").value(3500.0))
                .andExpect(jsonPath("$[?(@.nombre == 'Bolsa hielo')].veces").value(1));

        // Y al leer la orden, la línea sin registrar sale con el nombre tecleado, no con el id.
        mockMvc.perform(get("/orders/9003").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("sin-registrar:a3"))
                .andExpect(jsonPath("$.items[0].nameProduct").value("Bolsa hielo"));

        mockMvc.perform(get("/api/menu/sin-registrar?dias=7").header("Authorization", bearer(OTRO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value("Ajeno"));
    }
}
