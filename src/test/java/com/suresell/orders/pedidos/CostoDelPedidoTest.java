package com.suresell.orders.pedidos;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 💰 Cuánto cuesta UN pedido cuando el negocio (y la base) tiene muchos: dos
 * negocios con 10.000 pedidos de 5 líneas cada uno, confirmados y despachados
 * (200.000 cantidades por evento). Deja en la salida el plan de las lecturas que
 * hace la escritura ({@code fn_pedido_escribir_evento} lee {@code v_pedidos_lineas})
 * y el detalle, y el tiempo de una transición real como {@code app_user}.
 *
 * <p>La pregunta: ¿mover un pedido cuesta según ese pedido o según toda la base?
 * Dentro de la DEFINER el dueño salta RLS, así que «toda la base» son todos los negocios.
 */
@Testcontainers
class CostoDelPedidoTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static long usuario;
    static UUID unPedido;
    static UUID enviado;

    @BeforeAll
    static void sembrar() throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_pw'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        var pedidos = FlywayPedidos.configuracion(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        String hasta = System.getenv("PEDIDOS_HASTA");
        if (hasta != null && !hasta.isBlank()) {
            pedidos.target(hasta);
        }
        pedidos.load().migrate();
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            for (String t : new String[] {"perf-a", "perf-b"}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "', '" + t + "', 'pro')");
                try (ResultSet rs = s.executeQuery("INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('u@" + t
                        + ".invalid', '!', '" + t + "', 'admin') RETURNING id")) {
                    rs.next();
                    if (t.equals("perf-a")) {
                        usuario = rs.getLong(1);
                    }
                }
                s.execute("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) SELECT '" + t + "', 'D' || g, 'C', 's' FROM generate_series(1, 500) g");
                s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                        + "SELECT '" + t + "-p' || g, '" + t + "', 'P' || g, 1000, true FROM generate_series(1, 5) g");
                s.execute("INSERT INTO pedidos.pedidos (tenant_id, numero, cliente_documento, origen, capturado_por, estado, ocurrido_en, idempotency_key) "
                        + "SELECT '" + t + "', g, 'D' || (g % 500 + 1), 'vendedor', (SELECT id FROM users WHERE tenant_id = '" + t + "'), "
                        + "'DESPACHADO', now() - interval '1 day', 'k' || g FROM generate_series(1, 10000) g");
                s.execute("INSERT INTO pedidos.contadores_de_pedidos (tenant_id, ultimo) VALUES ('" + t + "', 10000)");
                s.execute("INSERT INTO pedidos.pedidos_lineas (tenant_id, pedido_id, n, producto_id, cantidad_pedida, precio_visto) "
                        + "SELECT p.tenant_id, p.id, n, p.tenant_id || '-p' || n, 10, 1000 FROM pedidos.pedidos p, generate_series(1, 5) n WHERE p.tenant_id = '" + t + "'");
                for (String[] e : new String[][] {{"1", "ENVIADO"}, {"2", "CONFIRMADO"}, {"3", "DESPACHADO"}}) {
                    s.execute("INSERT INTO pedidos.pedidos_eventos (tenant_id, pedido_id, secuencia, tipo, actor, actor_tenant_id, ocurrido_en, idempotency_key) "
                            + "SELECT p.tenant_id, p.id, " + e[0] + ", '" + e[1] + "', 'PROVEEDOR', p.tenant_id, now() - interval '1 day', p.idempotency_key || ':" + e[1] + "' "
                            + "FROM pedidos.pedidos p WHERE p.tenant_id = '" + t + "'");
                }
                s.execute("INSERT INTO pedidos.pedidos_eventos_lineas (tenant_id, pedido_id, evento_id, linea_id, cantidad) "
                        + "SELECT e.tenant_id, e.pedido_id, e.id, l.id, 10 FROM pedidos.pedidos_eventos e "
                        + "JOIN pedidos.pedidos_lineas l ON l.tenant_id = e.tenant_id AND l.pedido_id = e.pedido_id "
                        + "WHERE e.tenant_id = '" + t + "' AND e.tipo IN ('CONFIRMADO', 'DESPACHADO')");
            }
            s.execute("ANALYZE");
            try (ResultSet rs = s.executeQuery("SELECT id FROM pedidos.pedidos WHERE tenant_id = 'perf-a' AND numero = 7777")) {
                rs.next();
                unPedido = (UUID) rs.getObject(1);
            }
        }
        try (Connection c = comoApp(); PreparedStatement ps = c.prepareStatement("SELECT pedidos.fn_pedido_crear('ENVIADO', 'D1', 'vendedor', 'PREVENTA', "
                + "NULL, NULL, NULL, '[{\"producto_id\":\"perf-a-p1\",\"cantidad\":3},{\"producto_id\":\"perf-a-p2\",\"cantidad\":4}]'::jsonb, now(), 'medir')")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                enviado = (UUID) rs.getObject(1);
            }
        }
    }

    @Test
    @DisplayName("💰 mover y leer UN pedido con 200.000 cantidades por evento en la base")
    void medir() throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            plan(s, "lectura que hace la escritura (como el dueño de la DEFINER)", """
                    SELECT l.id, COALESCE(v.confirmada, v.pedida)
                      FROM pedidos.pedidos_lineas l
                      JOIN pedidos.v_pedidos_lineas v ON v.tenant_id = l.tenant_id AND v.linea_id = l.id
                     WHERE l.tenant_id = 'perf-a' AND l.pedido_id = '%s'""".formatted(unPedido));
            plan(s, "detalle: cantidades de un pedido", """
                    SELECT * FROM pedidos.v_pedidos_lineas WHERE tenant_id = 'perf-a' AND pedido_id = '%s'""".formatted(unPedido));
        }
        try (Connection c = comoApp(); PreparedStatement ps = c.prepareStatement(
                "SELECT pedidos.fn_pedido_transicionar(?, 'CONFIRMADO', NULL, NULL, NULL, now(), 'medir-confirmar')")) {
            ps.setObject(1, enviado);
            long t0 = System.nanoTime();
            ps.executeQuery().close();
            System.out.printf("── F5 costo: fn_pedido_transicionar CONFIRMADO como app_user: %.1f ms ──%n", (System.nanoTime() - t0) / 1e6);
        }
    }

    private static void plan(Statement s, String nombre, String sql) throws SQLException {
        StringBuilder salida = new StringBuilder("── F5 costo: " + nombre + " ──\n");
        try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
            while (rs.next()) {
                salida.append(rs.getString(1)).append('\n');
            }
        }
        System.out.println(salida);
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static Connection comoApp() throws SQLException {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', 'perf-a', false), set_config('app.user_id', '" + usuario + "', false)");
        }
        return c;
    }
}
