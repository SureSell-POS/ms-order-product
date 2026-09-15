package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
 * S1 (V81): app_user no tiene ningún privilegio sobre {@code super_admins}; el KAM se lee y se crea solo por
 * {@code fn_super_admin_por_correo} y {@code fn_super_admin_crear}. El servicio corre como app_user, igual que en
 * staging y producción.
 *
 * <p>Riesgo residual (escrito en V81): app_user todavía puede CREAR un KAM llamando a la función. Esta prueba lo
 * fija a propósito, para que cerrarlo sea una decisión visible y no un cambio silencioso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class SuperAdminsSoloPorFuncionTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String KAM = "kam-arranque@suresell.invalid";
    static final String CLAVE = "Arranque-de-prueba-2026";

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
        r.add("kam.bootstrap.email", () -> KAM);
        r.add("kam.bootstrap.password", () -> CLAVE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Connection comoAppUser() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
    }

    private Connection comoDueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private int login(String correo, String clave) throws Exception {
        return mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", correo, "password", clave))))
                .andReturn().getResponse().getStatus();
    }

    private String token(String correo, String clave) throws Exception {
        String cuerpo = mvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", correo, "password", clave))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(cuerpo);
        return n.get("token").asText();
    }

    private static void exigeSinPermiso(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            fail("se ejecutó sin permiso: " + sql);
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as("SQLSTATE de: " + sql + " (" + e.getMessage() + ")").isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("🔴 S1: el KAM de arranque se creó como app_user y su login funciona; la clave mala da 401")
    void kamDeArranqueYLogin() throws Exception {
        assertThat(login(KAM, CLAVE)).isEqualTo(200);
        assertThat(login(KAM.toUpperCase(), CLAVE)).as("el correo no distingue mayúsculas").isEqualTo(200);
        assertThat(login(KAM, "Otra-clave-mala-2026")).isEqualTo(401);
        assertThat(login("nadie@suresell.invalid", CLAVE)).isEqualTo(401);
    }

    @Test
    @DisplayName("🔴 S1: el alta de otro KAM desde el KAM sigue funcionando; repetida da 409")
    void altaDesdeElKam() throws Exception {
        String bearer = "Bearer " + token(KAM, CLAVE);
        String cuerpo = json.writeValueAsString(java.util.Map.of("email", "segundo-kam@suresell.invalid",
                "password", "Segunda-cuenta-2026"));
        mvc.perform(post("/admin/super-admins").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isCreated());
        assertThat(login("segundo-kam@suresell.invalid", "Segunda-cuenta-2026")).isEqualTo(200);
        mvc.perform(post("/admin/super-admins").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("🔴 S1: como app_user, SELECT, INSERT, UPDATE, DELETE, TRUNCATE y la secuencia directos dan 42501")
    void appUserSinPrivilegiosSobreLaTabla() throws Exception {
        try (Connection c = comoAppUser()) {
            exigeSinPermiso(c, "SELECT count(*) FROM public.super_admins");
            exigeSinPermiso(c, "INSERT INTO public.super_admins (email, password_hash) VALUES ('x@suresell.invalid', 'h')");
            exigeSinPermiso(c, "UPDATE public.super_admins SET password_hash = 'h'");
            exigeSinPermiso(c, "DELETE FROM public.super_admins");
            exigeSinPermiso(c, "TRUNCATE public.super_admins");
            exigeSinPermiso(c, "SELECT nextval('public.super_admins_id_seq')");
        }
        // Control del instrumento: el dueño sí lee, y la fila del KAM de arranque está.
        try (Connection c = comoDueno(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM public.super_admins WHERE email = '" + KAM + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("🔴 S1: otro rol sin EXECUTE (PUBLIC revocado) no llama a las funciones; app_user sí y la función valida")
    void funcionesSoloParaAppUser() throws Exception {
        try (Connection d = comoDueno(); Statement s = d.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otro_rol_s1') THEN "
                    + "CREATE ROLE otro_rol_s1 LOGIN PASSWORD 'otro_pw'; END IF; END $$");
            s.execute("GRANT USAGE ON SCHEMA public TO otro_rol_s1");
        }
        try (Connection o = DriverManager.getConnection(PG.getJdbcUrl(), "otro_rol_s1", "otro_pw")) {
            exigeSinPermiso(o, "SELECT * FROM public.fn_super_admin_por_correo('" + KAM + "')");
            exigeSinPermiso(o, "SELECT public.fn_super_admin_crear('z@suresell.invalid', '$2a$10$" + "a".repeat(53) + "')");
        }
        try (Connection c = comoAppUser(); Statement s = c.createStatement()) {
            try {
                s.execute("SELECT public.fn_super_admin_crear('clave@suresell.invalid', 'en-claro')");
                fail("la función aceptó una clave sin hash BCrypt");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).isEqualTo("22023");
            }
            // Riesgo residual escrito en V81: app_user todavía CREA un KAM por la función.
            try (var rs = s.executeQuery("SELECT public.fn_super_admin_crear('residual@suresell.invalid', '$2a$10$"
                    + "b".repeat(53) + "')")) {
                assertThat(rs.next()).isTrue();
            }
        }
        try (Connection d = comoDueno(); Statement s = d.createStatement()) {
            s.execute("DELETE FROM public.super_admins WHERE email = 'residual@suresell.invalid'");
        }
    }
}
