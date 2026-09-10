package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V54: en staging había nueve políticas `panel_shark_*` (hechas a mano) que
 * sumadas a `tenant_isolation_*` dejaban ver las ventas de Shark a cualquier
 * negocio. Ninguna migración las creó; esta guarda impide que una lo haga.
 *
 * <p>Dos reglas sobre `public`: ninguna política nombra a un negocio concreto,
 * y ninguna tabla con `tenant_id` tiene una política abierta ({@code true})
 * al lado de la suya. Las abiertas de los catálogos globales (planes, flujos,
 * tenants, super_admins) no llevan `tenant_id` y no cuentan.
 */
@Testcontainers
class NingunaPoliticaDeUnSoloNegocioTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private List<String> consulta(String sql) throws SQLException {
        List<String> filas = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                filas.add(rs.getString(1));
            }
        }
        return filas;
    }

    @Test
    @DisplayName("🔴 ninguna política de public nombra a un negocio concreto")
    void ningunNegocioEscritoAMano() throws SQLException {
        assertThat(consulta("""
                SELECT tablename || '.' || policyname || ' [' || qual || ']'
                  FROM pg_policies
                 WHERE schemaname = 'public'
                   AND (qual ~* 'shark' OR qual ~* '''[a-z0-9-]+''')""")).isEmpty();
    }

    @Test
    @DisplayName("una tabla con tenant_id no tiene una política abierta al lado de la suya")
    void ningunaAbiertaJuntoAUnaPorNegocio() throws SQLException {
        assertThat(consulta("""
                SELECT p.tablename || '.' || p.policyname
                  FROM pg_policies p
                  JOIN information_schema.columns c
                    ON c.table_schema = 'public' AND c.table_name = p.tablename AND c.column_name = 'tenant_id'
                 WHERE p.schemaname = 'public'
                   AND coalesce(p.qual, '') IN ('true', '(true)')
                   -- la trampa es la abierta AL LADO de una por negocio; las tablas de
                   -- auth (users, password_resets) son abiertas a propósito y no tienen otra
                   AND EXISTS (SELECT 1 FROM pg_policies q
                                WHERE q.schemaname = 'public' AND q.tablename = p.tablename
                                  AND q.qual LIKE '%app.tenant_id%')""")).isEmpty();
    }
}
