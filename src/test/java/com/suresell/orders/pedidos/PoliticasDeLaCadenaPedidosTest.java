package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Las guardas de políticas de los esquemas {@code pedidos} y {@code red}.
 *
 * <p>Hoy la cadena no tiene tablas, así que «cero hallazgos» no probaría nada:
 * sería verde por ausencia. Cada guarda se ejercita también contra un
 * fixture que DEBE atrapar, creado dentro de una transacción que se deshace
 * (el DDL de Postgres es transaccional): así la V2 de la red no es la primera
 * vez que muerden.
 *
 * <ol>
 *   <li><b>Un solo negocio</b>, la misma regla que
 *       {@code NingunaPoliticaDeUnSoloNegocioTest} en {@code public}: ninguna
 *       política nombra a un negocio, y ninguna tabla con {@code tenant_id} tiene
 *       una política abierta al lado de la suya.</li>
 *   <li><b>Dos partes solo en la lista blanca</b> (plan de la red B2B, §8.1): una
 *       política que nombra dos columnas de negocio solo puede existir en las
 *       tablas de la relación y del pedido.</li>
 * </ol>
 */
@Testcontainers
class PoliticasDeLaCadenaPedidosTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Tablas donde una política de dos partes está diseñada y probada (red §3.2, D4). */
    static final Set<String> LISTA_BLANCA_DOS_PARTES = Set.of(
            "red.relaciones_comerciales", "red.relaciones_eventos",
            "pedidos.pedidos", "pedidos.pedidos_lineas", "pedidos.pedidos_eventos", "pedidos.pedidos_eventos_lineas");

    @BeforeAll
    static void migrar() throws SQLException {
        try (Connection c = dueno(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE app_user LOGIN PASSWORD 'x'");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        FlywayPedidos.configuracion(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
    }

    private static Connection dueno() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static List<String> filas(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                r.add(rs.getString(1));
            }
        }
        return r;
    }

    /** Regla 1: una política que nombra un negocio, o una abierta junto a una por negocio. */
    static List<String> unSoloNegocio(Connection c) throws SQLException {
        return filas(c, """
                SELECT p.schemaname || '.' || p.tablename || '.' || p.policyname
                  FROM pg_policies p
                 WHERE p.schemaname IN ('pedidos', 'red')
                   AND ( coalesce(p.qual, '') ~* '''[a-z0-9-]+'''
                      OR coalesce(p.with_check, '') ~* '''[a-z0-9-]+'''
                      OR ( coalesce(p.qual, '') IN ('true', '(true)')
                           AND EXISTS (SELECT 1 FROM pg_policies q
                                        WHERE q.schemaname = p.schemaname AND q.tablename = p.tablename
                                          AND q.policyname <> p.policyname) ) )
                 ORDER BY 1""");
    }

    /** Regla 2: una política que nombra dos columnas de negocio fuera de la lista blanca. */
    static List<String> dosPartesFueraDeLaLista(Connection c) throws SQLException {
        List<String> r = new ArrayList<>();
        for (String f : filas(c, """
                SELECT p.schemaname || '.' || p.tablename || '|' || p.policyname
                  FROM pg_policies p
                 WHERE p.schemaname IN ('pedidos', 'red')
                   AND (SELECT count(*) FROM unnest(ARRAY['tenant_id', 'tenant_proveedor', 'tenant_comercio',
                                                          'comprador_tenant_id']) col
                         WHERE coalesce(p.qual, '') || ' ' || coalesce(p.with_check, '') ~ ('\\m' || col || '\\M')) >= 2
                 ORDER BY 1""")) {
            String tabla = f.substring(0, f.indexOf('|'));
            if (!LISTA_BLANCA_DOS_PARTES.contains(tabla)) {
                r.add(f.replace('|', '.'));
            }
        }
        return r;
    }

    @Test
    @DisplayName("🔴 un solo negocio: hoy limpio, y atrapa una política abierta junto a la del negocio")
    void unSoloNegocioMuerde() throws SQLException {
        try (Connection c = dueno()) {
            assertThat(unSoloNegocio(c)).isEmpty();

            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE red.__fixture_abierta (id INT, tenant_id TEXT)");
                s.execute("ALTER TABLE red.__fixture_abierta ENABLE ROW LEVEL SECURITY");
                s.execute("CREATE POLICY por_negocio ON red.__fixture_abierta USING "
                        + "(tenant_id = current_setting('app.tenant_id', true))");
                s.execute("CREATE POLICY abierta ON red.__fixture_abierta USING (true)");
                s.execute("CREATE TABLE pedidos.__fixture_nombra (id INT, tenant_id TEXT)");
                s.execute("CREATE POLICY de_shark ON pedidos.__fixture_nombra USING (tenant_id = 'shark-burger')");
                assertThat(unSoloNegocio(c)).containsExactly(
                        "pedidos.__fixture_nombra.de_shark", "red.__fixture_abierta.abierta");
            } finally {
                c.rollback();
            }
            assertThat(unSoloNegocio(c)).isEmpty();
        }
    }

    @Test
    @DisplayName("🔴 dos partes: hoy limpio, atrapa una fuera de la lista blanca y deja pasar la de la lista")
    void dosPartesMuerde() throws SQLException {
        try (Connection c = dueno()) {
            assertThat(dosPartesFueraDeLaLista(c)).isEmpty();

            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                String dosPartes = "(tenant_id = current_setting('app.tenant_id', true) "
                        + "OR comprador_tenant_id = current_setting('app.tenant_id', true))";
                s.execute("CREATE TABLE red.__fixture_catalogo (id INT, tenant_id TEXT, comprador_tenant_id TEXT)");
                s.execute("CREATE POLICY ve_el_comprador ON red.__fixture_catalogo USING " + dosPartes);
                // La misma política en una tabla de la lista blanca no es hallazgo.
                s.execute("CREATE TABLE pedidos.pedidos (id INT, tenant_id TEXT, comprador_tenant_id TEXT)");
                s.execute("CREATE POLICY dos_partes ON pedidos.pedidos USING " + dosPartes);
                assertThat(dosPartesFueraDeLaLista(c)).containsExactly("red.__fixture_catalogo.ve_el_comprador");
            } finally {
                c.rollback();
            }
            assertThat(dosPartesFueraDeLaLista(c)).isEmpty();
        }
    }
}
