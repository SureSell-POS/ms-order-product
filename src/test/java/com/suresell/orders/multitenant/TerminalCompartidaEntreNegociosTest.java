package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fase 0 (2026-09-09): el mismo navegador usado por dos negocios manda el
 * MISMO UUID de terminal, y la segunda venta salía con 500
 * ({@code duplicate key … terminals_pkey}). Desde V50 la identidad del
 * terminal es (negocio, UUID): cada negocio tiene su fila y la base no admite
 * una orden que apunte a un terminal de otro negocio.
 *
 * <p>Se prueba con el SQL exacto que usa {@code TerminalRepository} y por
 * comportamiento: lo que la base acepta y lo que rechaza.
 */
@Testcontainers
class TerminalCompartidaEntreNegociosTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** El mismo SQL de {@code TerminalRepository.darDeAltaSiNoExiste}. */
    private static final String ALTA = """
            INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto)
            VALUES (?, ?, 'activo', ?, ?, 1)
            ON CONFLICT (tenant_id, id) DO NOTHING
            """;

    private static final String CONTACTO = "UPDATE terminals SET ultima_conexion_en = ? WHERE id = ?";

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

    private int alta(Connection c, UUID terminal, String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ALTA)) {
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

    private int venta(Connection c, String tenant, long idOrder, UUID terminal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (uuid_id, tenant_id, id_order, total, terminal_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, tenant);
            ps.setLong(3, idOrder);
            ps.setBigDecimal(4, new BigDecimal("32000"));
            ps.setObject(5, terminal);
            return ps.executeUpdate();
        }
    }

    /** Cuántas filas tiene ese UUID en `terminals`, vistas sin RLS. */
    private int filasDe(UUID terminal) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = admin.prepareStatement("SELECT count(*) FROM terminals WHERE id = ?")) {
            ps.setObject(1, terminal);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("el mismo UUID en dos negocios son dos filas, y cada uno encuentra la suya")
    void dosNegociosMismoUuid() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection a = appConnection()) {
            setTenant(a, "t-term-a");
            assertThat(alta(a, terminal, "t-term-a")).isEqualTo(1);
        }
        try (Connection b = appConnection()) {
            setTenant(b, "t-term-b");
            // Bajo RLS el negocio B no ve la fila de A: su contacto no encuentra nada…
            assertThat(contacto(b, terminal)).isZero();
            // …y su alta ya no choca: es SU fila.
            assertThat(alta(b, terminal, "t-term-b")).isEqualTo(1);
            assertThat(contacto(b, terminal)).isEqualTo(1);
        }
        assertThat(filasDe(terminal)).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 una orden no puede apuntar a un terminal de otro negocio: la base la rechaza")
    void laOrdenNoPuedeApuntarFueraDeSuNegocio() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection c = appConnection()) {
            setTenant(c, "t-term-c");
            assertThat(alta(c, terminal, "t-term-c")).isEqualTo(1);
        }
        try (Connection d = appConnection()) {
            setTenant(d, "t-term-d");
            // El terminal existe (para C), pero D no lo ha registrado: antes de
            // V50 esta venta entraba apuntando a la fila de C.
            assertThatThrownBy(() -> venta(d, "t-term-d", 1, terminal))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_orders_terminal_del_negocio");
            // En cuanto D lo registra a su nombre, vende.
            assertThat(alta(d, terminal, "t-term-d")).isEqualTo(1);
            assertThat(venta(d, "t-term-d", 1, terminal)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("el mismo negocio no duplica su terminal; el segundo alta devuelve 0 sin excepción")
    void elMismoNegocioNoDuplicaSuTerminal() throws SQLException {
        UUID terminal = UUID.randomUUID();
        try (Connection a = appConnection()) {
            setTenant(a, "t-term-e");
            assertThat(alta(a, terminal, "t-term-e")).isEqualTo(1);
            assertThat(alta(a, terminal, "t-term-e")).isZero();
            assertThat(contacto(a, terminal)).isEqualTo(1);
        }
        assertThat(filasDe(terminal)).isEqualTo(1);
    }

    @Test
    @DisplayName("una orden sin terminal (cliente viejo) sigue entrando")
    void sinTerminalSigueEntrando() throws SQLException {
        try (Connection f = appConnection()) {
            setTenant(f, "t-term-f");
            assertThat(venta(f, "t-term-f", 1, null)).isEqualTo(1);
        }
    }
}
