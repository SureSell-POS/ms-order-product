package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fase 0 (2026-09-09): el mismo navegador usado por dos negocios manda el
 * MISMO UUID de terminal, y la segunda venta salía con 500
 * ({@code duplicate key … terminals_pkey}). Este test reproduce el choque con
 * el SQL exacto que usa {@code TerminalRepository} y comprueba las dos cosas
 * que importan: el alta con {@code ON CONFLICT DO NOTHING} no revienta bajo
 * RLS, y la orden del segundo negocio se puede guardar con ese
 * {@code terminal_id}.
 */
@Testcontainers
class TerminalCompartidaEntreNegociosTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ALTA_ANTIGUA = """
            INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto)
            VALUES (?, ?, 'activo', ?, ?, 1)
            """;

    /** El mismo SQL de {@code TerminalRepository.darDeAltaSiNoExiste}. */
    private static final String ALTA_TOLERANTE = ALTA_ANTIGUA + " ON CONFLICT (id) DO NOTHING";

    private static final String CONTACTO = """
            UPDATE terminals SET ultima_conexion_en = ? WHERE id = ?
            """;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_pw");
    }

    private void setTenant(Connection c, String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }

    private int alta(Connection c, String sql, UUID terminal, String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            OffsetDateTime ahora = OffsetDateTime.now();
            ps.setObject(1, terminal);
            ps.setString(2, tenant);
            ps.setObject(3, ahora);
            ps.setObject(4, ahora);
            return ps.executeUpdate();
        }
    }

    private int contacto(Connection c, UUID terminal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CONTACTO)) {
            ps.setObject(1, OffsetDateTime.now());
            ps.setObject(2, terminal);
            return ps.executeUpdate();
        }
    }

    private String duenoDe(UUID terminal) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = admin.prepareStatement("SELECT tenant_id FROM terminals WHERE id = ?")) {
            ps.setObject(1, terminal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void elAltaAntiguaReventabaConElSegundoNegocio() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection a = appConnection()) {
            setTenant(a, "t-term-a");
            assertThat(alta(a, ALTA_ANTIGUA, terminal, "t-term-a")).isEqualTo(1);
        }
        try (Connection b = appConnection()) {
            setTenant(b, "t-term-b");
            // Bajo RLS el negocio B no ve la fila de A: el contacto no encuentra nada…
            assertThat(contacto(b, terminal)).isZero();
            // …y el alta de antes chocaba con la clave primaria global.
            assertThatThrownBy(() -> alta(b, ALTA_ANTIGUA, terminal, "t-term-b"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("terminals_pkey");
        }
    }

    @Test
    void elAltaTolerantePermiteQueElSegundoNegocioVenda() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection a = appConnection()) {
            setTenant(a, "t-term-c");
            assertThat(alta(a, ALTA_TOLERANTE, terminal, "t-term-c")).isEqualTo(1);
        }
        try (Connection b = appConnection()) {
            setTenant(b, "t-term-d");
            // 0 filas, sin excepción: el terminal sigue siendo de C.
            assertThat(alta(b, ALTA_TOLERANTE, terminal, "t-term-d")).isZero();
            assertThat(duenoDe(terminal)).isEqualTo("t-term-c");

            // Y la venta de D con ese terminal_id entra: la FK apunta a una fila
            // que existe (aunque D no la vea), que es lo que V35 prometió.
            try (PreparedStatement ps = b.prepareStatement(
                    "INSERT INTO orders (uuid_id, tenant_id, id_order, total, terminal_id) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, "t-term-d");
                ps.setLong(3, 1);
                ps.setBigDecimal(4, new java.math.BigDecimal("32000"));
                ps.setObject(5, terminal);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (Statement s = b.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM orders WHERE terminal_id = '" + terminal + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void elMismoNegocioNoDuplicaSuTerminal() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection a = appConnection()) {
            setTenant(a, "t-term-e");
            assertThat(alta(a, ALTA_TOLERANTE, terminal, "t-term-e")).isEqualTo(1);
            assertThat(alta(a, ALTA_TOLERANTE, terminal, "t-term-e")).isZero();
            assertThat(contacto(a, terminal)).isEqualTo(1);
        }
    }
}
