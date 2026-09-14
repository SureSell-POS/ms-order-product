package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V69: el disparador que numera las ventas ({@code set_order_id_order}) con
 * {@code search_path} fijo y sin EXECUTE para PUBLIC. Lo que su cierre no puede
 * comprobar como dueño, porque depende del rol que vende: todo aquí va como
 * {@code app_user}, y la MISMA medición se hace con la base en v68 y en v69.
 *
 * <ul>
 *   <li>Consecutivos por sede y por negocio, dos ventas a la vez en la misma sede
 *       y una venta que se deshace: idénticos antes y después.</li>
 *   <li>El disparador sigue disparando con el EXECUTE revocado (las ventas entran)
 *       y {@code app_user} ya no puede llamarlo directamente.</li>
 *   <li>Un contador temporal con el mismo nombre y {@code pg_temp} delante en la
 *       sesión: antes el disparador lo usa; después no.</li>
 *   <li>Controles negativos sobre v69: quitar el {@code search_path} vuelve a abrir
 *       la sombra, y devolver el EXECUTE a PUBLIC vuelve a dejar llamarlo.</li>
 * </ul>
 */
@Testcontainers
class NumeracionDeVentasTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String A = "qa-numeracion-a";
    static final String B = "qa-numeracion-b";

    @Test
    @DisplayName("🔴 V69: la numeración de ventas es la misma antes y después, y la sombra de pg_temp deja de funcionar")
    void antesYDespues() throws Exception {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE app_user LOGIN PASSWORD 'app_pw'");
        }
        migrarHasta("68");
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + A + "', 'A', 'pro'), ('" + B + "', 'B', 'pro')");
        }

        Map<String, Object> antes = medir("v68");
        migrarHasta("69");
        Map<String, Object> despues = medir("v69");
        System.out.println("── V69 antes:   " + antes + " ──");
        System.out.println("── V69 después: " + despues + " ──");

        // Lo que no puede cambiar: los números.
        for (String caso : new String[] {"secuencia", "concurrencia", "deshecha"}) {
            assertThat(despues.get(caso)).as(caso).isEqualTo(antes.get(caso));
        }
        assertThat(antes.get("secuencia")).isEqualTo("A1=1,2 A2=1 B1=1");
        assertThat(antes.get("concurrencia")).isEqualTo("3,4 (la segunda esperó)");
        assertThat(antes.get("deshecha")).isEqualTo("5 deshecha, la siguiente 5");

        // Lo que sí cambia: el agujero y quién puede llamarla.
        assertThat(antes.get("sombra")).as("en v68 el disparador usa la tabla temporal").isEqualTo("900001, public sin tocar");
        assertThat(despues.get("sombra")).as("en v69 el número sale de public").isEqualTo("6, temporal sin tocar");
        assertThat(antes.get("llamadaDirecta")).as("en v68 PUBLIC tiene EXECUTE").isNotEqualTo("42501");
        assertThat(despues.get("llamadaDirecta")).as("en v69 app_user no puede llamarla").isEqualTo("42501");

        // Controles negativos sobre v69: la prueba ve el agujero si se reabre.
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("ALTER FUNCTION public.set_order_id_order() RESET search_path");
        }
        assertThat(medir("v69-sin-search-path").get("sombra")).as("sin search_path vuelve la sombra").isEqualTo("900001, public sin tocar");
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("ALTER FUNCTION public.set_order_id_order() SET search_path = pg_catalog, public, pg_temp");
            s.execute("GRANT EXECUTE ON FUNCTION public.set_order_id_order() TO PUBLIC");
        }
        assertThat(medir("v69-con-execute").get("llamadaDirecta")).as("con EXECUTE a PUBLIC se deja llamar").isNotEqualTo("42501");
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("REVOKE EXECUTE ON FUNCTION public.set_order_id_order() FROM PUBLIC");
        }
    }

    /**
     * Una medición completa sobre sedes nuevas de la fase, para que los números
     * esperados sean los mismos en cada una.
     */
    private static Map<String, Object> medir(String fase) throws Exception {
        long a1;
        long a2;
        long b1;
        try (Connection c = dueno()) {
            a1 = sede(c, A, fase + "-A1");
            a2 = sede(c, A, fase + "-A2");
            b1 = sede(c, B, fase + "-B1");
        }
        Map<String, Object> m = new LinkedHashMap<>();

        try (Connection a = comoApp(A); Connection b = comoApp(B)) {
            m.put("secuencia", "A1=" + vender(a, A, a1) + "," + vender(a, A, a1) + " A2=" + vender(a, A, a2) + " B1=" + vender(b, B, b1));
        }

        try (Connection primera = comoApp(A); Connection segunda = comoApp(A)) {
            primera.setAutoCommit(false);
            long n1 = vender(primera, A, a1);   // bloquea la fila del contador y no confirma
            CompletableFuture<Long> otra = CompletableFuture.supplyAsync(() -> {
                try {
                    return vender(segunda, A, a1);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            boolean espero = !otra.isDone();
            primera.commit();
            m.put("concurrencia", n1 + "," + otra.get(10, TimeUnit.SECONDS) + (espero ? " (la segunda esperó)" : " (NO esperó)"));
        }

        try (Connection a = comoApp(A)) {
            a.setAutoCommit(false);
            long deshecha = vender(a, A, a1);
            a.rollback();
            a.setAutoCommit(true);
            m.put("deshecha", deshecha + " deshecha, la siguiente " + vender(a, A, a1));
        }

        // Llamarla a mano: sin EXECUTE es 42501; con él, Postgres la rechaza por
        // ser de disparador (otro código). Lo que se mide es cuál de los dos.
        try (Connection a = comoApp(A); Statement s = a.createStatement()) {
            s.executeQuery("SELECT public.set_order_id_order()").close();
            m.put("llamadaDirecta", "sin error");
        } catch (SQLException e) {
            m.put("llamadaDirecta", e.getSQLState());
        }

        // La sombra, en una conexión nueva de app_user: tabla temporal con el
        // nombre del contador y pg_temp delante en la sesión.
        long realAntes = contadorReal(a1);
        try (Connection a = comoApp(A); Statement s = a.createStatement()) {
            s.execute("CREATE TEMP TABLE tenant_order_counters (tenant_id TEXT, site_id BIGINT, last_id BIGINT, PRIMARY KEY (tenant_id, site_id))");
            s.execute("GRANT ALL ON pg_temp.tenant_order_counters TO PUBLIC");
            s.execute("INSERT INTO pg_temp.tenant_order_counters VALUES ('" + A + "', " + a1 + ", 900000)");
            s.execute("SET search_path = pg_temp, public");
            long n = vender(a, A, a1);
            long temporal;
            try (ResultSet rs = s.executeQuery("SELECT last_id FROM pg_temp.tenant_order_counters")) {
                rs.next();
                temporal = rs.getLong(1);
            }
            long realDespues = contadorReal(a1);
            if (n == 900001 && temporal == 900001 && realDespues == realAntes) {
                m.put("sombra", "900001, public sin tocar");
            } else if (temporal == 900000 && realDespues == realAntes + 1 && n == realDespues) {
                m.put("sombra", n + ", temporal sin tocar");
            } else {
                m.put("sombra", "inesperado: n=" + n + " temporal=" + temporal + " real " + realAntes + "->" + realDespues);
            }
        }
        return m;
    }

    private static long vender(Connection c, String negocio, long sede) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.orders (uuid_id, tenant_id, status, site_id) VALUES (?, ?, 'pagado', ?) RETURNING id_order")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, negocio);
            ps.setLong(3, sede);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long sede(Connection c, String negocio, String codigo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sites (tenant_id, name, code, is_default) VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, negocio);
            ps.setString(2, codigo);
            ps.setString(3, codigo);
            ps.setBoolean(4, false);   // las ventas llevan sede; ninguna sede por defecto que se repita entre fases
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long contadorReal(long sede) throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT last_id FROM public.tenant_order_counters WHERE site_id = " + sede)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void migrarHasta(String version) {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static Connection comoApp(String negocio) throws SQLException {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + negocio + "', false)");
        }
        return c;
    }
}
