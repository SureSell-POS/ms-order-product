package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F5.2: el estado GUARDADO del pedido es siempre el DERIVADO de
 * sus eventos (el tipo del de mayor `secuencia`), sobre 1.000 secuencias aleatorias
 * de eventos válidos, sacadas del propio catálogo `pedidos.transiciones`.
 *
 * <p>Cada paso elige una transición permitida para el proveedor desde el estado
 * actual, con su motivo cuando lo exige, cantidades por línea al azar donde van, y
 * un `ocurrido_en` desordenado a propósito (hasta 5 días atrás): el orden lo manda la
 * secuencia, no el reloj del celular. La semilla sale en la salida para repetir un
 * fallo exactamente.
 */
@Testcontainers
class EstadoGuardadoEsDerivadoTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String NEGOCIO = "qa-propiedad-pedido";
    static final int SECUENCIAS = 1000;
    static final int PASOS_MAXIMOS = 8;
    static final Map<String, List<String>> MOTIVOS = Map.of(
            "RETENIDO", List.of("CUPO_EXCEDIDO", "FACTURA_VENCIDA", "MORA"),
            "LIBERADO", List.of("PAGO_RECIBIDO", "ACUERDO_DE_PAGO", "AUTORIZADO_POR_ADMIN"),
            "RECHAZADO", List.of("SIN_EXISTENCIA", "DUPLICADO"),
            "CANCELADO", List.of("CLIENTE_DESISTIO", "DUPLICADO"),
            "ENTREGA_FALLIDA", List.of("CERRADO", "SIN_DINERO", "DIRECCION_ERRADA", "RECHAZO_EN_PUERTA"),
            "ENTREGADO_CON_NOVEDAD", List.of("FALTANTE", "AVERIA", "VENCIDO"));
    static long usuario;
    /**
     * La forma de {@code v_pedidos_lineas} anterior a la de LATERAL (DISTINCT ON sobre
     * todas las cantidades), guardada aquí para comprobar que la nueva da las mismas filas.
     */
    static final String V_PEDIDOS_LINEAS_ANTERIOR = """
            WITH ultimos AS (
                SELECT DISTINCT ON (el.linea_id, grupo)
                       el.linea_id, e.secuencia, el.cantidad,
                       CASE WHEN e.tipo IN ('CONFIRMADO', 'AJUSTADO') THEN 'confirmada'
                            WHEN e.tipo = 'DESPACHADO' THEN 'despachada'
                            WHEN e.tipo IN ('ENTREGADO', 'ENTREGADO_CON_NOVEDAD') THEN 'entregada'
                            ELSE 'recibida' END AS grupo
                  FROM pedidos.pedidos_eventos_lineas el
                  JOIN pedidos.pedidos_eventos e ON e.tenant_id = el.tenant_id AND e.pedido_id = el.pedido_id AND e.id = el.evento_id
                 ORDER BY el.linea_id, grupo, e.secuencia DESC
            )
            SELECT l.tenant_id, l.pedido_id, l.id AS linea_id, l.n, l.producto_id,
                   l.cantidad_pedida                                     AS pedida,
                   c.cantidad                                            AS confirmada,
                   d.cantidad                                            AS despachada,
                   -- Una entrega vale si es posterior al último despacho (un ENTREGA_FALLIDA y un segundo despacho la anulan).
                   CASE WHEN en.secuencia > d.secuencia THEN en.cantidad END AS entregada,
                   CASE WHEN r.secuencia > d.secuencia THEN r.cantidad END   AS recibida,
                   COALESCE(c.cantidad, l.cantidad_pedida)
                     - COALESCE(CASE WHEN en.secuencia > d.secuencia THEN en.cantidad END, 0) AS pendiente,
                   l.precio_visto, l.precio_confirmado, l.precio_origen, l.lista_precio_item_id
              FROM pedidos.pedidos_lineas l
              LEFT JOIN ultimos c  ON c.linea_id = l.id  AND c.grupo = 'confirmada'
              LEFT JOIN ultimos d  ON d.linea_id = l.id  AND d.grupo = 'despachada'
              LEFT JOIN ultimos en ON en.linea_id = l.id AND en.grupo = 'entregada'
              LEFT JOIN ultimos r  ON r.linea_id = l.id  AND r.grupo = 'recibida'
            """;

    @BeforeAll
    static void migrar() throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_pw'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        FlywayPedidos.configuracion(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + NEGOCIO + "', 'P', 'pro')");
            try (ResultSet rs = s.executeQuery("INSERT INTO users (email, password_hash, tenant_id, role) "
                    + "VALUES ('p@propiedad.invalid', '!', '" + NEGOCIO + "', 'admin') RETURNING id")) {
                rs.next();
                usuario = rs.getLong(1);
            }
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                    + "('prop-1', '" + NEGOCIO + "', 'Uno', 1000, true), ('prop-2', '" + NEGOCIO + "', 'Dos', 2500, true)");
            s.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) "
                    + "VALUES ('" + NEGOCIO + "', 'prop-cliente', 'Cliente', 15, 's')");
        }
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @Test
    @DisplayName("🔴 F5.2: 1.000 secuencias aleatorias de eventos válidos, 0 diferencias entre estado guardado y derivado")
    void estadoGuardadoEsDerivado() throws SQLException {
        long semilla = System.nanoTime();
        System.out.println("── F5.2 semilla: " + semilla + " ──");
        Random azar = new Random(semilla);
        int eventos = 0;
        int diferencias = 0;
        List<String> primeras = new ArrayList<>();

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw")) {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + NEGOCIO + "', false), set_config('app.user_id', '" + usuario + "', false)");
            }
            for (int i = 0; i < SECUENCIAS; i++) {
                UUID pedido = crear(c, azar, i);
                eventos++;
                String estado = estadoGuardado(c, pedido);
                for (int paso = 0; paso < PASOS_MAXIMOS; paso++) {
                    List<String> posibles = transiciones(c, estado);
                    if (posibles.isEmpty()) {
                        break;   // estado final para el proveedor
                    }
                    String tipo = posibles.get(azar.nextInt(posibles.size()));
                    transicionar(c, azar, pedido, tipo, i, paso);
                    eventos++;
                    estado = estadoGuardado(c, pedido);
                    if (!tipo.equals(estado)) {
                        diferencias++;
                        if (primeras.size() < 5) {
                            primeras.add("pedido " + i + " paso " + paso + ": aplique " + tipo + " y quedo " + estado);
                        }
                    }
                }
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT count(*) FILTER (WHERE estado_guardado IS DISTINCT FROM estado_derivado), count(*) FROM pedidos.v_pedidos_estado")) {
                rs.next();
                diferencias += rs.getInt(1);
                assertThat(rs.getInt(2)).as("pedidos creados").isEqualTo(SECUENCIAS);
            }
        }
        // Y las cantidades por línea: la vista de LATERAL da exactamente las filas de la forma anterior.
        int lineas;
        int distintas;
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "WITH nueva AS (SELECT * FROM pedidos.v_pedidos_lineas), anterior AS (" + V_PEDIDOS_LINEAS_ANTERIOR + ") "
                        + "SELECT (SELECT count(*) FROM nueva), "
                        + "(SELECT count(*) FROM (SELECT * FROM nueva EXCEPT ALL SELECT * FROM anterior) x) "
                        + "+ (SELECT count(*) FROM (SELECT * FROM anterior EXCEPT ALL SELECT * FROM nueva) y)")) {
            rs.next();
            lineas = rs.getInt(1);
            distintas = rs.getInt(2);
        }
        System.out.println("── F5.2: v_pedidos_lineas frente a la forma anterior: " + lineas + " líneas, " + distintas + " filas distintas ──");
        assertThat(lineas).as("la comparación mira líneas de verdad").isEqualTo(SECUENCIAS * 2);
        assertThat(distintas).as("semilla " + semilla + ": la vista nueva da las mismas filas").isZero();
        // F5.11: las cantidades en una pasada (Pedidos.ULTIMAS_CANTIDADES) son las de la vista, línea por línea.
        int lineasInforme;
        int distintasInforme;
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             PreparedStatement ps = c.prepareStatement("WITH ped AS (SELECT id FROM pedidos.pedidos WHERE tenant_id = ?), " + Pedidos.ULTIMAS_CANTIDADES
                     + ", unaPasada AS (SELECT l.id, u.confirmada, u.despachada, u.entregada FROM pedidos.pedidos_lineas l LEFT JOIN ult u ON u.linea_id = l.id"
                     + " WHERE l.tenant_id = ?), vista AS (SELECT linea_id, confirmada, despachada, entregada FROM pedidos.v_pedidos_lineas WHERE tenant_id = ?)"
                     + " SELECT (SELECT count(*) FROM unaPasada), (SELECT count(*) FROM (SELECT * FROM unaPasada EXCEPT ALL SELECT * FROM vista) a)"
                     + " + (SELECT count(*) FROM (SELECT * FROM vista EXCEPT ALL SELECT * FROM unaPasada) b),"
                     + " (SELECT count(*) FROM vista WHERE entregada IS NOT NULL)")) {
            ps.setString(1, NEGOCIO);
            ps.setString(2, NEGOCIO);
            ps.setString(3, NEGOCIO);
            ps.setString(4, NEGOCIO);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                lineasInforme = rs.getInt(1);
                distintasInforme = rs.getInt(2);
                System.out.println("── F5.2: cantidades en una pasada (F5.11) frente a la vista: " + lineasInforme + " líneas, " + distintasInforme
                        + " distintas; " + rs.getInt(3) + " con entrega ──");
            }
        }
        assertThat(lineasInforme).isEqualTo(SECUENCIAS * 2);
        assertThat(distintasInforme).as("semilla " + semilla + ": el informe cuenta lo mismo que la vista").isZero();
        System.out.println("── F5.2: " + SECUENCIAS + " pedidos, " + eventos + " eventos, " + diferencias + " diferencias ──");
        assertThat(primeras).as("semilla " + semilla).isEmpty();
        assertThat(diferencias).as("semilla " + semilla).isZero();
        assertThat(eventos).as("hubo recorridos, no solo creaciones").isGreaterThan(SECUENCIAS * 2);
    }

    private static UUID crear(Connection c, Random azar, int i) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pedidos.fn_pedido_crear(?, 'prop-cliente', 'vendedor', 'PREVENTA', NULL, NULL, NULL, "
                + "'[{\"producto_id\":\"prop-1\",\"cantidad\":10},{\"producto_id\":\"prop-2\",\"cantidad\":4}]'::jsonb, ?, ?)")) {
            ps.setString(1, azar.nextBoolean() ? "ENVIADO" : "CREADO_BORRADOR");
            ps.setTimestamp(2, desordenado(azar));
            ps.setString(3, "prop-" + i);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static void transicionar(Connection c, Random azar, UUID pedido, String tipo, int i, int paso) throws SQLException {
        String motivo = null;
        String lineas = null;
        if (MOTIVOS.containsKey(tipo)) {
            List<String> m = MOTIVOS.get(tipo);
            motivo = m.get(azar.nextInt(m.size()));
        }
        if (tipo.equals("AJUSTADO") || tipo.equals("ENTREGADO_CON_NOVEDAD") || (azar.nextBoolean()
                && (tipo.equals("CONFIRMADO") || tipo.equals("DESPACHADO") || tipo.equals("ENTREGADO")))) {
            UUID linea = unaLinea(c, pedido, azar);
            boolean precio = tipo.equals("AJUSTADO") && azar.nextBoolean();
            if (tipo.equals("AJUSTADO")) {
                motivo = precio ? "ERROR_DE_PRECIO" : "SIN_EXISTENCIA";
            }
            // Una entrega no pasa de lo despachado en su línea (V4): se sortea dentro de ese tope.
            // Y ENTREGADO es todo lo despachado: una diferencia es ENTREGADO_CON_NOVEDAD.
            int tope = ENTREGAS.contains(tipo) ? despachada(c, linea) : 10;
            int cantidad = tipo.equals("ENTREGADO") ? tope : azar.nextInt(tope + 1);
            lineas = "[{\"linea_id\":\"" + linea + "\",\"cantidad\":" + cantidad
                    + (precio ? ",\"precio\":" + (500 + azar.nextInt(3000)) : "") + "}]";
        }
        if (ENTREGAS.contains(tipo)) {
            // Desde V4 de pedidos una entrega va con su prueba, por fn_pedido_entregar.
            boolean fallida = tipo.equals("ENTREGA_FALLIDA");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pedidos.fn_pedido_entregar(?, ?, ?, NULL, ?::jsonb, ?, ?, NULL, NULL, ?, ?)")) {
                ps.setObject(1, pedido);
                ps.setString(2, tipo);
                ps.setString(3, motivo);
                ps.setString(4, lineas);
                ps.setString(5, fallida ? null : "Quien recibe");
                ps.setString(6, fallida ? null : "1000" + i);
                ps.setTimestamp(7, desordenado(azar));
                ps.setString(8, "prop-" + i + "-" + paso);
                ps.executeQuery().close();
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pedidos.fn_pedido_transicionar(?, ?, ?, NULL, ?::jsonb, ?, ?)")) {
            ps.setObject(1, pedido);
            ps.setString(2, tipo);
            ps.setString(3, motivo);
            ps.setString(4, lineas);
            ps.setTimestamp(5, desordenado(azar));
            ps.setString(6, "prop-" + i + "-" + paso);
            ps.executeQuery().close();
        }
    }

    static final java.util.Set<String> ENTREGAS = java.util.Set.of("ENTREGADO", "ENTREGADO_CON_NOVEDAD", "ENTREGA_FALLIDA");

    private static int despachada(Connection c, UUID linea) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(despachada, 0) FROM pedidos.v_pedidos_lineas WHERE tenant_id = ? AND linea_id = ?")) {
            ps.setString(1, NEGOCIO);
            ps.setObject(2, linea);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Un momento cualquiera de los últimos 5 días: el reloj no ordena nada. */
    private static Timestamp desordenado(Random azar) {
        return Timestamp.from(Instant.now().minusSeconds(azar.nextInt(5 * 24 * 3600)));
    }

    private static List<String> transiciones(Connection c, String estado) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT hacia FROM pedidos.transiciones WHERE desde = ? AND actor = 'PROVEEDOR' ORDER BY hacia")) {
            ps.setString(1, estado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    r.add(rs.getString(1));
                }
            }
        }
        return r;
    }

    private static String estadoGuardado(Connection c, UUID pedido) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT estado FROM pedidos.pedidos WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, NEGOCIO);
            ps.setObject(2, pedido);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static UUID unaLinea(Connection c, UUID pedido, Random azar) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM pedidos.pedidos_lineas WHERE tenant_id = ? AND pedido_id = ? ORDER BY n")) {
            ps.setString(1, NEGOCIO);
            ps.setObject(2, pedido);
            List<UUID> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject(1));
                }
            }
            return ids.get(azar.nextInt(ids.size()));
        }
    }
}
