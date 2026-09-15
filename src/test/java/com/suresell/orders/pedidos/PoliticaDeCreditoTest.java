package com.suresell.orders.pedidos;

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
import java.util.ArrayList;
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
 * Plan de mayoristas F5.4 por la API real, con {@code app_user}: con AVISAR el pedido sigue
 * su camino; con RETENER_PEDIDO, el de un cliente con una factura vencida hace más de N días
 * nace RETENIDO con FACTURA_VENCIDA, y liberarlo deja confirmarlo. Solo el admin cambia la
 * política, y queda su rastro (V73).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class PoliticaDeCreditoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-politica-credito";
    static final String OTRO = "qa-politica-credito-otro";
    static final String ADMIN = "admin@qa-politica-credito.invalid";
    static final String CAJA = "caja@qa-politica-credito.invalid";
    static final String ANA = "ana@qa-politica-credito.invalid";
    static final String ADMIN_OTRO = "admin@qa-politica-credito-otro.invalid";
    /** Debe una factura vencida hace 10 días. */
    static final String MOROSA = "900";
    /** Debe una factura que vence en 5 días. */
    static final String AL_DIA = "901";

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
    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));

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
            dueno.update("DELETE FROM pedidos.pedidos_eventos_lineas WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos_lineas WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.contadores_de_pedidos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM politica_de_credito_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM politica_de_credito WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        admin = usuario(ADMIN, "admin", "Admin", T);
        usuario(CAJA, "cajero", "Caja", T);
        long ana = usuario(ANA, "vendedor", "Ana", T);
        usuario(ADMIN_OTRO, "admin", "Otro", OTRO);
        for (int i = 1; i <= 3; i++) {
            dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, ?, 1000, true)",
                    producto(i), T, "Producto " + i);
        }
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda morosa', ?, 8, 's'), (?, ?, 'Tienda al día', ?, 8, 's')", T, MOROSA, ana, T, AL_DIA, ana);
        debito(cuenta(MOROSA), 50000, hoy.minusDays(18), hoy.minusDays(10));
        debito(cuenta(AL_DIA), 50000, hoy.minusDays(3), hoy.plusDays(5));
    }

    private long usuario(String email, String rol, String nombre, String tenant) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, tenant, rol, nombre);
    }

    private String cuenta(String doc) {
        String id = UUID.randomUUID().toString();
        dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                + "customer_name, status, total_debt, updated_at) VALUES (?, ?, now(), 1000000, ?, 'x', 'ACTIVE', 50000, now())",
                id, T, doc);
        return id;
    }

    private void debito(String cuenta, int monto, LocalDate fecha, LocalDate vence) {
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, "
                + "transaction_date, type, vence_el, order_uuid) VALUES (?, ?, ?, ?, now(), 'Venta a credito', ?, 'DEBIT', ?, ?)",
                UUID.randomUUID().toString(), T, cuenta, monto, java.sql.Date.valueOf(fecha), java.sql.Date.valueOf(vence),
                UUID.randomUUID());
    }

    private static String producto(int i) {
        return "qa-pol-" + i;
    }

    private static String lineas() {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            l.add("{\"productoId\":\"" + producto(i) + "\",\"cantidad\":2}");
        }
        return "[" + String.join(",", l) + "]";
    }

    private JsonNode tomar(String quien, String rol, String cliente, String clave) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(quien, rol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + cliente + "\",\"origen\":\"vendedor\",\"lineas\":" + lineas()
                                + ",\"idempotencyKey\":\"" + clave + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions politica(String quien, String rol, String cuerpo) throws Exception {
        return mockMvc.perform(put("/api/cartera/politica-de-credito").header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static List<String> tipos(JsonNode pedido) {
        List<String> t = new ArrayList<>();
        pedido.get("eventos").forEach(e -> t.add(e.get("tipo").asText()));
        return t;
    }

    @Test
    @DisplayName("🔴 F5.4: con AVISAR (sin fila y con fila) el pedido de la tienda morosa no se retiene: el admin lo deja CONFIRMADO")
    void conAvisarNoRetiene() throws Exception {
        assertThat(tomar(ADMIN, "admin", MOROSA, "avisar-sin-fila").get("estado").asText()).isEqualTo("CONFIRMADO");
        politica(ADMIN, "admin", "{\"politica\":\"AVISAR\",\"diasMoraParaRetener\":0}").andExpect(status().isOk());
        JsonNode p = tomar(ADMIN, "admin", MOROSA, "avisar-con-fila");
        assertThat(p.get("estado").asText()).isEqualTo("CONFIRMADO");
        assertThat(tipos(p)).doesNotContain("RETENIDO");
    }

    @Test
    @DisplayName("🔴 F5.4: con RETENER_PEDIDO y 5 días, el pedido de la morosa (10 días) nace RETENIDO con FACTURA_VENCIDA; liberar deja confirmarlo")
    void conRetenerNaceRetenido() throws Exception {
        politica(ADMIN, "admin", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.politica").value("RETENER_PEDIDO"))
                .andExpect(jsonPath("$.diasMoraParaRetener").value(5));

        JsonNode retenido = tomar(CAJA, "cajero", MOROSA, "retener-1");
        assertThat(retenido.get("estado").asText()).isEqualTo("RETENIDO");
        assertThat(tipos(retenido)).containsExactly("ENVIADO", "RETENIDO");
        JsonNode evento = retenido.get("eventos").get(1);
        assertThat(evento.get("motivo").asText()).isEqualTo("FACTURA_VENCIDA");
        assertThat(evento.get("nota").asText()).contains("10 días").contains("más de 5");

        // El de la vendedora también: la política es del negocio, no de quién toma el pedido.
        assertThat(tomar(ANA, "vendedor", MOROSA, "retener-ana").get("estado").asText()).isEqualTo("RETENIDO");
        // Control: la tienda al día sigue su camino.
        assertThat(tomar(CAJA, "cajero", AL_DIA, "al-dia").get("estado").asText()).isEqualTo("CONFIRMADO");

        // Liberar y confirmar lo pasa a CONFIRMADO.
        UUID id = UUID.fromString(retenido.get("id").asText());
        mockMvc.perform(post("/api/pedidos/" + id + "/liberar").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"PAGO_RECIBIDO\",\"idempotencyKey\":\"retener-1-libera\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LIBERADO"));
        mockMvc.perform(post("/api/pedidos/" + id + "/confirmar").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"retener-1-confirma\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"));
    }

    @Test
    @DisplayName("control de borde: con 10 días de mora, la factura vencida hace exactamente 10 no retiene (es «más de N»)")
    void elBordeNoRetiene() throws Exception {
        politica(ADMIN, "admin", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":10}").andExpect(status().isOk());
        assertThat(tomar(ADMIN, "admin", MOROSA, "borde-10").get("estado").asText()).isEqualTo("CONFIRMADO");
        politica(ADMIN, "admin", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":9}").andExpect(status().isOk());
        assertThat(tomar(ADMIN, "admin", MOROSA, "borde-9").get("estado").asText()).isEqualTo("RETENIDO");
    }

    @Test
    @DisplayName("🔴 F5.4: solo el admin cambia la política, con valores del catálogo, y cada cambio queda con su autor; otro negocio no la ve")
    void soloAdminYConRastro() throws Exception {
        politica(CAJA, "cajero", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":5}").andExpect(status().isForbidden());
        politica(ADMIN, "admin", "{\"politica\":\"BLOQUEAR\",\"diasMoraParaRetener\":5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("politica"));
        politica(ADMIN, "admin", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":-1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("diasMoraParaRetener"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM politica_de_credito WHERE tenant_id = ?", Integer.class, T)).isZero();

        politica(ADMIN, "admin", "{\"politica\":\"RETENER_PEDIDO\",\"diasMoraParaRetener\":15}").andExpect(status().isOk());
        politica(ADMIN, "admin", "{\"politica\":\"AVISAR\",\"diasMoraParaRetener\":15}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualizadoPorId").value(admin))
                .andExpect(jsonPath("$.historia.length()").value(2))
                .andExpect(jsonPath("$.historia[0].politicaAnterior").value("RETENER_PEDIDO"))
                .andExpect(jsonPath("$.historia[0].politica").value("AVISAR"))
                .andExpect(jsonPath("$.historia[0].usuario").value("Admin"))
                .andExpect(jsonPath("$.historia[1].politicaAnterior").isEmpty());

        mockMvc.perform(get("/api/cartera/politica-de-credito").header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.politica").value("AVISAR"))
                .andExpect(jsonPath("$.actualizadoPorId").isEmpty())
                .andExpect(jsonPath("$.historia.length()").value(0));
    }
}
