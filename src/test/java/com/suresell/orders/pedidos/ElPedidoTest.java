package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El pedido (V2 de la cadena `pedidos`, plan de mayoristas F5.1) desde fuera de su
 * propio bloque de cierre: lo que necesita DOS conexiones o el rol de la
 * aplicación. Todo como {@code app_user}.
 *
 * <ul>
 *   <li>Dos transiciones a la vez sobre el mismo pedido: la segunda espera al
 *       bloqueo y toma la secuencia siguiente, sin choque ni hueco.</li>
 *   <li>Un solo negocio: otro no lee nada, y nadie escribe fuera de las funciones.</li>
 * </ul>
 */
@Testcontainers
class ElPedidoTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String A = "qa-pedido-a";
    static final String B = "qa-pedido-b";
    static long usuarioA;
    static long usuarioB;

    @BeforeAll
    static void migrar() throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_pw'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        FlywayPedidos.configuracion(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + A + "', 'A', 'pro'), ('" + B + "', 'B', 'pro')");
            usuarioA = escalar(c, "INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('a@pedido.invalid', '!', '" + A + "', 'admin') RETURNING id");
            usuarioB = escalar(c, "INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('b@pedido.invalid', '!', '" + B + "', 'admin') RETURNING id");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES ('ped-aceite', '" + A + "', 'Aceite', 10000, true)");
            s.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES ('" + A + "', '900', 'Tienda', 8, 's')");
        }
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static long escalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Una conexión como la aplicación, con negocio y usuario de sesión. */
    private static Connection comoApp(String negocio, long usuario) throws SQLException {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + negocio + "', false), set_config('app.user_id', '" + usuario + "', false)");
        }
        return c;
    }

    private UUID pedido;

    @BeforeEach
    void crear() throws SQLException {
        try (Connection c = comoApp(A, usuarioA);
             PreparedStatement ps = c.prepareStatement("SELECT pedidos.fn_pedido_crear('ENVIADO', '900', 'vendedor', 'PREVENTA', NULL, NULL, NULL, "
                     + "'[{\"producto_id\":\"ped-aceite\",\"cantidad\":20}]'::jsonb, now(), ?)")) {
            ps.setString(1, "crear-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                pedido = (UUID) rs.getObject(1);
            }
        }
    }

    private static UUID transicionar(Connection c, UUID pedido, String tipo, String motivo, String lineas) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pedidos.fn_pedido_transicionar(?, ?, ?, NULL, ?::jsonb, now(), ?)")) {
            ps.setObject(1, pedido);
            ps.setString(2, tipo);
            ps.setString(3, motivo);
            ps.setString(4, lineas);
            ps.setString(5, tipo + "-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    @Test
    @DisplayName("🔴 dos transiciones a la vez: la segunda espera al bloqueo y toma la secuencia siguiente, sin choque ni hueco")
    void concurrencia() throws Exception {
        String linea = "[{\"linea_id\":\"" + lineaDe(pedido) + "\",\"cantidad\":18}]";
        try (Connection primera = comoApp(A, usuarioA); Connection segunda = comoApp(A, usuarioA)) {
            primera.setAutoCommit(false);
            transicionar(primera, pedido, "AJUSTADO", "SIN_EXISTENCIA", linea);   // bloquea el pedido y no confirma

            CompletableFuture<UUID> otra = CompletableFuture.supplyAsync(() -> {
                try {
                    return transicionar(segunda, pedido, "CONFIRMADO", null, null);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertThat(otra).as("la segunda tiene que esperar al bloqueo de la primera").isNotDone();
            primera.commit();
            otra.get(10, TimeUnit.SECONDS);
        }
        try (Connection c = dueno(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT secuencia || ':' || tipo FROM pedidos.pedidos_eventos WHERE pedido_id = '" + pedido + "' ORDER BY secuencia")) {
            List<String> eventos = new ArrayList<>();
            while (rs.next()) {
                eventos.add(rs.getString(1));
            }
            assertThat(eventos).containsExactly("1:ENVIADO", "2:AJUSTADO", "3:CONFIRMADO");
            assertThat(escalar(c, "SELECT count(*) FROM pedidos.v_pedidos_estado WHERE pedido_id = '" + pedido
                    + "' AND estado_guardado = estado_derivado AND estado_guardado = 'CONFIRMADO'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("🔴 un solo negocio: otro negocio no lee nada del pedido, ni por las tablas ni por las vistas")
    void otroNegocioNoLee() throws SQLException {
        try (Connection a = comoApp(A, usuarioA); Connection b = comoApp(B, usuarioB)) {
            for (String tabla : List.of("pedidos.pedidos", "pedidos.pedidos_lineas", "pedidos.pedidos_eventos",
                    "pedidos.v_pedidos_lineas", "pedidos.v_pedidos_estado")) {
                assertThat(escalar(a, "SELECT count(*) FROM " + tabla)).as("A en " + tabla).isPositive();
                assertThat(escalar(b, "SELECT count(*) FROM " + tabla)).as("B en " + tabla).isZero();
            }
            // Y B no lo mueve aunque sepa su id: para B no existe.
            assertThatThrownBy(() -> transicionar(b, pedido, "CANCELADO", "DUPLICADO", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no existe en este negocio");
        }
    }

    @Test
    @DisplayName("🔴 nadie escribe fuera de las funciones: la aplicación no inserta ni actualiza el pedido directamente")
    void soloLasFunciones() throws SQLException {
        try (Connection a = comoApp(A, usuarioA); Statement s = a.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE pedidos.pedidos SET estado = 'ENTREGADO' WHERE id = '" + pedido + "'"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO pedidos.pedidos_eventos (tenant_id, pedido_id, secuencia, tipo, actor, "
                    + "actor_tenant_id, ocurrido_en, idempotency_key) VALUES ('" + A + "', '" + pedido + "', 99, 'ENTREGADO', 'PROVEEDOR', '"
                    + A + "', now(), 'a-mano')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }

    private UUID lineaDe(UUID pedido) throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM pedidos.pedidos_lineas WHERE pedido_id = '" + pedido + "'")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }
}
