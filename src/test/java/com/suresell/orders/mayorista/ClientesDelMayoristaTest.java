package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
 * Plan de mayoristas F1.6 por la API real: el cliente ampliado (V62), su historia
 * con autor en {@code clientes_eventos}, y el vendedor viendo solo sus clientes,
 * filtrado en el servidor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class ClientesDelMayoristaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-clientes-may";
    static final String ADMIN = "admin@qa-clientes-may.invalid";
    static final String ANA = "ana@qa-clientes-may.invalid";
    static final String PEDRO = "pedro@qa-clientes-may.invalid";
    static final String CAJA = "caja@qa-clientes-may.invalid";

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
    private long admin;
    private long ana;
    private long pedro;

    private static String bearer(String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM users WHERE tenant_id = ?", T);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", T, T);
        admin = usuario(ADMIN, "admin");
        ana = usuario(ANA, "vendedor");
        pedro = usuario(PEDRO, "vendedor");
        usuario(CAJA, "cajero");
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, creado_por) VALUES "
                + "(?, '100', 'Tienda de Ana', ?, 's'), (?, '200', 'Tienda de Pedro', ?, 's'), (?, '300', 'Sin vendedor', NULL, 's')",
                T, ana, T, pedro, T);
    }

    private long usuario(String email, String rol) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, '!', ?, ?) RETURNING id",
                Long.class, email, T, rol);
    }

    @Test
    @DisplayName("🔴 un vendedor ve solo sus clientes, aunque pida los de otro; admin y cajero ven todos y filtran")
    void elVendedorVeLosSuyos() throws Exception {
        mockMvc.perform(get("/api/mayorista/clientes").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("100"));
        mockMvc.perform(get("/api/mayorista/clientes").param("vendedorId", String.valueOf(pedro))
                        .header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("100"));
        mockMvc.perform(get("/api/mayorista/clientes").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/mayorista/clientes").param("vendedorId", String.valueOf(pedro))
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("200"));
        // La ficha de un cliente de otro vendedor no existe para Ana.
        mockMvc.perform(get("/api/mayorista/clientes/200").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/mayorista/clientes/100").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Tienda de Ana"));
    }

    @Test
    @DisplayName("🔴 editar un cliente por la API deja un evento por campo cambiado, CON autor")
    void editarDejaHistoriaConAutor() throws Exception {
        mockMvc.perform(put("/api/mayorista/clientes/300").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoCliente\":\"dulceria\",\"direccionEntrega\":\"Calle 1 # 2-3\",\"plazoDias\":8,"
                                + "\"vendedorId\":" + ana + ",\"exigeFactura\":true,\"municipioDane\":\"05001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo_cliente").value("DULCERIA"))
                .andExpect(jsonPath("$.direccion_entrega").value("Calle 1 # 2-3"))
                .andExpect(jsonPath("$.vendedor").doesNotExist());

        List<Map<String, Object>> eventos = dueno.queryForList(
                "SELECT e.campo, e.valor_anterior, e.valor_nuevo, e.usuario_id FROM clientes_eventos e "
                        + "JOIN clientes c ON c.id = e.cliente_id WHERE c.tenant_id = ? AND c.documento = '300' ORDER BY e.campo", T);
        assertThat(eventos).extracting(e -> e.get("campo"))
                .containsExactly("direccion_entrega", "exige_factura", "municipio_dane", "plazo_dias", "tipo_cliente", "vendedor_id");
        assertThat(eventos).allSatisfy(e -> assertThat(((Number) e.get("usuario_id")).longValue()).isEqualTo(admin));
        assertThat(eventos).filteredOn(e -> "plazo_dias".equals(e.get("campo")))
                .singleElement().satisfies(e -> assertThat(e.get("valor_nuevo")).isEqualTo("8"));

        // El POST de siempre sobre un cliente existente también deja autor.
        mockMvc.perform(post("/api/mayorista/clientes").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documento\":\"300\",\"nombre\":\"Sin vendedor renombrada\",\"plazoDias\":8}"))
                .andExpect(status().isCreated());
        assertThat(dueno.queryForObject("SELECT usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'nombre'",
                Long.class, T)).isEqualTo(admin);
    }

    @Test
    @DisplayName("desactivar no borra: activo=false con su evento; y solo el admin edita o desactiva")
    void desactivarYPermisos() throws Exception {
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ANA, "vendedor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plazoDias\":30}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes WHERE tenant_id = ? AND documento = '100'",
                Integer.class, T)).isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT valor_nuevo FROM clientes_eventos WHERE tenant_id = ? AND campo = 'activo'",
                String.class, T)).isEqualTo("false");
    }

    @Test
    @DisplayName("🔴 datos fuera de catálogo: 400 con campo, y la fila no cambia")
    void catalogosCerrados() throws Exception {
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tipoCliente\":\"OTRO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("tipoCliente"));
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"municipioDane\":\"Medellin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("municipioDane"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 un cambio que llega sin app.user_id se guarda, queda sin autor y AVISA en el log de la base")
    void sinAutorAvisa() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + T + "'");
            st.executeUpdate("UPDATE clientes SET plazo_dias = 15 WHERE documento = '100'");
            SQLWarning aviso = st.getWarnings();
            // SQLWarning es Throwable e Iterable a la vez: se afirma sobre su texto.
            String texto = aviso == null ? null : aviso.getMessage();
            assertThat(texto).isNotNull().contains("clientes_eventos sin autor");
        }
        assertThat(dueno.queryForObject("SELECT usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'plazo_dias'",
                Long.class, T)).isNull();
    }
}
