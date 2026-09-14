package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas, F1.2 (S3: «un user creado desde el POS»): el vendedor es un
 * usuario del negocio con su rol, su nombre visible, y se desactiva, no se borra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class VendedorComoUsuarioTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    static final String T = "negocio-vendedores";
    static final String OTRO = "negocio-vendedores-otro";
    static final String ADMIN = "duena@negocio-vendedores.invalid";

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

    private static String bearer(String tenant, String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", role)
                .signWith(KEY).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, '!', ?, 'admin')", ADMIN, T);
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('ajeno@otro.invalid', '!', ?, 'vendedor')",
                OTRO);
    }

    private long crearVendedor(String email, String nombre) throws Exception {
        mockMvc.perform(post("/account/users").header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"claveSegura1\",\"role\":\"vendedor\","
                                + "\"nombre\":\"" + nombre + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("vendedor"))
                .andExpect(jsonPath("$.nombre").value(nombre));
        return dueno.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @Test
    @DisplayName("🔴 el admin crea un vendedor con nombre, y el vendedor entra con role=vendedor en su token")
    void elVendedorEsUnUsuarioConSuRol() throws Exception {
        crearVendedor("ana@negocio-vendedores.invalid", "Ana R.");
        Map<String, Object> fila = dueno.queryForMap("SELECT role, nombre FROM users WHERE email = ?",
                "ana@negocio-vendedores.invalid");
        assertThat(fila).containsEntry("role", "vendedor").containsEntry("nombre", "Ana R.");

        String cuerpo = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@negocio-vendedores.invalid\",\"password\":\"claveSegura1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = cuerpo.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        Claims claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("vendedor");
        assertThat(claims.get("tenant_id", String.class)).isEqualTo(T);
    }

    @Test
    @DisplayName("un cajero no crea vendedores")
    void elCajeroNoCrea() throws Exception {
        mockMvc.perform(post("/account/users").header("Authorization", bearer(T, "caja@x.invalid", "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@negocio-vendedores.invalid\",\"password\":\"claveSegura1\",\"role\":\"vendedor\"}"))
                .andExpect(status().isForbidden());
        assertThat(dueno.queryForObject("SELECT count(*) FROM users WHERE email = 'x@negocio-vendedores.invalid'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("🔴 ?rol=vendedor: el cajero ve solo los vendedores activos de SU negocio; sin rol, 403")
    void elSelectorDeLaCaja() throws Exception {
        crearVendedor("ana@negocio-vendedores.invalid", "Ana");
        long luis = crearVendedor("luis@negocio-vendedores.invalid", "Luis");
        mockMvc.perform(put("/account/users/" + luis).header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));

        String cajero = bearer(T, "caja@x.invalid", "cajero");
        mockMvc.perform(get("/account/users?rol=vendedor").header("Authorization", cajero))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Ana"));
        mockMvc.perform(get("/account/users").header("Authorization", cajero))
                .andExpect(status().isForbidden());
        // Desactivado, no borrado: la fila sigue para las ventas que lo nombran.
        assertThat(dueno.queryForObject("SELECT status FROM users WHERE id = ?", String.class, luis)).isEqualTo("disabled");
    }

    @Test
    @DisplayName("🔴 PUT: nadie se quita su propio rol de admin, y un usuario de otro negocio es 404")
    void losLimitesDelPut() throws Exception {
        long admin = dueno.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN);
        mockMvc.perform(put("/account/users/" + admin).header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rol\":\"cajero\"}"))
                .andExpect(status().isConflict());
        long ajeno = dueno.queryForObject("SELECT id FROM users WHERE email = 'ajeno@otro.invalid'", Long.class);
        mockMvc.perform(put("/account/users/" + ajeno).header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isNotFound());
        assertThat(dueno.queryForObject("SELECT status FROM users WHERE id = ?", String.class, ajeno)).isEqualTo("active");
        mockMvc.perform(put("/account/users/" + ajeno).header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rol\":\"super_admin\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un rol inventado se rechaza al crear y al cambiar")
    void rolInventado() throws Exception {
        long ana = crearVendedor("ana@negocio-vendedores.invalid", "Ana");
        mockMvc.perform(post("/account/users").header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"m@negocio-vendedores.invalid\",\"password\":\"claveSegura1\",\"role\":\"mesero\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/account/users/" + ana).header("Authorization", bearer(T, ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rol\":\"sistema\"}"))
                .andExpect(status().isBadRequest());
    }
}
