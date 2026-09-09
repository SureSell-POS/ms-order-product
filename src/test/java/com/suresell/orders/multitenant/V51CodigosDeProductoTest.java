package com.suresell.orders.multitenant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V51 sobre un esquema que YA tiene productos (la lección de A: una migración
 * se prueba sobre datos, no sobre base vacía). Migra hasta V50, siembra
 * productos en dos negocios, aplica V51 y comprueba lo que la tabla promete:
 * el mismo código en dos negocios son dos filas; repetido en el mismo
 * negocio rebota (23505); cada negocio ve solo los suyos (RLS); un código
 * retirado deja de estorbar y puede volver a asignarse.
 */
@Testcontainers
class V51CodigosDeProductoTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static Flyway flyway(String hasta) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (hasta != null) {
            config = config.target(MigrationVersion.fromVersion(hasta));
        }
        return config.load();
    }

    private Connection app(String tenant) throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_pw");
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
        return c;
    }

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private int uno(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void ejecutar(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    private void producto(String tenant, String id, String nombre) throws SQLException {
        try (Connection a = admin()) {
            ejecutar(a, "INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING",
                    tenant, tenant);
            ejecutar(a, "INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                    + "VALUES (?, ?, ?, 1000, true)", id, tenant, nombre);
        }
    }

    private void codigo(Connection c, String codigo, String producto, String tipo) throws SQLException {
        ejecutar(c, "INSERT INTO codigos_de_producto (codigo, producto_id, tipo, fuente) VALUES (?, ?, ?, 'siembra')",
                codigo, producto, tipo);
    }

    @Test
    @DisplayName("🔴 V51 sobre productos existentes: mismo código en dos negocios sí, repetido en uno no, y cada uno ve lo suyo")
    void migraSobreProductosYAisla() throws SQLException {
        flyway("50").migrate();
        producto("t-v51-a", "A-ARROZ", "Arroz");
        producto("t-v51-b", "B-ARROZ", "Arroz");

        // Y ahora V51, sobre ese estado.
        flyway(null).migrate();

        try (Connection a = app("t-v51-a"); Connection b = app("t-v51-b")) {
            // El mismo EAN en dos negocios: dos filas, ninguna choca.
            codigo(a, "7702001234567", "A-ARROZ", "EAN");
            codigo(b, "7702001234567", "B-ARROZ", "EAN");
            codigo(a, "11", "A-ARROZ", "PLU");

            // Repetido en el MISMO negocio: la base lo rechaza con clave duplicada.
            assertThatThrownBy(() -> codigo(a, "7702001234567", "A-ARROZ", "EAN"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");

            // Cada negocio ve solo los suyos (RLS): A tiene 2, B tiene 1.
            assertThat(uno(a, "SELECT count(*) FROM codigos_de_producto")).isEqualTo(2);
            assertThat(uno(b, "SELECT count(*) FROM codigos_de_producto")).isEqualTo(1);
            // Y el DEFAULT puso el negocio de la sesión, no otro.
            assertThat(uno(a, "SELECT count(*) FROM codigos_de_producto WHERE tenant_id = 't-v51-a'")).isEqualTo(2);

            // Un tipo fuera del enum no entra (regla 9: enums, no texto libre).
            assertThatThrownBy(() -> codigo(a, "X-1", "A-ARROZ", "OTRO"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23514");

            // Retirar no borra: la fila sigue, y el mismo código puede volver a asignarse.
            ejecutar(a, "UPDATE codigos_de_producto SET retirado_en = now() WHERE codigo = '11'");
            assertThat(uno(a, "SELECT count(*) FROM codigos_de_producto WHERE codigo = '11'")).isEqualTo(1);
            codigo(a, "11", "A-ARROZ", "PLU");
            assertThat(uno(a, "SELECT count(*) FROM codigos_de_producto WHERE codigo = '11'")).isEqualTo(2);
            assertThat(uno(a, "SELECT count(*) FROM codigos_de_producto WHERE codigo = '11' AND retirado_en IS NULL"))
                    .isEqualTo(1);

            // app_user no puede borrar: un código se cierra, no se borra.
            assertThatThrownBy(() -> ejecutar(a, "DELETE FROM codigos_de_producto WHERE codigo = '11'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
        }

        try (Connection admin = admin()) {
            // El aislamiento es real, no decorativo: RLS activo, forzado, sin política abierta.
            assertThat(uno(admin, "SELECT count(*) FROM pg_class WHERE oid = 'public.codigos_de_producto'::regclass "
                    + "AND relrowsecurity AND relforcerowsecurity")).isEqualTo(1);
            assertThat(uno(admin, "SELECT count(*) FROM pg_policies WHERE tablename = 'codigos_de_producto' "
                    + "AND (qual = 'true' OR qual IS NULL)")).isEqualTo(0);
        }
    }
}
