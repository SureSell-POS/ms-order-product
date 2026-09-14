package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Las dos cadenas Flyway del servicio arrancan juntas, cada una en su historial,
 * y ninguna apaga a la otra (plan de mayoristas F0.9, riesgo 22; plan de la red
 * B2B T1.1, riesgo 2).
 *
 * <p>Se levanta el contexto REAL, con el perfil {@code cloud} y la cadena
 * encendida: las dos formas de romper esto son de configuración y solo se ven
 * arrancando.
 *
 * <p><b>Controles negativos</b>, hechos al escribirla (2026-09-13), con lo que
 * de verdad se puso en rojo:
 * <ol>
 *   <li>Quitar {@code .table(...)} en {@link FlywayPedidos#configuracion}: con
 *       {@code defaultSchema(pedidos)} el historial no cae en {@code public}, cae en
 *       {@code pedidos.flyway_schema_history}; rojas 0, 2 y 4 (la cadena no está
 *       donde se la busca).</li>
 *   <li>Quitar además {@code schemas} y {@code defaultSchema}: la cadena apunta a
 *       {@code public.flyway_schema_history}; Flyway ve la V1 de {@code public} con
 *       otra huella, falla la validación y <b>el contexto no arranca</b>: las seis
 *       en rojo. En un despliegue, servicio caído en vez de historial pisado.</li>
 *   <li>Exponer la cadena como {@code @Bean Flyway} (sin el inicializador): la
 *       configuración automática de {@code public} desaparece, {@code public} no
 *       migra (ni siquiera existe {@code app_user}); rojas 1, 3 y humo.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("cloud")
@Testcontainers
class HistorialesSeparadosTest {

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
        r.add("pedidos.flyway.enabled", () -> "true");
        r.add("security.jwt.secret", () -> "clave-de-prueba-multitenant-min-32-bytes!!");
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @Autowired ApplicationContext contexto;
    @Autowired DataSource appDataSource;

    private JdbcTemplate dueno;

    @BeforeEach
    void conectar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    private static List<String> scripts(String carpeta) throws IOException {
        try (Stream<Path> s = Files.list(Paths.get("src/main/resources", carpeta))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.matches("V\\d+__.*\\.sql")).sorted().toList();
        }
    }

    private static int ultima(List<String> scripts) {
        return scripts.stream().mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__")))).max().orElseThrow();
    }

    private List<String> historial(String tabla) {
        return dueno.queryForList("SELECT script FROM " + tabla + " WHERE success AND version IS NOT NULL", String.class);
    }

    @Test
    @DisplayName("0 · la cadena pedidos corrió de verdad: su bean existe y su historial también")
    void laCadenaCorrio() {
        // Sin esto, con el interruptor apagado las demás pasarían en verde sin migrar nada.
        assertThat(contexto.containsBean("flywayPedidos")).isTrue();
        assertThat(dueno.queryForObject(
                "SELECT to_regclass('pedidos." + FlywayPedidos.TABLA_DE_HISTORIAL + "') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("🔴 1 · public.flyway_schema_history no tiene ninguna migración de pedidos")
    void publicNoTieneNadaDePedidos() throws IOException {
        assertThat(historial("public.flyway_schema_history"))
                .doesNotContainAnyElementsOf(scripts("db/migration-pedidos"))
                .containsExactlyInAnyOrderElementsOf(scripts("db/migration"));
    }

    @Test
    @DisplayName("2 · el historial de pedidos no tiene ninguna migración de public")
    void pedidosNoTieneNadaDePublic() throws IOException {
        assertThat(historial("pedidos." + FlywayPedidos.TABLA_DE_HISTORIAL))
                .doesNotContainAnyElementsOf(scripts("db/migration"))
                .containsExactlyInAnyOrderElementsOf(scripts("db/migration-pedidos"));
    }

    @Test
    @DisplayName("🔴 3 · public sigue migrando: su última versión está aplicada")
    void laUltimaDePublicEstaAplicada() throws IOException {
        Integer aplicada = dueno.queryForObject(
                "SELECT max(version::int) FROM public.flyway_schema_history WHERE success AND version IS NOT NULL",
                Integer.class);
        assertThat(aplicada).isEqualTo(ultima(scripts("db/migration")));
    }

    @Test
    @DisplayName("4 · la última de pedidos está aplicada en su propio historial")
    void laUltimaDePedidosEstaAplicada() throws IOException {
        Integer aplicada = dueno.queryForObject(
                "SELECT max(version::int) FROM pedidos." + FlywayPedidos.TABLA_DE_HISTORIAL
                        + " WHERE success AND version IS NOT NULL", Integer.class);
        assertThat(aplicada).isEqualTo(ultima(scripts("db/migration-pedidos")));
    }

    @Test
    @DisplayName("humo · el pool de la app (app_user) llega a `red` al arrancar")
    void laAppUsaRed() {
        Boolean usa = new JdbcTemplate(appDataSource).queryForObject(
                "SELECT has_schema_privilege(current_user, 'red', 'USAGE') AND has_schema_privilege(current_user, 'pedidos', 'USAGE')",
                Boolean.class);
        assertThat(usa).isTrue();
        assertThat(new JdbcTemplate(appDataSource).queryForObject("SELECT current_user", String.class))
                .isEqualTo("app_user");
    }
}
