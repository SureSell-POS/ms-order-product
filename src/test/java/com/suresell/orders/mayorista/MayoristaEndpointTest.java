package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 * Plan de mayoristas, F0.3-F0.6, por la API real (perfil {@code cloud}, filtros
 * y RLS de verdad, la aplicación como {@code app_user}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class MayoristaEndpointTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String A = "qa-mayorista-a";
    static final String B = "qa-mayorista-b";
    static final String ADMIN_A = "admin@qa-mayorista-a.invalid";
    static final String ADMIN_B = "admin@qa-mayorista-b.invalid";

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

    /** Conexión como dueño: sembrar y leer lo escrito sin que RLS lo esconda. */
    private JdbcTemplate dueno;

    private static String bearer(String tenant, String email, List<String> modules) {
        JwtBuilder b = Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", "admin");
        if (modules != null) {
            b.claim("modules", modules);
        }
        return "Bearer " + b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String conModulo(String tenant, String email) {
        return bearer(tenant, email, List.of("ventas", "mayorista", "cartera"));
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {A, B}) {
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM listas_precio_items WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM listas_precio WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, '!', ?, 'admin')", ADMIN_A, A);
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, '!', ?, 'admin')", ADMIN_B, B);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES ('arroz-qa-a', ?, 'Arroz 25 kg', 120000, true)", A);
    }

    private Long idDe(String email) {
        return dueno.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    // ── F0.3 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 F0.3: un negocio sin el módulo mayorista recibe 403; con el módulo, 200")
    void laGuardaTieneDosCaras() throws Exception {
        mockMvc.perform(get("/api/mayorista/listas").header("Authorization", bearer(A, ADMIN_A, List.of("ventas"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("MODULO_NO_INCLUIDO"));
        mockMvc.perform(get("/api/mayorista/listas").header("Authorization", bearer(A, ADMIN_A, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mayorista/listas").header("Authorization", conModulo(A, ADMIN_A)))
                .andExpect(status().isOk());
    }

    // ── F0.4 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 F0.4: el admin del negocio ya no se regala módulos; nada se escribe")
    void elAdminNoSeRegalaModulos() throws Exception {
        mockMvc.perform(put("/account/modules").header("Authorization", conModulo(A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"ruta\":true}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SOLO_KAM"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM tenant_modules WHERE tenant_id = ?", Integer.class, A))
                .isZero();
    }

    // ── F0.5 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 F0.5: sin X-User-Name se crea igual, y el autor es el usuario del token")
    void elAutorSaleDelToken() throws Exception {
        String respuesta = mockMvc.perform(post("/api/mayorista/listas").header("Authorization", conModulo(A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"dist\",\"nombre\":\"Distribuidor\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID lista = UUID.fromString(respuesta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

        Map<String, Object> fila = dueno.queryForMap("SELECT creado_por, autor_id FROM listas_precio WHERE id = ?", lista);
        assertThat(fila.get("creado_por")).isEqualTo(ADMIN_A);
        assertThat(((Number) fila.get("autor_id")).longValue()).isEqualTo(idDe(ADMIN_A));
    }

    @Test
    @DisplayName("🔴 F0.5: una cabecera X-User-Name falsa no decide el autor del precio ni del cliente")
    void laCabeceraFalsaSeIgnora() throws Exception {
        UUID lista = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) "
                + "VALUES (?, 'dist', 'Distribuidor', 'semilla') RETURNING id", UUID.class, A);

        mockMvc.perform(post("/api/mayorista/listas/" + lista + "/lineas").header("Authorization", conModulo(A, ADMIN_A))
                        .header("X-User-Name", "Impostor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":\"arroz-qa-a\",\"precio\":100000}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/mayorista/clientes").header("Authorization", conModulo(A, ADMIN_A))
                        .header("X-User-Name", "Impostor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documento\":\"900123456\",\"nombre\":\"Tienda La Esquina\",\"listaPrecioId\":\""
                                + lista + "\"}"))
                .andExpect(status().isCreated());

        Map<String, Object> linea = dueno.queryForMap(
                "SELECT usuario_id, autor_id FROM listas_precio_items WHERE tenant_id = ? AND lista_id = ?", A, lista);
        assertThat(linea.get("usuario_id")).isEqualTo(ADMIN_A);
        assertThat(((Number) linea.get("autor_id")).longValue()).isEqualTo(idDe(ADMIN_A));
        Map<String, Object> cliente = dueno.queryForMap(
                "SELECT creado_por, autor_id FROM clientes WHERE tenant_id = ? AND documento = '900123456'", A);
        assertThat(cliente.get("creado_por")).isEqualTo(ADMIN_A);
        assertThat(((Number) cliente.get("autor_id")).longValue()).isEqualTo(idDe(ADMIN_A));
    }

    // ── F0.6 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 F0.6: sin RLS, los clientes de A traen la cartera de A y no la de B con el mismo documento")
    void losClientesNoMezclanCarteras() {
        for (String[] n : new String[][] {{A, "5000"}, {B, "777000"}}) {
            dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES (?, '900123456', 'Tienda', 'semilla')",
                    n[0]);
            dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                            + "customer_name, status, total_debt, updated_at) "
                            + "VALUES (?, ?, now(), 100000, '900123456', 'Tienda', 'ACTIVE', ?, now())",
                    UUID.randomUUID().toString(), n[0], new java.math.BigDecimal(n[1]));
        }
        // El dueño salta RLS: aquí solo el filtro escrito separa los negocios.
        List<Map<String, Object>> clientes = new ListasDePrecio(dueno).clientes(A);
        assertThat(clientes).hasSize(1);
        assertThat(new java.math.BigDecimal(clientes.get(0).get("deuda").toString())).isEqualByComparingTo("5000");
    }
}
