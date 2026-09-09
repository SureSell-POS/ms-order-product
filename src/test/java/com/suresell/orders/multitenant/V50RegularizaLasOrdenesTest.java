package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V50 sobre una base que YA tiene el defecto: una orden que apunta al
 * terminal de otro negocio (lo que dejó el parche {@code ON CONFLICT DO
 * NOTHING} en staging). La primera versión de V50 hacía el INSERT de
 * regularización ANTES de cambiar la clave y chocaba con
 * {@code terminals_pkey}: el servicio de staging arrancó, falló y quedó caído
 * (2026-09-09 04:33). Este test migra hasta V49, siembra ese estado, y
 * entonces aplica V50.
 */
@Testcontainers
class V50RegularizaLasOrdenesTest {

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

    @Test
    @DisplayName("🔴 con una orden apuntando al terminal de otro negocio, V50 arranca y la regulariza")
    void migraSobreElDefecto() throws SQLException {
        flyway("49").migrate();

        UUID terminal = UUID.randomUUID();
        OffsetDateTime ahora = OffsetDateTime.now();
        // El negocio A registra el terminal (clave vieja: solo el UUID).
        try (Connection a = app("t-v50-a");
             PreparedStatement ps = a.prepareStatement(
                     "INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto) "
                     + "VALUES (?, ?, 'activo', ?, ?, 1)")) {
            ps.setObject(1, terminal);
            ps.setString(2, "t-v50-a");
            ps.setObject(3, ahora);
            ps.setObject(4, ahora);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        // El negocio B vende con ese mismo UUID: antes de V50 la FK lo dejaba
        // pasar porque la fila de A existe.
        try (Connection b = app("t-v50-b");
             PreparedStatement ps = b.prepareStatement(
                     "INSERT INTO orders (uuid_id, tenant_id, id_order, total, terminal_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "t-v50-b");
            ps.setLong(3, 1);
            ps.setBigDecimal(4, new BigDecimal("1000"));
            ps.setObject(5, terminal);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        // Y ahora V50, sobre ese estado. La primera versión reventaba aquí.
        flyway(null).migrate();

        try (Connection admin = admin()) {
            assertThat(uno(admin, "SELECT count(*) FROM terminals WHERE id = ?", terminal))
                    .as("una fila por negocio para el mismo UUID").isEqualTo(2);
            assertThat(uno(admin, "SELECT count(*) FROM terminals WHERE id = ? AND tenant_id = 't-v50-b'", terminal))
                    .as("B tiene ahora su propio terminal").isEqualTo(1);
            assertThat(uno(admin, "SELECT count(*) FROM orders o WHERE o.terminal_id IS NOT NULL AND NOT EXISTS "
                    + "(SELECT 1 FROM terminals t WHERE t.id = o.terminal_id AND t.tenant_id = o.tenant_id)"))
                    .as("ninguna orden apunta fuera de su negocio").isZero();
            assertThat(uno(admin, "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_orders_terminal_del_negocio'"))
                    .isEqualTo(1);
            assertThat(uno(admin, "SELECT count(*) FROM pg_constraint WHERE conname = 'orders_terminal_id_fkey'"))
                    .as("la FK vieja ya no está").isZero();
        }
    }
}
