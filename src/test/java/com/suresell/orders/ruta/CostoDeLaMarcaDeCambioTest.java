package com.suresell.orders.ruta;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 💰 F6.0b (V83), A/B en la MISMA corrida, sin JIT y como app_user: lo que los disparadores de la marca le cuestan a la venta a
 * crédito de caja (el WHEN del cupo en accounts_receivable) y al guardado de producto como lo hace core (JPA merge: UPDATE de
 * todas las columnas con un cambio de precio, e INSERT). Con los disparadores y sin ellos (desactivados por el dueño entre
 * vueltas), alternando para que el caché y el ruido caigan igual en los dos lados. Guardas de ECM: +10 % la venta, +25 % el
 * guardado de producto. Compara la mediana de las vueltas de cada lado.
 */
@Testcontainers
class CostoDeLaMarcaDeCambioTest {

    static final String T = "perf-marca";
    static final int VUELTAS = 7;
    static final int POR_VUELTA = 400;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void sembrar() throws SQLException {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + T + "', '" + T + "', 'pro')");
            s.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) "
                    + "SELECT '" + T + "', 'C' || g, 'Cliente ' || g, 30, 's' FROM generate_series(1, 200) g");
            s.execute("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) "
                    + "SELECT '" + T + "-' || g, '" + T + "', now(), 999999999, 'C' || g, 'Cliente ' || g, 'ACTIVE', 0, now() FROM generate_series(1, 200) g");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                    + "SELECT '" + T + "-p' || g, '" + T + "', 'Producto ' || g, 1000, true FROM generate_series(1, 2000) g");
            s.execute("ANALYZE");
        }
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static Connection comoApp() throws SQLException {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + T + "', false)");
            s.execute("SET jit = off");
        }
        return c;
    }

    private static void disparadores(boolean encendidos, String... nombresPorTabla) throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            for (String t : nombresPorTabla) {
                String[] tablaYDisparador = t.split(":");
                s.execute("ALTER TABLE public." + tablaYDisparador[0] + (encendidos ? " ENABLE" : " DISABLE") + " TRIGGER " + tablaYDisparador[1]);
            }
        }
    }

    private static double mediana(double[] v) {
        double[] x = v.clone();
        Arrays.sort(x);
        return x[x.length / 2];
    }

    private int ventas = 0;

    /** POR_VUELTA ventas a crédito de caja, cada una en su transacción (autocommit), como entra una venta. Devuelve ms. */
    private double vueltaDeVentas(Connection app) throws SQLException {
        try (PreparedStatement ps = app.prepareStatement("INSERT INTO orders (uuid_id, tenant_id, status, payment_method, subtotal, total, synced, "
                + "is_printed, created_at, cliente_documento) VALUES (?, '" + T + "', 'pagado', 'CREDITO', 1000, 1000, true, false, now(), ?)")) {
            long t0 = System.nanoTime();
            for (int i = 0; i < POR_VUELTA; i++) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, "C" + (ventas++ % 200 + 1));
                ps.executeUpdate();
            }
            return (System.nanoTime() - t0) / 1e6;
        }
    }

    private int guardados = 0;

    /** POR_VUELTA guardados de producto como core: UPDATE de todas las columnas con otro precio, y un INSERT cada diez. Devuelve ms. */
    private double vueltaDeProductos(Connection app) throws SQLException {
        try (PreparedStatement up = app.prepareStatement("UPDATE menu_products SET name_product = ?, price = ?, active = true, category_id = NULL, "
                + "tenant_id = '" + T + "' WHERE id_product = ?");
             PreparedStatement in = app.prepareStatement("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                     + "VALUES (?, '" + T + "', 'Nuevo', 500, true)")) {
            long t0 = System.nanoTime();
            for (int i = 0; i < POR_VUELTA; i++) {
                int g = guardados++;
                if (i % 10 == 0) {
                    in.setString(1, T + "-n" + g);
                    in.executeUpdate();
                } else {
                    int p = g % 2000 + 1;
                    up.setString(1, "Producto " + p);
                    up.setInt(2, 1000 + g);
                    up.setString(3, T + "-p" + p);
                    up.executeUpdate();
                }
            }
            return (System.nanoTime() - t0) / 1e6;
        }
    }

    @Test
    @DisplayName("💰 F6.0b: la venta a crédito de caja con la marca cuesta como sin ella (+10 %) y el guardado de producto de core (+25 %)")
    void aB() throws Exception {
        String[] deLaVenta = {"accounts_receivable:trg_ar_marca_cliente_cupo", "accounts_receivable:trg_ar_marca_cliente_insert"};
        String[] delProducto = {"menu_products:trg_menu_products_marca_insert", "menu_products:trg_menu_products_marca_update"};
        double[] ventaCon = new double[VUELTAS];
        double[] ventaSin = new double[VUELTAS];
        double[] productoCon = new double[VUELTAS];
        double[] productoSin = new double[VUELTAS];
        try (Connection app = comoApp()) {
            // Calentar las dos rutas antes de medir.
            vueltaDeVentas(app);
            vueltaDeProductos(app);
            for (int v = 0; v < VUELTAS; v++) {
                boolean primeroCon = v % 2 == 0;
                for (boolean con : primeroCon ? new boolean[] {true, false} : new boolean[] {false, true}) {
                    disparadores(con, deLaVenta);
                    disparadores(con, delProducto);
                    (con ? ventaCon : ventaSin)[v] = vueltaDeVentas(app);
                    (con ? productoCon : productoSin)[v] = vueltaDeProductos(app);
                }
            }
        } finally {
            disparadores(true, deLaVenta);
            disparadores(true, delProducto);
        }
        double vc = mediana(ventaCon), vs = mediana(ventaSin), pc = mediana(productoCon), ps = mediana(productoSin);
        System.out.printf(java.util.Locale.ROOT,
                "── F6.0b A/B (%d vueltas × %d, mediana, ms) ── venta a crédito con marca %.1f · sin %.1f (%+.1f %%) · producto con marca %.1f · sin %.1f (%+.1f %%)%n",
                VUELTAS, POR_VUELTA, vc, vs, 100 * (vc / vs - 1), pc, ps, 100 * (pc / ps - 1));
        // Control: los cuatro disparadores quedan encendidos al final.
        try (Connection d = dueno(); Statement s = d.createStatement(); var rs = s.executeQuery(
                "SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_ar_marca_cliente_cupo', 'trg_ar_marca_cliente_insert', "
                        + "'trg_menu_products_marca_insert', 'trg_menu_products_marca_update') AND tgenabled = 'O'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(4);
        }
        assertThat(vc).as("venta a crédito: con la marca %.1f ms frente a sin ella %.1f ms", vc, vs).isLessThanOrEqualTo(vs * 1.10);
        assertThat(pc).as("guardado de producto: con la marca %.1f ms frente a sin ella %.1f ms", pc, ps).isLessThanOrEqualTo(ps * 1.25);
    }
}
