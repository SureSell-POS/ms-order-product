package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
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
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F1.5 (V61): {@code fn_precios_para} en lote y {@code fn_precio_para} línea a
 * línea son la misma regla escrita dos veces. Esta prueba es lo que las ata: si
 * alguien corrige una y olvida la otra, se pone en rojo.
 *
 * <p>Cubre lo que decide un precio: escalas (debajo, en y encima del umbral),
 * vigencias (antes de abrir, abierta, cerrada), lista inactiva, cliente sin
 * lista, cliente inactivo, cliente inexistente, sin cliente, producto
 * inexistente, línea de lista cuyo producto no está en el catálogo, y dos
 * negocios con el mismo documento. Con {@code app_user} y con el dueño, que
 * salta RLS.
 */
@Testcontainers
class PreciosEnLoteTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String A = "qa-lote-a";
    static final String B = "qa-lote-b";
    static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");
    static final Instant T1 = Instant.parse("2026-09-10T12:00:00Z");
    static final Instant T2 = Instant.parse("2026-09-20T12:00:00Z");

    static final String[] PRODUCTOS = {"arroz", "aceite", "azucar", "solo-en-lista", "no-existe", "arroz-b"};
    static final int[] CANTIDADES = {0, 1, 9, 10, 11, 50};
    static final String[] DOCUMENTOS = {"900-con-lista", "900-lista-inactiva", "900-sin-lista", "900-inactivo",
            "900-no-existe", null};
    static final Instant[] MOMENTOS = {Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-05T00:00:00Z"),
            Instant.parse("2026-09-15T00:00:00Z"), Instant.parse("2026-09-25T00:00:00Z")};

    @BeforeAll
    static void migrarYSembrar() throws SQLException {
        try (Connection d = dueno(); Statement st = d.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'x'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        try (Connection d = dueno(); Statement st = d.createStatement()) {
            st.execute("""
                    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES
                      ('arroz', 'qa-lote-a', 'Arroz', 120000, true),
                      ('aceite', 'qa-lote-a', 'Aceite', 210000, true),
                      ('azucar', 'qa-lote-a', 'Azucar', 90000, true),
                      ('arroz-b', 'qa-lote-b', 'Arroz B', 5, true)""");
            String lista = """
                    INSERT INTO listas_precio (id, tenant_id, codigo, nombre, activa, creado_por)
                    VALUES ('%s', '%s', '%s', 'Lista', %s, 'prueba')""";
            st.execute(lista.formatted("11111111-1111-4111-8111-111111111111", A, "dist", "true"));
            st.execute(lista.formatted("22222222-2222-4222-8222-222222222222", A, "vieja", "false"));
            st.execute(lista.formatted("33333333-3333-4333-8333-333333333333", B, "dist", "true"));
            String linea = """
                    INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio,
                                                     vigente_desde, vigente_hasta, usuario_id, fuente, confianza)
                    VALUES ('%s', '%s', '%s', %d, %s, '%s', %s, 'prueba', 'declarado_comerciante', 1)""";
            String activa = "11111111-1111-4111-8111-111111111111";
            st.execute(linea.formatted(A, activa, "arroz", 1, "100000", T0, "NULL"));
            st.execute(linea.formatted(A, activa, "arroz", 10, "95000", T0, "NULL"));
            st.execute(linea.formatted(A, activa, "aceite", 1, "200000", T0, "'" + T1 + "'"));
            st.execute(linea.formatted(A, activa, "aceite", 1, "205000", T1, "NULL"));
            st.execute(linea.formatted(A, activa, "azucar", 10, "80000", T2, "NULL"));
            st.execute(linea.formatted(A, activa, "solo-en-lista", 1, "7000", T0, "NULL"));
            st.execute(linea.formatted(A, "22222222-2222-4222-8222-222222222222", "arroz", 1, "1", T0, "NULL"));
            st.execute(linea.formatted(B, "33333333-3333-4333-8333-333333333333", "arroz", 1, "2", T0, "NULL"));
            st.execute("""
                    INSERT INTO clientes (tenant_id, documento, nombre, lista_precio_id, activo, creado_por) VALUES
                      ('qa-lote-a', '900-con-lista', 'Con lista', '11111111-1111-4111-8111-111111111111', true, 'p'),
                      ('qa-lote-a', '900-lista-inactiva', 'Lista vieja', '22222222-2222-4222-8222-222222222222', true, 'p'),
                      ('qa-lote-a', '900-sin-lista', 'Sin lista', NULL, true, 'p'),
                      ('qa-lote-a', '900-inactivo', 'Inactivo', '11111111-1111-4111-8111-111111111111', false, 'p'),
                      ('qa-lote-b', '900-con-lista', 'Mismo documento en B', '33333333-3333-4333-8333-333333333333', true, 'p')""");
        }
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "x");
    }

    private static void negocio(Connection c, String tenant) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + tenant + "'");
        }
    }

    private static String fila(ResultSet rs) throws SQLException {
        return rs.getBigDecimal("precio").stripTrailingZeros().toPlainString() + " " + rs.getString("origen") + " "
                + rs.getObject("lista_precio_item_id") + " " + rs.getObject("lista_precio_id");
    }

    /** Lo que responde fn_precio_para para una línea, o «sin fila». */
    private static String unaPorUna(Connection c, String doc, String producto, int cantidad, Instant m)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM fn_precio_para(?, ?, ?, ?)")) {
            ps.setString(1, doc);
            ps.setString(2, producto);
            ps.setInt(3, cantidad);
            ps.setTimestamp(4, Timestamp.from(m));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fila(rs) : "sin fila";
            }
        }
    }

    /** Lo que responde el lote para todos los productos de golpe, en su orden. */
    private static List<String> enLote(Connection c, String doc, int cantidad, Instant m) throws SQLException {
        Integer[] cantidades = new Integer[PRODUCTOS.length];
        java.util.Arrays.fill(cantidades, cantidad);
        List<String> porProducto = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM fn_precios_para(?, ?, ?, ?)")) {
            Array productos = c.createArrayOf("text", PRODUCTOS);
            ps.setString(1, doc);
            ps.setArray(2, productos);
            ps.setArray(3, c.createArrayOf("integer", cantidades));
            ps.setTimestamp(4, Timestamp.from(m));
            java.util.Map<String, String> vistos = new java.util.HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vistos.put(rs.getString("producto_id"), fila(rs));
                }
            }
            for (String p : PRODUCTOS) {
                porProducto.add(vistos.getOrDefault(p, "sin fila"));
            }
        }
        return porProducto;
    }

    private static int compararTodo(Connection c) throws SQLException {
        int comparadas = 0;
        List<String> diferencias = new ArrayList<>();
        for (String doc : DOCUMENTOS) {
            for (int cantidad : CANTIDADES) {
                for (Instant m : MOMENTOS) {
                    List<String> lote = enLote(c, doc, cantidad, m);
                    for (int i = 0; i < PRODUCTOS.length; i++) {
                        String una = unaPorUna(c, doc, PRODUCTOS[i], cantidad, m);
                        comparadas++;
                        if (!Objects.equals(una, lote.get(i))) {
                            diferencias.add(doc + " · " + PRODUCTOS[i] + " x" + cantidad + " @" + m
                                    + ": una=" + una + " lote=" + lote.get(i));
                        }
                    }
                }
            }
        }
        assertThat(diferencias).as("el lote y fn_precio_para no dicen lo mismo").isEmpty();
        return comparadas;
    }

    @Test
    @DisplayName("🔴 paridad como app_user: 720 combinaciones, cero diferencias, y la escala y la vigencia se ejercen")
    void paridadComoApp() throws SQLException {
        try (Connection c = app()) {
            negocio(c, A);
            assertThat(compararTodo(c)).isEqualTo(DOCUMENTOS.length * CANTIDADES.length * MOMENTOS.length * PRODUCTOS.length);
            // Que no sea paridad de dos cosas vacías: hay precios de lista, escala y vigencia.
            assertThat(unaPorUna(c, "900-con-lista", "arroz", 9, MOMENTOS[2])).startsWith("100000 LISTA");
            assertThat(unaPorUna(c, "900-con-lista", "arroz", 10, MOMENTOS[2])).startsWith("95000 LISTA");
            assertThat(unaPorUna(c, "900-con-lista", "aceite", 1, MOMENTOS[1])).startsWith("200000 LISTA");
            assertThat(unaPorUna(c, "900-con-lista", "aceite", 1, MOMENTOS[2])).startsWith("205000 LISTA");
            assertThat(unaPorUna(c, "900-lista-inactiva", "arroz", 1, MOMENTOS[2])).startsWith("120000 BASE");
        }
    }

    @Test
    @DisplayName("🔴 paridad con el dueño (salta RLS) en el negocio A y en el B: ninguno toma nada del otro")
    void paridadSinRls() throws SQLException {
        try (Connection c = dueno()) {
            negocio(c, A);
            compararTodo(c);
            assertThat(enLote(c, "900-con-lista", 1, MOMENTOS[2]).get(0)).startsWith("100000 LISTA");
            negocio(c, B);
            compararTodo(c);
            // En B, `arroz` tiene línea en la lista DE B (2): la suya, nunca el 100000 de A.
            assertThat(enLote(c, "900-con-lista", 1, MOMENTOS[2]).get(0))
                    .startsWith("2 LISTA").endsWith("33333333-3333-4333-8333-333333333333");
            assertThat(enLote(c, "900-con-lista", 1, MOMENTOS[2]).get(5)).startsWith("5 BASE");
        }
    }
}
