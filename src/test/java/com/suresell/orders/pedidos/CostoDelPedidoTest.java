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
            // Uno de cada diez sigue por despachar, con entrega en la semana: lo que mira la bandeja.
            s.execute("UPDATE pedidos.pedidos SET estado = 'ENVIADO', fecha_entrega_prometida = current_date + (numero % 7)::int WHERE numero % 10 = 0");
            // Siete de cada diez ya se entregaron (la vista de pendiente de reversa los recorre).
            s.execute("UPDATE pedidos.pedidos SET estado = 'ENTREGADO' WHERE numero % 10 BETWEEN 3 AND 9");
            // Y uno de cada cien, con novedad: su prueba de entrega con diferencias (lo único que la vista recorre).
            s.execute("UPDATE pedidos.pedidos SET estado = 'ENTREGADO_CON_NOVEDAD' WHERE numero % 100 = 3");
            s.execute("INSERT INTO pedidos.entregas (tenant_id, pedido_id, evento_id, resultado, recibe_nombre, recibe_documento, registrado_por, ocurrido_en) "
                    + "SELECT e.tenant_id, e.pedido_id, e.id, 'ENTREGADO_CON_NOVEDAD', 'x', '1', p.capturado_por, now() "
                    + "FROM pedidos.pedidos_eventos e JOIN pedidos.pedidos p ON p.id = e.pedido_id WHERE p.estado = 'ENTREGADO_CON_NOVEDAD' AND e.tipo = 'DESPACHADO'");
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
            plan(s, "bandeja: por despachar, por entrega y número, primera página con totales", """
                    SELECT p.id, p.numero, p.estado, c.nombre, u.nombre, t.lineas, t.total
                      FROM pedidos.pedidos p
                      LEFT JOIN clientes c ON c.tenant_id = p.tenant_id AND c.documento = p.cliente_documento
                      LEFT JOIN users u ON u.tenant_id = p.tenant_id AND u.id = p.vendedor_id
                      LEFT JOIN LATERAL (SELECT count(*) AS lineas,
                                                sum(COALESCE(v.confirmada, v.pedida) * COALESCE(v.precio_confirmado, v.precio_visto)) AS total
                                           FROM pedidos.v_pedidos_lineas v
                                          WHERE v.tenant_id = p.tenant_id AND v.pedido_id = p.id) t ON true
                     WHERE p.tenant_id = 'perf-a' AND p.estado = ANY ('{ENVIADO,CONFIRMADO}')
                     ORDER BY COALESCE(p.fecha_entrega_prometida, 'infinity'::date), p.numero LIMIT 51""");
            plan(s, "bandeja: todo, por entrega y número, primera página", """
                    SELECT p.id, p.numero FROM pedidos.pedidos p
                     WHERE p.tenant_id = 'perf-a'
                     ORDER BY COALESCE(p.fecha_entrega_prometida, 'infinity'::date), p.numero LIMIT 51""");
            plan(s, "conteo de pendientes de reversa (F5.7)", """
                    SELECT count(*) FROM pedidos.pedidos p
                      JOIN pedidos.v_pedidos_pendiente_de_reversa pr ON pr.tenant_id = p.tenant_id AND pr.pedido_id = p.id
                     WHERE p.tenant_id = 'perf-a'""");
            plan(s, "bandeja: primera página con la marca de pendiente de reversa (F5.7)", """
                    SELECT p.id, p.numero, pr.valor
                      FROM pedidos.pedidos p
                      LEFT JOIN pedidos.v_pedidos_pendiente_de_reversa pr ON pr.tenant_id = p.tenant_id AND pr.pedido_id = p.id
                     WHERE p.tenant_id = 'perf-a'
                     ORDER BY COALESCE(p.fecha_entrega_prometida, 'infinity'::date), p.numero LIMIT 51""");
            for (String grupo : new String[] {"p.cliente_documento", "p.vendedor_id", "v.producto_id"}) {
                plan(s, "cumplimiento del mes por " + grupo + " (F5.11, sobre v_pedidos_lineas)", """
                        SELECT %s AS clave, count(DISTINCT p.id) AS pedidos, sum(v.pedida) AS pedidas, sum(v.confirmada) AS confirmadas,
                               sum(v.despachada) AS despachadas, sum(v.entregada) AS entregadas,
                               sum(v.pedida * COALESCE(v.precio_confirmado, v.precio_visto)) AS valor_pedido,
                               sum(COALESCE(v.entregada, 0) * COALESCE(v.precio_confirmado, v.precio_visto)) AS valor_entregado
                          FROM pedidos.pedidos p
                          JOIN pedidos.v_pedidos_lineas v ON v.tenant_id = p.tenant_id AND v.pedido_id = p.id
                         WHERE p.tenant_id = 'perf-a' AND p.ocurrido_en >= now() - interval '30 days'
                         GROUP BY 1""".formatted(grupo));
            }

            String unaPasada = """
                    WITH ped AS (
                        SELECT p.id, p.cliente_documento, p.vendedor_id FROM pedidos.pedidos p
                         WHERE p.tenant_id = 'perf-a' AND p.ocurrido_en >= now() - interval '30 days'
                    ), ult AS (
                        SELECT el.linea_id,
                               (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo IN ('CONFIRMADO', 'AJUSTADO')))[1] AS confirmada,
                               (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo = 'DESPACHADO'))[1] AS despachada,
                               max(e.secuencia) FILTER (WHERE e.tipo = 'DESPACHADO') AS sec_despacho,
                               (array_agg(el.cantidad ORDER BY e.secuencia DESC) FILTER (WHERE e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD')))[1] AS entregada,
                               max(e.secuencia) FILTER (WHERE e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD')) AS sec_entrega
                          FROM ped
                          JOIN pedidos.pedidos_eventos e ON e.tenant_id = 'perf-a' AND e.pedido_id = ped.id
                          JOIN pedidos.pedidos_eventos_lineas el ON el.tenant_id = e.tenant_id AND el.pedido_id = e.pedido_id AND el.evento_id = e.id
                         GROUP BY el.linea_id
                    )
                    SELECT %s AS clave, count(DISTINCT ped.id) AS pedidos, sum(l.cantidad_pedida) AS pedidas, sum(u.confirmada) AS confirmadas,
                           sum(u.despachada) AS despachadas, sum(CASE WHEN u.sec_entrega > u.sec_despacho THEN u.entregada END) AS entregadas,
                           sum(l.cantidad_pedida * COALESCE(l.precio_confirmado, l.precio_visto)) AS valor_pedido,
                           sum(COALESCE(CASE WHEN u.sec_entrega > u.sec_despacho THEN u.entregada END, 0) * COALESCE(l.precio_confirmado, l.precio_visto)) AS valor_entregado
                      FROM ped
                      JOIN pedidos.pedidos_lineas l ON l.tenant_id = 'perf-a' AND l.pedido_id = ped.id
                      LEFT JOIN ult u ON u.linea_id = l.id
                     GROUP BY 1""";
            plan(s, "cumplimiento del mes por cliente (F5.11, una pasada)", unaPasada.formatted("ped.cliente_documento"));
            String sobreLaVista = """
                    SELECT p.cliente_documento AS clave, count(DISTINCT p.id) AS pedidos, sum(v.pedida) AS pedidas, sum(v.confirmada) AS confirmadas,
                           sum(v.despachada) AS despachadas, sum(v.entregada) AS entregadas,
                           sum(v.pedida * COALESCE(v.precio_confirmado, v.precio_visto)) AS valor_pedido,
                           sum(COALESCE(v.entregada, 0) * COALESCE(v.precio_confirmado, v.precio_visto)) AS valor_entregado
                      FROM pedidos.pedidos p
                      JOIN pedidos.v_pedidos_lineas v ON v.tenant_id = p.tenant_id AND v.pedido_id = p.id
                     WHERE p.tenant_id = 'perf-a' AND p.ocurrido_en >= now() - interval '30 days'
                     GROUP BY 1""";
            String a = "(" + unaPasada.formatted("ped.cliente_documento") + ")";
            String b = "(" + sobreLaVista + ")";
            try (ResultSet rs = s.executeQuery("SELECT (SELECT count(*) FROM (" + a + " EXCEPT ALL " + b + ") x) + (SELECT count(*) FROM ("
                    + b + " EXCEPT ALL " + a + ") y), (SELECT count(*) FROM " + b + " z)")) {
                rs.next();
                System.out.println("── F5 costo: paridad una pasada frente a la vista: " + rs.getInt(1) + " filas distintas de " + rs.getInt(2) + " ──");
            }
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
        // Sin JIT: su compilación (cientos de ms) taparía el costo del plan, que es lo que se mide.
        s.execute("SET jit = off");
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
