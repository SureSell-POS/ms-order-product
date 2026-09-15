package com.suresell.orders.ruta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F6.0b (V83): la marca de cambio del catálogo y de los clientes, con las condiciones de ECM. La base la pone para todo
 * escritor (aquí como app_user, que es como escriben -mt, core e inventario).
 */
@Testcontainers
class MarcaDeCambioTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrar() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration").load().migrate();
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

    private static String uno(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("🔴 F6.0b: las columnas del disparador de menu_products (UPDATE OF y WHEN) son exactamente las que viajan en el paquete")
    void elDisparadorVigilaLoQueViaja() throws Exception {
        String def;
        try (Connection c = dueno()) {
            def = uno(c, "SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t WHERE t.tgrelid = 'public.menu_products'::regclass "
                    + "AND t.tgname = 'trg_menu_products_marca_update'");
        }
        assertThat(def).as("el disparador existe").isNotNull();
        Matcher of = Pattern.compile("UPDATE OF (.+?) ON ").matcher(def);
        assertThat(of.find()).as(def).isTrue();
        List<String> columnasDelUpdate = new ArrayList<>();
        for (String col : of.group(1).split(",")) {
            columnasDelUpdate.add(col.trim());
        }
        Set<String> columnasDelWhen = new LinkedHashSet<>();
        Matcher when = Pattern.compile("\\(?old\\.(\\w+)\\)?(?:::[\\w ]+)?\\s+IS DISTINCT FROM\\s+\\(?new\\.(\\w+)", Pattern.CASE_INSENSITIVE).matcher(def);
        while (when.find()) {
            assertThat(when.group(1)).isEqualTo(when.group(2));
            columnasDelWhen.add(when.group(1));
        }
        assertThat(columnasDelUpdate).containsExactlyInAnyOrderElementsOf(MarcasDeCambio.CATALOGO_QUE_VIAJA);
        assertThat(columnasDelWhen).containsExactlyInAnyOrderElementsOf(MarcasDeCambio.CATALOGO_QUE_VIAJA);
    }

    @Test
    @DisplayName("🔴 F6.0b: como app_user, borrar dos productos deja dos lápidas que ve su negocio y no el otro; app_user no escribe lápidas")
    void lapidasComoAppUser() throws Exception {
        try (Connection d = dueno(); Statement s = d.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('f60b-a', 'A', 'pro'), ('f60b-b', 'B', 'pro') ON CONFLICT DO NOTHING");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                    + "('f60b-1', 'f60b-a', 'Uno', 100, true), ('f60b-2', 'f60b-a', 'Dos', 200, true), ('f60b-3', 'f60b-b', 'Tres', 300, true)");
        }
        try (Connection a = comoApp("f60b-a"); Statement s = a.createStatement()) {
            assertThat(s.executeUpdate("DELETE FROM menu_products WHERE id_product IN ('f60b-1', 'f60b-2')")).isEqualTo(2);
            assertThat(uno(a, "SELECT count(*) FROM menu_products_borrados WHERE id_product IN ('f60b-1', 'f60b-2')")).isEqualTo("2");
            for (String sql : new String[] {"INSERT INTO menu_products_borrados (tenant_id, id_product) VALUES ('f60b-a', 'x')",
                    "DELETE FROM menu_products_borrados", "UPDATE menu_products_borrados SET id_product = 'y'"}) {
                try {
                    s.execute(sql);
                    fail("app_user escribió lápidas: " + sql);
                } catch (SQLException e) {
                    assertThat(e.getSQLState()).as(sql).isEqualTo("42501");
                }
            }
        }
        try (Connection b = comoApp("f60b-b")) {
            assertThat(uno(b, "SELECT count(*) FROM menu_products_borrados")).as("otro negocio no las ve").isEqualTo("0");
        }
        try (Connection d = dueno()) {
            assertThat(uno(d, "SELECT count(*) FROM menu_products_borrados WHERE tenant_id = 'f60b-a'")).as("control: están").isEqualTo("2");
        }
    }

    @Test
    @DisplayName("🔴 F6.0b: como app_user, un producto nuevo y un cambio de precio mueven la marca; cambiar lo que no viaja, no")
    void marcaDelCatalogoComoAppUser() throws Exception {
        try (Connection d = dueno(); Statement s = d.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('f60b-c', 'C', 'pro') ON CONFLICT DO NOTHING");
        }
        try (Connection a = comoApp("f60b-c"); Statement s = a.createStatement()) {
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, actualizado_en) "
                    + "VALUES ('f60b-c1', 'f60b-c', 'Uno', 100, true, '2020-01-01')");
            assertThat(uno(a, "SELECT actualizado_en > now() - interval '1 minute' FROM menu_products WHERE id_product = 'f60b-c1'"))
                    .as("nace con la marca de la base, no con la que trae").isEqualTo("t");
            s.execute("UPDATE menu_products SET actualizado_en = '2020-01-01' WHERE id_product = 'f60b-c1'");
            s.execute("UPDATE menu_products SET creado_en_caja_por = 'Caja', price = price WHERE id_product = 'f60b-c1'");
            assertThat(uno(a, "SELECT actualizado_en::date FROM menu_products WHERE id_product = 'f60b-c1'")).isEqualTo("2020-01-01");
            s.execute("UPDATE menu_products SET price = 150 WHERE id_product = 'f60b-c1'");
            assertThat(uno(a, "SELECT actualizado_en > now() - interval '1 minute' FROM menu_products WHERE id_product = 'f60b-c1'")).isEqualTo("t");
        }
    }
}
