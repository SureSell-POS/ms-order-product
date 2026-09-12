package com.suresell.orders.multitenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * El agujero de la edición de órdenes, cerrado y medido contra Postgres real.
 *
 * <h2>Qué había</h2>
 *
 * Medido en ANULAR-ORDENES-Y-AUTORIZACION-POR-TARJETA.md §1.4: {@code PUT
 * /orders/{id}} y {@code PATCH /orders/{id}/apply-discount} <b>no exigían
 * ningún rol</b> —no hay Spring Security en este servicio y estos dos endpoints
 * no eran de los siete que leen el rol del JWT a mano— y el único freno a la
 * edición era el reloj: 7 minutos desde {@code created_at}, <b>sin mirar el
 * estado de la orden ni si la caja ya había cerrado</b>. Traducido: un cajero
 * podía cambiarle los ítems y el total a una venta ya cobrada; y con
 * {@code apply-discount} podía cambiarle el total <b>sin dejar una sola fila de
 * rastro</b>.
 *
 * <h2>Qué fija esta prueba</h2>
 *
 * <ol>
 *   <li>Una orden <b>cobrada</b> no se edita, ni en el minuto cero: 403
 *       {@code ORDEN_YA_COBRADA}. Corregir una venta cobrada es el camino de
 *       anulación, que es otro trabajo y deja otro rastro.</li>
 *   <li>Una orden <b>abierta</b> (mesa, consumo en curso) se sigue editando.</li>
 *   <li>Editar y descontar son de <b>administrador</b>: un cajero recibe 403
 *       {@code SOLO_ADMINISTRADOR} en los dos.</li>
 *   <li>Un descuento escribe en {@code order_edit_history} quién, cuándo, qué
 *       había y qué quedó.</li>
 *   <li>{@code POST /api/orders/{id}/restore} <b>funciona</b> —fallaba siempre
 *       por construir la auditoría sin {@code order_uuid_id}, que es
 *       {@code NOT NULL}—, deja rastro, y un cajero no puede.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class OrdenCobradaNoSeEditaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-edicion";
    static final String ADMIN = "admin@negocio-edicion.co";
    static final String CAJERO = "cajero@negocio-edicion.co";
    static final String PRODUCTO = "P-EDICION";

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

    /** Token con negocio, rol y sujeto: el sujeto es lo que convierte la firma en un `users.id`. */
    private String bearer(String email, String rol) {
        return "Bearer " + Jwts.builder()
                .subject(email)
                .claim("tenant_id", TENANT)
                .claim("role", rol)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private String comoAdmin() {
        return bearer(ADMIN, "admin");
    }

    private String comoCajero() {
        return bearer(CAJERO, "cajero");
    }

    private Connection sinRls() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = sinRls(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM order_edit_history");
            s.execute("DELETE FROM order_deletions");
            s.execute("DELETE FROM discount_usage");
            s.execute("DELETE FROM coupon_product");
            s.execute("DELETE FROM discount_coupon");
            s.execute("DELETE FROM order_item");
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM tenant_order_counters WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM menu_products");
            s.execute("DELETE FROM users WHERE tenant_id = '" + TENANT + "'");
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + TENANT
                    + "', 'Negocio de edicion', 'pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO users (email, password_hash, tenant_id, role) VALUES "
                    + "('" + ADMIN + "', 'no-se-usa-en-esta-prueba', '" + TENANT + "', 'admin'), "
                    + "('" + CAJERO + "', 'no-se-usa-en-esta-prueba', '" + TENANT + "', 'cajero')");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                    + "VALUES ('" + PRODUCTO + "', '" + TENANT + "', 'Gaseosa', 5000, true)");
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                    + "VALUES ('" + TENANT + "', 'Principal', 'PRINCIPAL', 'DIRECTO', true)");
            // Cupón GENERAL (sin productos asociados) del 10 %: aplica a toda la orden.
            s.execute("INSERT INTO discount_coupon (tenant_id, code, name, discount_percentage, is_active) "
                    + "VALUES ('" + TENANT + "', 'DESC10', 'Diez por ciento', 10, true)");
        }
    }

    /** Una venta de mostrador: nace COBRADA (flujo DIRECTO). Devuelve su número. */
    private long crearVenta(String clave) throws Exception {
        String cuerpo = "{\"paymentMethod\":\"CASH\","
                + "\"items\":[{\"productId\":\"" + PRODUCTO + "\",\"quantity\":1,\"unitPrice\":5000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
        MvcResult r = mockMvc.perform(post("/orders/create")
                        .header("Authorization", comoAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString()).get("idOrder").asLong();
    }

    /**
     * Deja la orden en estado `abierta`, que es como nace una cuenta de mesa.
     * Se hace por SQL a propósito: lo que se está probando es la regla de
     * estado, no el camino por el que la orden llegó a ese estado.
     */
    private void abrir(long idOrder) throws Exception {
        try (Connection c = sinRls(); Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("UPDATE orders SET status = 'abierta' WHERE id_order = "
                    + idOrder + " AND tenant_id = '" + TENANT + "'"));
        }
    }

    private MvcResult editar(long idOrder, String autorizacion, int cantidad) throws Exception {
        return mockMvc.perform(put("/orders/" + idOrder)
                        .header("Authorization", autorizacion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + PRODUCTO
                                + "\",\"quantity\":" + cantidad + ",\"unitPrice\":5000}]}"))
                .andReturn();
    }

    private MvcResult descontar(long idOrder, String autorizacion) throws Exception {
        return mockMvc.perform(patch("/orders/" + idOrder + "/apply-discount")
                        .header("Authorization", autorizacion)
                        .param("discountCode", "DESC10"))
                .andReturn();
    }

    private String columnaDeLaOrden(long idOrder, String columna) throws Exception {
        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + columna + " FROM orders WHERE id_order = "
                     + idOrder + " AND tenant_id = '" + TENANT + "'")) {
            assertTrue(rs.next(), "la orden #" + idOrder + " debería existir");
            return rs.getString(1);
        }
    }

    // =================================================================
    // 1 · Una venta cobrada no se edita, ni en el minuto cero.
    // =================================================================

    @Test
    @DisplayName("🔴 una orden ya cobrada no se edita aunque no hayan pasado los 7 minutos")
    void unaOrdenCobradaNoSeEditaAunqueEsteDentroDeLosSieteMinutos() throws Exception {
        long idOrder = crearVenta("cobrada-1");
        assertEquals("pagado", columnaDeLaOrden(idOrder, "status"),
                "una venta de mostrador nace cobrada");

        MvcResult r = editar(idOrder, comoAdmin(), 5);

        // Recién creada: el reloj de los 7 minutos NO la habría frenado. La
        // frena el estado, que es la regla nueva.
        assertEquals(403, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode cuerpo = json.readTree(r.getResponse().getContentAsString());
        assertEquals("ORDEN_YA_COBRADA", cuerpo.get("error").asText());
        assertEquals("ORDEN_YA_COBRADA", cuerpo.get("codigo").asText());
        assertNotNull(cuerpo.get("mensaje"), "el contrato de errores lleva `mensaje`");
        assertTrue(cuerpo.get("mensaje").asText().toLowerCase().contains("cobrada"));

        // Y no cambió nada: ni el total ni las líneas.
        assertEquals(0, new java.math.BigDecimal(columnaDeLaOrden(idOrder, "total"))
                .compareTo(new java.math.BigDecimal("5000")));
        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM order_edit_history WHERE order_id = " + idOrder)) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "un rechazo no escribe historial");
        }
    }

    // =================================================================
    // 2 · Una orden abierta (mesa) se sigue editando.
    // =================================================================

    @Test
    @DisplayName("una orden abierta sí se edita, y deja su rastro de siempre")
    void unaOrdenAbiertaSeSigueEditando() throws Exception {
        long idOrder = crearVenta("abierta-1");
        abrir(idOrder);

        MvcResult r = editar(idOrder, comoAdmin(), 3);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());

        assertEquals(0, new java.math.BigDecimal(columnaDeLaOrden(idOrder, "total"))
                .compareTo(new java.math.BigDecimal("15000")), "3 × 5000");
        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT edit_type, old_total, new_total, edited_by "
                     + "FROM order_edit_history WHERE order_id = " + idOrder)) {
            assertTrue(rs.next(), "la edición de una orden abierta deja historial");
            assertEquals("ITEM_QUANTITY_CHANGED", rs.getString("edit_type"));
            assertEquals(0, rs.getBigDecimal("old_total").compareTo(new java.math.BigDecimal("5000")));
            assertEquals(0, rs.getBigDecimal("new_total").compareTo(new java.math.BigDecimal("15000")));
            assertNotNull(rs.getObject("edited_by"), "y dice quién editó");
        }
    }

    // =================================================================
    // 3 · Editar y descontar son de administrador.
    // =================================================================

    @Test
    @DisplayName("🔴 un cajero no puede editar ni aplicar descuentos; un admin sí")
    void elCajeroNoEditaNiDescuentaYElAdminSi() throws Exception {
        long abierta = crearVenta("rol-1");
        abrir(abierta);
        long cobrada = crearVenta("rol-2");

        // Editar, de cajero → 403 con el contrato de errores.
        MvcResult rechazo = editar(abierta, comoCajero(), 4);
        assertEquals(403, rechazo.getResponse().getStatus());
        JsonNode cuerpo = json.readTree(rechazo.getResponse().getContentAsString());
        assertEquals("SOLO_ADMINISTRADOR", cuerpo.get("error").asText());
        assertEquals("SOLO_ADMINISTRADOR", cuerpo.get("codigo").asText());
        assertTrue(cuerpo.get("mensaje").asText().contains("administrador"));
        assertEquals(0, new java.math.BigDecimal(columnaDeLaOrden(abierta, "total"))
                .compareTo(new java.math.BigDecimal("5000")), "el cajero no cambió nada");

        // Descontar, de cajero → 403. Es el endpoint que cambia el total de una
        // venta cobrada, y era el que no pedía nada.
        MvcResult rechazoDescuento = descontar(cobrada, comoCajero());
        assertEquals(403, rechazoDescuento.getResponse().getStatus());
        assertEquals("SOLO_ADMINISTRADOR",
                json.readTree(rechazoDescuento.getResponse().getContentAsString()).get("error").asText());
        assertNotNull(columnaDeLaOrden(cobrada, "total"));
        assertEquals(0, new java.math.BigDecimal(columnaDeLaOrden(cobrada, "total"))
                .compareTo(new java.math.BigDecimal("5000")));

        // Y de administrador, los dos pasan.
        assertEquals(200, editar(abierta, comoAdmin(), 4).getResponse().getStatus());
        assertEquals(200, descontar(cobrada, comoAdmin()).getResponse().getStatus());
    }

    // =================================================================
    // 4 · El descuento deja rastro.
    // =================================================================

    @Test
    @DisplayName("🔴 aplicar un descuento deja fila con quién, cuándo, el antes y el después")
    void elDescuentoDejaRastroConElAntesYElDespues() throws Exception {
        long idOrder = crearVenta("descuento-1");

        MvcResult r = descontar(idOrder, comoAdmin());
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(0, new java.math.BigDecimal(columnaDeLaOrden(idOrder, "total"))
                .compareTo(new java.math.BigDecimal("4500")), "5000 menos el 10 %");

        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT h.edit_type, h.old_total, h.new_total, h.discount_code, h.edited_at, "
                     + "u.email AS autor FROM order_edit_history h "
                     + "LEFT JOIN users u ON u.id = h.edited_by "
                     + "WHERE h.order_id = " + idOrder)) {
            assertTrue(rs.next(), "un descuento tiene que escribir una fila; antes no escribía ninguna");
            assertEquals("DISCOUNT_APPLIED", rs.getString("edit_type"));
            assertEquals(0, rs.getBigDecimal("old_total").compareTo(new java.math.BigDecimal("5000")),
                    "qué había");
            assertEquals(0, rs.getBigDecimal("new_total").compareTo(new java.math.BigDecimal("4500")),
                    "qué quedó");
            assertEquals("DESC10", rs.getString("discount_code"));
            assertNotNull(rs.getTimestamp("edited_at"), "cuándo");
            assertEquals(ADMIN, rs.getString("autor"), "quién");
            assertTrue(!rs.next(), "una sola fila por descuento");
        }
    }

    // =================================================================
    // 5 · Restaurar una orden borrada.
    // =================================================================

    @Test
    @DisplayName("🔴 restaurar una orden borrada la devuelve, deja rastro, y el cajero no puede")
    void restaurarDevuelveLaOrdenDejaRastroYElCajeroNoPuede() throws Exception {
        long idOrder = crearVenta("restaurar-1");
        String uuid = columnaDeLaOrden(idOrder, "uuid_id");

        assertEquals(200, mockMvc.perform(delete("/api/orders/" + idOrder)
                        .header("Authorization", comoAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"se borró por error\"}"))
                .andReturn().getResponse().getStatus());
        assertTrue(mockMvc.perform(get("/orders/" + idOrder).header("Authorization", comoAdmin()))
                .andReturn().getResponse().getStatus() != 200,
                "borrada, ya no se ve por el camino normal");

        // El cajero no restaura.
        MvcResult rechazo = mockMvc.perform(post("/api/orders/" + idOrder + "/restore")
                        .header("Authorization", comoCajero())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"la quiero de vuelta\"}"))
                .andReturn();
        assertEquals(403, rechazo.getResponse().getStatus());
        assertEquals("borrada", columnaDeLaOrden(idOrder,
                "CASE WHEN deleted_at IS NULL THEN 'viva' ELSE 'borrada' END"),
                "sigue borrada tras el intento del cajero");

        // El administrador sí, y esto es lo que fallaba SIEMPRE: la fila de
        // auditoría se construía sin order_uuid_id, que es UUID NOT NULL.
        MvcResult ok = mockMvc.perform(post("/api/orders/" + idOrder + "/restore")
                        .header("Authorization", comoAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"se borró por error\"}"))
                .andReturn();
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());

        // Vuelve a estar visible por el camino normal (el @SQLRestriction ya no la esconde).
        assertEquals(200, mockMvc.perform(get("/orders/" + idOrder).header("Authorization", comoAdmin()))
                .andReturn().getResponse().getStatus());
        assertEquals("viva", columnaDeLaOrden(idOrder,
                "CASE WHEN deleted_at IS NULL THEN 'viva' ELSE 'borrada' END"));

        // Y el rastro: dos filas, la del borrado y la de la restauración, las
        // dos atadas a la orden por su UUID.
        try (Connection c = sinRls(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT reason, deleted_by, order_uuid_id, total "
                     + "FROM order_deletions WHERE id_order = " + idOrder + " ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("se borró por error", rs.getString("reason"));
            assertEquals(uuid, rs.getString("order_uuid_id"));
            assertTrue(rs.next(), "la restauración también deja rastro");
            assertTrue(rs.getString("reason").startsWith("RESTAURADA:"));
            assertEquals(ADMIN, rs.getString("deleted_by"));
            assertEquals(uuid, rs.getString("order_uuid_id"),
                    "la columna que hacía fallar restore() siempre");
            assertEquals(0, rs.getBigDecimal("total").compareTo(new java.math.BigDecimal("5000")));
            assertTrue(!rs.next());
        }
    }
}
