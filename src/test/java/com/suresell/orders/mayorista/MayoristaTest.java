package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mayorista (ola 2): V44 y V45 medidas como {@code app_user}, que NO salta RLS.
 *
 * <p>Lo que se mide es comportamiento: que un cierre con fuente y sin
 * confianza ya no entra; que el precio sale de la lista del cliente y de su
 * escala; que un precio no se edita sino que se versiona; que una venta a
 * crédito por encima del cupo ENTRA y avisa; y que el libro de cartera ya no
 * se puede borrar desde la aplicación — que es exactamente lo que hacía
 * ms-core-app en cada operación antes de esta ola.
 */
@Testcontainers
class MayoristaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CLAVE_APP = "clave-de-prueba";
    private static final String NEGOCIO = "qa-delta";

    @BeforeAll
    static void migrar() throws SQLException {
        try (Connection d = comoDuenno(); Statement st = d.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD '" + CLAVE_APP + "'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        try (Connection d = comoDuenno(); Statement st = d.createStatement()) {
            st.execute("""
                    INSERT INTO menu_products (id_product, tenant_id, name_product, price, active)
                    VALUES ('bulto-arroz', 'qa-delta', 'Bulto de arroz 25 kg', 120000, true),
                           ('aceite-20l', 'qa-delta', 'Aceite 20 L', 210000, true),
                           ('bulto-arroz-otro', 'otro-negocio', 'Bulto de arroz', 999999, true)""");
        }
    }

    private static Connection comoDuenno() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection comoApp(String tenant) throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", CLAVE_APP);
        try (Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + tenant + "'");
        }
        return c;
    }

    private static String texto(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static BigDecimal numero(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        }
    }

    /** Una lista con el arroz a 100.000 desde 1 y a 95.000 desde 10, y un cliente con ella. */
    private static UUID listaConCliente(Connection app, String documento) throws SQLException {
        UUID lista = UUID.fromString(texto(app, """
                INSERT INTO listas_precio (codigo, nombre, creado_por)
                VALUES ('dist-%s', 'Distribuidor', 'jefe') RETURNING id::text""".formatted(documento)));
        try (Statement st = app.createStatement()) {
            st.execute("""
                    INSERT INTO listas_precio_items (lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza)
                    VALUES ('%1$s', 'bulto-arroz', 1, 100000, 'jefe', 'declarado_comerciante', 1),
                           ('%1$s', 'bulto-arroz', 10, 95000, 'jefe', 'acuerdo_documentado', 2)""".formatted(lista));
            st.execute("""
                    INSERT INTO clientes (documento, nombre, lista_precio_id, plazo_dias, creado_por)
                    VALUES ('%s', 'Tienda La Esquina', '%s', 30, 'jefe')""".formatted(documento, lista));
        }
        return lista;
    }

    // =====================================================================

    @Nested
    @DisplayName("V44: los dos CHECK que no se ejercían")
    class LosDosCheck {

        @Test
        @DisplayName("🔴 un cierre con fuente y SIN confianza ya no entra")
        void cierreSinConfianzaNoEntra() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                assertThatThrownBy(() -> st.execute("""
                        INSERT INTO daily_closures (id, tenant_id, user_name, opening_time, closure_date, qr_fuente, qr_confianza)
                        VALUES (gen_random_uuid(), 'qa-delta', 'cajero', now(), current_date, 'pos', NULL)"""))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_daily_closures_qr_coherencia");
            }
        }

        @Test
        @DisplayName("🔴 una orden con hash y sin origen de cadena ya no entra")
        void ordenConHashSinOrigenNoEntra() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                assertThatThrownBy(() -> st.execute("""
                        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed,
                                            created_at, hash_propio, cadena_origen)
                        VALUES (gen_random_uuid(), 'qa-delta', 'pagado', 'CASH', 1, 1, true, false, now(),
                                repeat('a', 64), NULL)"""))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ck_orders_cadena_coherente");
            }
        }
    }

    @Nested
    @DisplayName("V45: el precio sale de la lista del cliente")
    class ElPrecio {

        @Test
        @DisplayName("🔴 escala: 5 bultos a 100.000, 10 a 95.000; sin cliente, el precio base")
        void laEscalaDeLaLista() throws SQLException {
            try (Connection app = comoApp(NEGOCIO)) {
                listaConCliente(app, "900-1");
                assertThat(texto(app, "SELECT precio::text || ' ' || origen FROM fn_precio_para('900-1', 'bulto-arroz', 5)"))
                        .isEqualTo("100000.00 LISTA");
                assertThat(texto(app, "SELECT precio::text || ' ' || origen FROM fn_precio_para('900-1', 'bulto-arroz', 10)"))
                        .isEqualTo("95000.00 LISTA");
                // Producto que la lista no tiene: precio base del catálogo.
                assertThat(texto(app, "SELECT precio::text || ' ' || origen FROM fn_precio_para('900-1', 'aceite-20l', 3)"))
                        .isEqualTo("210000 BASE");
                // Cliente desconocido: base.
                assertThat(texto(app, "SELECT precio::text || ' ' || origen FROM fn_precio_para('nadie', 'bulto-arroz', 50)"))
                        .isEqualTo("120000 BASE");
            }
        }

        @Test
        @DisplayName("🔴 la lista de un negocio no le pone precio al otro: RLS")
        void laListaNoCruzaNegocios() throws SQLException {
            try (Connection app = comoApp(NEGOCIO)) {
                listaConCliente(app, "900-2");
            }
            try (Connection otro = comoApp("otro-negocio")) {
                assertThat(numero(otro, "SELECT count(*) FROM listas_precio")).isEqualByComparingTo("0");
                assertThat(numero(otro, "SELECT count(*) FROM clientes")).isEqualByComparingTo("0");
                // Mismo documento, otro negocio: ni la lista ni el producto del vecino existen para él.
                assertThat(texto(otro, "SELECT precio::text FROM fn_precio_para('900-2', 'bulto-arroz', 10)")).isNull();
                assertThat(texto(otro, "SELECT precio::text || ' ' || origen FROM fn_precio_para('900-2', 'bulto-arroz-otro', 10)"))
                        .isEqualTo("999999 BASE");
            }
        }

        @Test
        @DisplayName("🔴 un precio no se edita: se cierra la línea y se abre otra, y la vieja se queda")
        void unPrecioNoSeEdita() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                UUID lista = listaConCliente(app, "900-3");
                assertThatThrownBy(() -> st.execute(
                        "UPDATE listas_precio_items SET precio = 1 WHERE lista_id = '" + lista + "' AND cantidad_minima = 1"))
                        .hasMessageContaining("no se edita");
                assertThatThrownBy(() -> st.execute(
                        "DELETE FROM listas_precio_items WHERE lista_id = '" + lista + "'"))
                        .hasMessageContaining("permission denied");
                // El rito: cerrar y abrir.
                st.execute("UPDATE listas_precio_items SET vigente_hasta = now() WHERE lista_id = '" + lista
                        + "' AND cantidad_minima = 1 AND vigente_hasta IS NULL");
                st.execute("""
                        INSERT INTO listas_precio_items (lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza)
                        VALUES ('%s', 'bulto-arroz', 1, 102000, 'jefe', 'declarado_comerciante', 1)""".formatted(lista));
                assertThat(texto(app, "SELECT precio::text FROM fn_precio_para('900-3', 'bulto-arroz', 1)")).isEqualTo("102000.00");
                assertThat(numero(app, "SELECT count(*) FROM listas_precio_items WHERE lista_id = '" + lista + "' AND cantidad_minima = 1"))
                        .as("la línea vieja sigue ahí, cerrada").isEqualByComparingTo("2");
                // Y una cerrada no se reabre.
                assertThatThrownBy(() -> st.execute("UPDATE listas_precio_items SET vigente_hasta = NULL WHERE lista_id = '"
                        + lista + "' AND vigente_hasta IS NOT NULL"))
                        .hasMessageContaining("ya estaba cerrada");
            }
        }
    }

    @Nested
    @DisplayName("V45: la venta a crédito avisa, no bloquea")
    class LaVentaACredito {

        private void cuenta(Connection app, String documento, int cupo) throws SQLException {
            try (Statement st = app.createStatement()) {
                st.execute("""
                        INSERT INTO accounts_receivable (id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at)
                        VALUES ('%s', now(), %d, '%s', 'Tienda La Esquina', 'ACTIVE', 0, now())"""
                        .formatted(UUID.randomUUID(), cupo, documento));
            }
        }

        private UUID venderACredito(Connection app, String documento, int total) throws SQLException {
            UUID orden = UUID.randomUUID();
            try (Statement st = app.createStatement()) {
                st.execute("""
                        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, created_at, cliente_documento)
                        VALUES ('%s', 'qa-delta', 'pagado', 'CREDITO', %d, %d, true, false, now(), '%s')"""
                        .formatted(orden, total, total, documento));
            }
            return orden;
        }

        @Test
        @DisplayName("🔴 por encima del cupo la venta ENTRA, marcada, y el débito queda en el libro con su orden")
        void porEncimaDelCupoEntraYAvisa() throws SQLException {
            try (Connection app = comoApp(NEGOCIO)) {
                cuenta(app, "900-4", 100000);
                UUID dentro = venderACredito(app, "900-4", 60000);
                UUID fuera = venderACredito(app, "900-4", 60000);

                assertThat(texto(app, "SELECT excede_cupo::text FROM orders WHERE uuid_id = '" + dentro + "'")).isEqualTo("false");
                assertThat(texto(app, "SELECT excede_cupo::text FROM orders WHERE uuid_id = '" + fuera + "'")).isEqualTo("true");
                assertThat(numero(app, "SELECT total_debt FROM accounts_receivable WHERE customer_document = '900-4'"))
                        .isEqualByComparingTo("120000");
                assertThat(texto(app, """
                        SELECT type || ':' || amount::text || ':' || excede_cupo::text || ':' || registrado_por
                          FROM debt_transactions WHERE order_uuid = '%s'""".formatted(fuera)))
                        .isEqualTo("DEBIT:60000.00:true:sistema:venta");
                assertThat(numero(app, "SELECT count(*) FROM debt_transactions WHERE account_id = (SELECT id FROM accounts_receivable WHERE customer_document = '900-4')"))
                        .as("el libro tiene las dos ventas").isEqualByComparingTo("2");
            }
        }

        @Test
        @DisplayName("sin cuenta de cartera, o con la cuenta suspendida, la venta a crédito se niega en cristiano")
        void sinCuentaSeNiega() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                assertThatThrownBy(() -> venderACredito(app, "sin-cuenta", 1000))
                        .hasMessageContaining("no tiene cuenta de cartera");
                cuenta(app, "900-5", 100000);
                st.execute("UPDATE accounts_receivable SET status = 'SUSPENDED' WHERE customer_document = '900-5'");
                assertThatThrownBy(() -> venderACredito(app, "900-5", 1000))
                        .hasMessageContaining("suspendida");
                assertThat(numero(app, "SELECT count(*) FROM orders WHERE cliente_documento IN ('sin-cuenta', '900-5')"))
                        .isEqualByComparingTo("0");
            }
        }

        @Test
        @DisplayName("🔴 el libro de cartera ya no se puede borrar ni reescribir desde la aplicación")
        void elLibroNoSeBorra() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                cuenta(app, "900-6", 100000);
                venderACredito(app, "900-6", 5000);
                assertThatThrownBy(() -> st.execute("DELETE FROM debt_transactions"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
                assertThatThrownBy(() -> st.execute("UPDATE debt_transactions SET amount = 1"))
                        .hasMessageContaining("permission denied");
            }
        }

        @Test
        @DisplayName("una venta al contado no toca la cartera aunque traiga cliente")
        void alContadoNoTocaLaCartera() throws SQLException {
            try (Connection app = comoApp(NEGOCIO); Statement st = app.createStatement()) {
                cuenta(app, "900-7", 100000);
                st.execute("""
                        INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, is_printed, created_at, cliente_documento)
                        VALUES (gen_random_uuid(), 'qa-delta', 'pagado', 'CASH', 5000, 5000, true, false, now(), '900-7')""");
                assertThat(numero(app, "SELECT total_debt FROM accounts_receivable WHERE customer_document = '900-7'")).isEqualByComparingTo("0");
                assertThat(texto(app, "SELECT excede_cupo::text FROM orders WHERE cliente_documento = '900-7'")).isNull();
            }
        }
    }
}
