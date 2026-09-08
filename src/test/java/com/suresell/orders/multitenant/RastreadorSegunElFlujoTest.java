package com.suresell.orders.multitenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V48 (ola 3): el rastreador es obligatorio SOLO donde el flujo de la sede lo
 * usa. Se mide de extremo a extremo, contra un Postgres con la cadena real:
 * la misma orden, en tres sedes con tres flujos.
 *
 * <p>Lo que protege: una droguería no tiene rastreadores y hasta hoy el
 * contrato le obligaba a inventar uno; y un POS viejo que lo siga mandando en
 * cualquier flujo tiene que seguir vendiendo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class RastreadorSegunElFlujoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-flujo";

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

    private String bearer() {
        return "Bearer " + Jwts.builder()
                .claim("tenant_id", TENANT)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM order_item");
            s.execute("DELETE FROM orders");
            // La primera venta crea el contador por sede (V28); sin quitarlo no
            // se puede quitar la sede para probar el siguiente flujo.
            s.execute("DELETE FROM tenant_order_counters WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM menu_products");
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + TENANT + "','F','pro') "
                    + "ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                    + "VALUES ('P-F','" + TENANT + "','Acetaminofén',10000,true)");
        }
    }

    private void sedeCon(String flujo) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                    + "VALUES ('" + TENANT + "', 'Principal', 'PRINCIPAL', '" + flujo + "', true)");
        }
    }

    private long ordenes() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM orders")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String orden(String pager, String clave) {
        return "{" + pager + "\"paymentMethod\":\"CASH\","
                + "\"items\":[{\"productId\":\"P-F\",\"quantity\":1,\"unitPrice\":10000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    private int crear(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn().getResponse().getStatus();
    }

    static final String SIN_PAGER = "";
    static final String PAGER_NULO = "\"pagerColor\":null,\"pagerNumber\":null,";
    static final String CON_PAGER = "\"pagerColor\":\"AMARILLO\",\"pagerNumber\":\"7\",";

    @Test
    @DisplayName("🔴 DIRECTO: la orden sin rastreador se registra")
    void directoVendeSinRastreador() throws Exception {
        sedeCon("DIRECTO");
        assertEquals(201, crear(orden(SIN_PAGER, "directo-1")));
        assertEquals(201, crear(orden(PAGER_NULO, "directo-2")));
        assertEquals(2, ordenes());
    }

    @Test
    @DisplayName("DIRECTO: un POS viejo que manda el rastreador igual sigue vendiendo, dos veces con el mismo")
    void directoAceptaElRastreadorDeUnPosViejo() throws Exception {
        sedeCon("DIRECTO");
        // En RASTREADOR la segunda daría 409 (rastreador ocupado); aquí no hay
        // rastreadores que ocupar.
        assertEquals(201, crear(orden(CON_PAGER, "directo-viejo-1")));
        assertEquals(201, crear(orden(CON_PAGER, "directo-viejo-2")));
        assertEquals(2, ordenes());
    }

    @Test
    @DisplayName("🔴 control negativo — RASTREADOR: sin rastreador es 400 y no deja fila; con él, 201")
    void rastreadorLoExige() throws Exception {
        sedeCon("RASTREADOR");
        assertEquals(400, crear(orden(SIN_PAGER, "rastreador-1")));
        assertEquals(400, crear(orden(PAGER_NULO, "rastreador-2")));
        assertEquals(0, ordenes(), "el servidor respondió 400 pero la orden quedó escrita");
        assertEquals(201, crear(orden(CON_PAGER, "rastreador-3")));
        assertEquals(1, ordenes());
    }

    @Test
    @DisplayName("sin sede (negocio recién registrado) se comporta como siempre: el rastreador es obligatorio")
    void sinSedeEsLoDeSiempre() throws Exception {
        assertEquals(400, crear(orden(SIN_PAGER, "sin-sede-1")));
        assertEquals(201, crear(orden(CON_PAGER, "sin-sede-2")));
    }

    @Test
    @DisplayName("el POS lee el flujo y su posMode legado del mismo endpoint de siempre")
    void elPosLeeElFlujo() throws Exception {
        sedeCon("DIRECTO");
        String cuerpo = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/account/sites/mode").header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(cuerpo)
                .contains("\"posMode\":\"PLAZOLETA\"")
                .contains("\"restaurante\":false")
                .contains("\"flujoDeVenta\":\"DIRECTO\"")
                .contains("\"usaMesas\":false")
                .contains("\"usaRastreador\":false");
    }
}
