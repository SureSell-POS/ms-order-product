package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code fn_precio_para} decide un precio: el filtro por negocio va escrito en
 * la consulta, no delegado a RLS (plan de mayoristas, F0.6).
 *
 * <p>Se mide con el DUEÑO de la base, que salta RLS: es el caso en que delegar
 * el filtro falla. Dos negocios tienen un cliente con el mismo documento, que
 * es lo normal (un NIT compra a dos distribuidoras), y cada uno con su lista.
 * Con {@code app.tenant_id} fijado en el negocio A, la función tiene que
 * responder lo de A y nada de B.
 *
 * <p>Como {@code app_user} RLS ya aislaba y esta prueba no lo cambia: se
 * incluye para que el arreglo no rompa el camino que usa la venta.
 */
@Testcontainers
class PrecioConFiltroDeNegocioTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CLAVE_APP = "clave-de-prueba";
    private static final String A = "qa-precio-a";
    private static final String B = "qa-precio-b";
    private static final String DOCUMENTO = "900123456";

    @BeforeAll
    static void migrarYSembrar() throws SQLException {
        try (Connection d = comoDuenno(); Statement st = d.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD '" + CLAVE_APP + "'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        try (Connection d = comoDuenno(); Statement st = d.createStatement()) {
            // A vende arroz y aceite. B tiene en SU lista una línea para el
            // mismo identificador de producto: hoy `producto_id` es una
            // referencia blanda sin negocio, y con identidad por negocio (plan
            // SKU) coincidir será lo normal.
            st.execute("""
                    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
                    VALUES ('arroz-25', '%1$s', 'Arroz 25 kg', 120000, true),
                           ('aceite-20', '%1$s', 'Aceite 20 L', 210000, true)""".formatted(A));
            for (String[] n : new String[][] {{A, "100000"}, {B, "1"}}) {
                st.execute("""
                        WITH l AS (
                          INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por)
                          VALUES ('%1$s', 'dist', 'Distribuidor', 'prueba') RETURNING id),
                        i AS (
                          INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio,
                                                           usuario_id, fuente, confianza)
                          SELECT '%1$s', id, 'arroz-25', 1, %2$s, 'prueba', 'declarado_comerciante', 1 FROM l)
                        INSERT INTO clientes (tenant_id, documento, nombre, lista_precio_id, creado_por)
                        SELECT '%1$s', '%3$s', 'Tienda La Esquina', id, 'prueba' FROM l"""
                        .formatted(n[0], n[1], DOCUMENTO));
            }
        }
    }

    private static Connection comoDuenno() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection comoApp(String tenant) throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", CLAVE_APP);
        fijar(c, tenant);
        return c;
    }

    private static void fijar(Connection c, String tenant) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + tenant + "'");
        }
    }

    /** Precio y origen, o el error de la base como texto: lo que se mide es qué responde. */
    private static String precio(Connection c, String producto) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT precio, origen FROM fn_precio_para('" + DOCUMENTO + "', '"
                     + producto + "', 1, now())")) {
            if (!rs.next()) {
                return "sin fila";
            }
            BigDecimal p = rs.getBigDecimal(1);
            String salida = p.stripTrailingZeros().toPlainString() + " " + rs.getString(2);
            return rs.next() ? salida + " y más filas" : salida;
        } catch (SQLException e) {
            return "ERROR: " + e.getMessage().lines().findFirst().orElse("");
        }
    }

    @Test
    @DisplayName("🔴 sin RLS, el cliente de A cobra con la lista de A y no con la de B")
    void laListaDeOtroNegocioNoDecide() throws SQLException {
        try (Connection d = comoDuenno()) {
            fijar(d, A);
            assertThat(precio(d, "arroz-25")).isEqualTo("100000 LISTA");
        }
    }

    @Test
    @DisplayName("🔴 sin RLS, un producto sin línea da el precio base de A y no un error por el cliente de B")
    void elPrecioBaseNoSeRompeConElMismoDocumentoEnOtroNegocio() throws SQLException {
        try (Connection d = comoDuenno()) {
            fijar(d, A);
            assertThat(precio(d, "aceite-20")).isEqualTo("210000 BASE");
        }
    }

    @Test
    @DisplayName("sin negocio fijado no se resuelve nada, ni con el dueño")
    void sinNegocioNoHayPrecio() throws SQLException {
        try (Connection d = comoDuenno()) {
            assertThat(precio(d, "arroz-25")).isEqualTo("sin fila");
        }
    }

    @Test
    @DisplayName("como app_user el camino de la venta responde igual que antes")
    void comoAppNoCambia() throws SQLException {
        try (Connection app = comoApp(A)) {
            assertThat(precio(app, "arroz-25")).isEqualTo("100000 LISTA");
            assertThat(precio(app, "aceite-20")).isEqualTo("210000 BASE");
        }
        try (Connection app = comoApp(B)) {
            assertThat(precio(app, "aceite-20")).isEqualTo("sin fila");
        }
    }
}
