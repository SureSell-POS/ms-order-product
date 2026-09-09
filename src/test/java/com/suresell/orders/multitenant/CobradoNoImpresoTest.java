package com.suresell.orders.multitenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * V52 — «Cobrado, no impreso», medido contra un Postgres con la cadena real.
 *
 * <h3>Lo que protege</h3>
 *
 * Hoy una venta se cobra, la tirilla no sale y nada lo registra. Estos tests
 * fijan que el estado de la tirilla existe, que es distinto de la comanda
 * ({@code is_printed}), que un estado inventado no entra ni por la API ni por
 * SQL, que un negocio no puede tocar la tirilla de otro, y que la sede que no
 * imprime lo dice en el mismo endpoint que el POS ya lee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CobradoNoImpresoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-tirilla";
    static final String OTRO = "otro-negocio-tirilla";

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
    final ObjectMapper json = new ObjectMapper();

    private String bearer(String tenant) {
        return "Bearer " + Jwts.builder()
                .claim("tenant_id", tenant)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM order_item");
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM tenant_order_counters WHERE tenant_id IN ('" + TENANT + "','" + OTRO + "')");
            s.execute("DELETE FROM sites WHERE tenant_id IN ('" + TENANT + "','" + OTRO + "')");
            s.execute("DELETE FROM menu_products");
            for (String t : new String[] {TENANT, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','T','pro') ON CONFLICT (id) DO NOTHING");
                s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                        + "VALUES ('P-" + t + "','" + t + "','Gaseosa',5000,true)");
                s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                        + "VALUES ('" + t + "', 'Principal', 'PRINCIPAL', 'DIRECTO', true)");
            }
        }
    }

    private long crearVenta(String tenant, String clave, String extra) throws Exception {
        String cuerpo = "{\"paymentMethod\":\"CASH\","
                + "\"items\":[{\"productId\":\"P-" + tenant + "\",\"quantity\":1,\"unitPrice\":5000}],"
                + "\"idempotencyKey\":\"" + clave + "\"" + extra + "}";
        MvcResult r = mockMvc.perform(post("/orders/create")
                        .header("Authorization", bearer(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString()).get("idOrder").asLong();
    }

    private MvcResult recibo(String tenant, long id, String cuerpo) throws Exception {
        return mockMvc.perform(patch("/orders/" + id + "/recibo")
                        .header("Authorization", bearer(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
    }

    private String columna(long id, String col) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + col + " FROM orders WHERE id_order = " + id
                     + " AND tenant_id = '" + TENANT + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("una venta nace con la tirilla en no_solicitado y sin motivo")
    void naceNoSolicitado() throws Exception {
        long id = crearVenta(TENANT, "nace-1", "");
        assertEquals("no_solicitado", columna(id, "recibo_estado"));
        assertNull(columna(id, "recibo_motivo"));
        String detalle = mockMvc.perform(get("/orders/" + id).header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(detalle.contains("\"reciboEstado\":\"no_solicitado\""), detalle);
    }

    @Test
    @DisplayName("🔴 PATCH no_impreso con motivo: queda escrito, con fecha, y responde el estado tal como quedó")
    void patchNoImpreso() throws Exception {
        long id = crearVenta(TENANT, "patch-1", "");
        MvcResult r = recibo(TENANT, id, "{\"estado\":\"no_impreso\",\"motivo\":\"agente_apagado\"}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertEquals(id, n.get("idOrder").asLong());
        assertEquals("no_impreso", n.get("reciboEstado").asText());
        assertEquals("agente_apagado", n.get("reciboMotivo").asText());
        assertFalse(n.get("reciboActualizadoAt").isNull());
        assertEquals("no_impreso", columna(id, "recibo_estado"));
        assertEquals("agente_apagado", columna(id, "recibo_motivo"));

        // Reimprimir después: el motivo se limpia, porque ya no hay fallo que explicar.
        r = recibo(TENANT, id, "{\"estado\":\"enviado\"}");
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("enviado", columna(id, "recibo_estado"));
        assertNull(columna(id, "recibo_motivo"));
    }

    @Test
    @DisplayName("la tirilla NO es la comanda: el PATCH de recibo no toca is_printed")
    void noTocaLaComanda() throws Exception {
        long id = crearVenta(TENANT, "comanda-1", "");
        recibo(TENANT, id, "{\"estado\":\"confirmado\"}");
        assertEquals("f", columna(id, "is_printed"));
    }

    @Test
    @DisplayName("🔴 control negativo: un estado inventado es 400 con el contrato de error, y no queda escrito")
    void estadoInvalidoEs400() throws Exception {
        long id = crearVenta(TENANT, "invalido-1", "");
        MvcResult r = recibo(TENANT, id, "{\"estado\":\"impreso\"}");
        assertEquals(400, r.getResponse().getStatus());
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertEquals("BAD_REQUEST", n.get("error").asText());
        assertTrue(n.get("message").asText().contains("impreso"), n.toString());
        assertEquals("no_solicitado", columna(id, "recibo_estado"));

        r = recibo(TENANT, id, "{\"estado\":\"no_impreso\",\"motivo\":\"se_fue_la_luz\"}");
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("no_solicitado", columna(id, "recibo_estado"));
    }

    @Test
    @DisplayName("🔴 aislamiento: la tirilla de una venta de otro negocio no existe (404) y no cambia")
    void otroNegocioEs404() throws Exception {
        long id = crearVenta(TENANT, "ajena-1", "");
        MvcResult r = recibo(OTRO, id, "{\"estado\":\"descartado\"}");
        assertEquals(404, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("no_solicitado", columna(id, "recibo_estado"));
    }

    @Test
    @DisplayName("el CHECK de la base rechaza un estado fuera de la lista, medido con una fila")
    void elCheckSeMideConUnaFila() throws Exception {
        long id = crearVenta(TENANT, "check-1", "");
        try (Connection c = admin(); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE orders SET recibo_estado = 'impreso' WHERE id_order = " + id));
            assertTrue(e.getMessage().contains("ck_orders_recibo_estado"), e.getMessage());
        }
    }

    @Test
    @DisplayName("una venta offline llega ya con el estado de su tirilla, y el historial la filtra por «sin tirilla»")
    void ventaOfflineTraeSuEstadoYElHistorialFiltra() throws Exception {
        long sin = crearVenta(TENANT, "offline-1", ",\"reciboEstado\":\"no_impreso\",\"reciboMotivo\":\"agente_apagado\"");
        long con = crearVenta(TENANT, "offline-2", ",\"reciboEstado\":\"enviado\"");
        long nada = crearVenta(TENANT, "offline-3", "");
        assertEquals("no_impreso", columna(sin, "recibo_estado"));
        assertEquals("agente_apagado", columna(sin, "recibo_motivo"));
        assertEquals("enviado", columna(con, "recibo_estado"));
        assertEquals("no_solicitado", columna(nada, "recibo_estado"));

        String pagina = mockMvc.perform(get("/orders/historial").param("reciboEstado", "no_impreso")
                        .header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        JsonNode contenido = json.readTree(pagina).get("content");
        assertEquals(1, contenido.size(), pagina);
        assertEquals(sin, contenido.get(0).get("idOrder").asLong());
        assertEquals("agente_apagado", contenido.get(0).get("reciboMotivo").asText());

        String todas = mockMvc.perform(get("/orders/historial").header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        assertEquals(3, json.readTree(todas).get("content").size(), todas);
    }

    @Test
    @DisplayName("🔴 control negativo: un estado inventado al crear la venta es 400 y no deja fila")
    void estadoInvalidoAlCrearNoDejaFila() throws Exception {
        String cuerpo = "{\"paymentMethod\":\"CASH\","
                + "\"items\":[{\"productId\":\"P-" + TENANT + "\",\"quantity\":1,\"unitPrice\":5000}],"
                + "\"idempotencyKey\":\"crear-invalido\",\"reciboEstado\":\"impreso\"}";
        int status = mockMvc.perform(post("/orders/create")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn().getResponse().getStatus();
        assertEquals(400, status);
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM orders WHERE tenant_id = '" + TENANT + "'")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "el servidor respondió 400 pero la orden quedó escrita");
        }
    }

    @Test
    @DisplayName("sites/mode dice si la sede imprime tirilla: true por defecto, false cuando el KAM lo apaga")
    void laSedeDiceSiImprime() throws Exception {
        String modo = mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(modo.contains("\"imprimeTirilla\":true"), modo);

        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("UPDATE sites SET imprime_tirilla = false WHERE tenant_id = '" + TENANT + "'");
        }
        modo = mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(modo.contains("\"imprimeTirilla\":false"), modo);

        // El otro negocio no se entera.
        String otro = mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer(OTRO)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(otro.contains("\"imprimeTirilla\":true"), otro);
    }

    @Test
    @DisplayName("sin sede, la respuesta sigue siendo la de siempre y la tirilla se asume que sí")
    void sinSedeImprime() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
        }
        String modo = mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer(TENANT)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(modo.contains("\"imprimeTirilla\":true"), modo);
    }
}
