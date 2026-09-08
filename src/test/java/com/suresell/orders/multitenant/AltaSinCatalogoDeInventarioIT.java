package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Un Postgres con SOLO la cadena {@code public}: sin esquema {@code inventario}.
 *
 * <p>Es el caso de la suite de este servicio y de cualquier base nueva antes de
 * que corra ms-smart-inventory. El alta no puede depender de que el otro
 * servicio ya haya migrado, pero tampoco puede fingir que anotó un perfil.
 */
@Testcontainers
class AltaSinCatalogoDeInventarioIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static AltaDeNegocioService servicio;

    @BeforeAll
    static void preparar() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        servicio = new AltaDeNegocioService(jdbc, new BCryptPasswordEncoder(),
                new PlanCatalogService(new PlanRepository(jdbc)),
                new com.suresell.orders.flujo.FlujosDeVenta(jdbc), new PerfilDelNegocio(jdbc));
    }

    @Test
    @DisplayName("sin catálogo de inventario se mide que no está, y el alta sale con el flujo de siempre")
    void sinCatalogoElAltaNoFinge() {
        assertThat(new PerfilDelNegocio(jdbc).hayCatalogo()).isFalse();

        var r = servicio.darDeAlta(new AltaDeNegocioService.Solicitud(
                "Kiosco", "admin@kiosco.co", "clave-seguraaa", "pro", null, null, null, null, null, null, null));

        assertThat(r.flujoDeVenta()).isEqualTo("RASTREADOR");
        assertThat(r.modo()).isEqualTo("PLAZOLETA");
        assertThat(r.perfil()).isNull();
        // No hay dónde anotarlo, y el resultado lo dice en vez de callarlo.
        assertThat(r.perfilRegistrado()).isFalse();
    }

    @Test
    @DisplayName("sin catálogo, el perfil que sí tiene flujos en public se acepta y manda el defecto")
    void elPerfilConFlujosEnPublicSeAceptaSinCatalogo() {
        var r = servicio.darDeAlta(new AltaDeNegocioService.Solicitud(
                "Farmacia", "admin@farmacia.co", "clave-seguraaa", "pro", null, null, null, null, null,
                "drogueria", null));

        assertThat(r.perfil()).isEqualTo("drogueria");
        assertThat(r.flujoDeVenta()).isEqualTo("DIRECTO");
        assertThat(r.perfilRegistrado()).isFalse();
    }
}
