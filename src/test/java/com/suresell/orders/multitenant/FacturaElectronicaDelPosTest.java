package com.suresell.orders.multitenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Marcar «Requiere factura electrónica» en el POS no puede tumbar la venta.
 *
 * <h2>Lo que se sospechaba, leído y no ejecutado</h2>
 *
 * `FACTUS-FACTURACION-ELECTRONICA.md` §7.1: el POS mete `facturaElectronica` en
 * el cuerpo de `POST /orders/create` —viaja el {@code StoredOrder} entero
 * ({@code db.ts:20-62}), que el outbox usa tal cual de payload
 * ({@code offline-order.repository.ts:104})—, y este servicio corre con
 * {@code fail-on-unknown-properties: true} ({@code application.yml:17},
 * {@code application-cloud.yml:26}) sobre un DTO que no declaraba ese campo.
 *
 * <p>Y el fallo no era solo visible: el outbox del POS clasifica los <b>4xx como
 * {@code FAILED} definitivo</b>, sin reintento
 * ({@code http-order-sync.gateway.ts:56-58}, {@code outbox-sync.engine.ts:33}).
 * Una venta rechazada así <b>no se sincroniza nunca</b>.
 *
 * <h2>Lo que fija esta prueba</h2>
 *
 * <ol>
 *   <li>El cuerpo exacto del POS <b>con</b> {@code facturaElectronica} crea la
 *       venta (201). Es la comprobación que pedía el documento.</li>
 *   <li>El dato <b>se guarda</b> con la orden: si el servidor lo aceptara y lo
 *       tirara, el día que haya que emitir no habría a nombre de quién.</li>
 *   <li>Sin el campo, todo sigue igual: la columna queda nula.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class FacturaElectronicaDelPosTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-factura";
    static final String PRODUCTO = "P-FACTURA";

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

    @Autowired
    MockMvc mockMvc;

    final ObjectMapper json = new ObjectMapper();

    private String bearer() {
        return "Bearer " + Jwts.builder()
                .claim("tenant_id", TENANT)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private Connection sinRls() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = sinRls(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM order_item");
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM tenant_order_counters WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM menu_products");
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + TENANT
                    + "', 'Negocio con factura', 'pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                    + "VALUES ('" + PRODUCTO + "', '" + TENANT + "', 'Hamburguesa', 25000, true)");
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                    + "VALUES ('" + TENANT + "', 'Principal', 'PRINCIPAL', 'DIRECTO', true)");
        }
    }

    /**
     * El cuerpo que manda HOY el POS al marcar factura: el {@code StoredOrder}
     * completo. Se deja literal, con todos sus campos, porque el valor de esta
     * prueba está justamente en que sea el de verdad y no una versión mínima.
     */
    private String cuerpoDelPos(String clave, String bloqueFactura) {
        return """
                {
                  "idempotencyKey": "%s",
                  "tenantId": "%s",
                  "idOrder": null,
                  "createdAt": "2026-09-11T18:05:11.000Z",
                  "paymentMethod": "CASH",
                  "subtotal": 25000,
                  "total": 25000,
                  "discountCode": null,
                  "discountAmount": 0,
                  "pagerColor": null,
                  "pagerNumber": null,
                  "items": [{"productId": "%s", "quantity": 1, "unitPrice": 25000}],
                  "payments": null,
                  "tableSessionId": null,
                  "preparadoEnComanda": false,
                  "status": "pending",
                  "synced": false%s
                }
                """.formatted(clave, TENANT, PRODUCTO, bloqueFactura);
    }

    private static final String FACTURA = """
            ,
              "facturaElectronica": {
                "nombre": "Distribuciones del Valle SAS",
                "documento": "901234567-8",
                "correo": "facturacion@distrivalle.co",
                "telefono": "3001234567"
              }""";

    private MvcResult crear(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
    }

    private String facturaGuardada(long idOrder) throws Exception {
        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT factura_electronica FROM orders WHERE id_order = "
                     + idOrder + " AND tenant_id = '" + TENANT + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    // =================================================================

    @Test
    @DisplayName("🔴 una venta con «Requiere factura electrónica» marcada se crea (no 400)")
    void laVentaConFacturaSeCrea() throws Exception {
        MvcResult r = crear(cuerpoDelPos("f0000000-0000-4000-8000-000000000001", FACTURA));

        // Si esto sale 400, el POS marca el evento del outbox como FAILED y esa
        // venta no se sincroniza NUNCA: no es un error que se reintente solo.
        assertEquals(201, r.getResponse().getStatus(),
                "el cuerpo con facturaElectronica tiene que crear la venta; respuesta: "
                        + r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("🔴 el cliente de la factura se GUARDA con la orden, no se descarta")
    void elClienteDeLaFacturaSeGuarda() throws Exception {
        MvcResult r = crear(cuerpoDelPos("f0000000-0000-4000-8000-000000000002", FACTURA));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        long idOrder = json.readTree(r.getResponse().getContentAsString()).get("idOrder").asLong();

        String guardado = facturaGuardada(idOrder);
        assertNotNull(guardado, "aceptar el campo y tirarlo sería peor que rechazarlo: "
                + "el día que haya que emitir no habría a nombre de quién");
        JsonNode factura = json.readTree(guardado);
        assertEquals("Distribuciones del Valle SAS", factura.get("nombre").asText());
        assertEquals("901234567-8", factura.get("documento").asText());
        assertEquals("facturacion@distrivalle.co", factura.get("correo").asText());
        assertEquals("3001234567", factura.get("telefono").asText());
    }

    @Test
    @DisplayName("sin el campo, la venta de siempre no cambia: la columna queda nula")
    void sinFacturaLaVentaNoCambia() throws Exception {
        MvcResult r = crear(cuerpoDelPos("f0000000-0000-4000-8000-000000000003", ""));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        long idOrder = json.readTree(r.getResponse().getContentAsString()).get("idOrder").asLong();

        assertEquals(null, facturaGuardada(idOrder),
                "un nulo honesto: esta venta no pidió factura");
    }

    @Test
    @DisplayName("el teléfono es opcional, como en el formulario del POS")
    void elTelefonoEsOpcional() throws Exception {
        String sinTelefono = """
                ,
                  "facturaElectronica": {
                    "nombre": "Ana Perez",
                    "documento": "1090123456",
                    "correo": "ana@correo.co"
                  }""";
        MvcResult r = crear(cuerpoDelPos("f0000000-0000-4000-8000-000000000004", sinTelefono));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        long idOrder = json.readTree(r.getResponse().getContentAsString()).get("idOrder").asLong();

        JsonNode factura = json.readTree(facturaGuardada(idOrder));
        assertEquals("Ana Perez", factura.get("nombre").asText());
        assertTrue(factura.get("telefono") == null || factura.get("telefono").isNull());
    }
}
