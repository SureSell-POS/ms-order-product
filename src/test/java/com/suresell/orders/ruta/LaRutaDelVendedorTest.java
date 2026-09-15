package com.suresell.orders.ruta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.suresell.orders.infrastructure.config.FlywayPedidos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F6.1 (V6 pedidos, M-D2), como app_user y con la sesión de cada vendedor: la ruta de un día sale del plan de visitas; una
 * visita SIN_PEDIDO sin motivo se rechaza; app_user no escribe la ruta sin las funciones; otro negocio no ve nada.
 */
@Testcontainers
class LaRutaDelVendedorTest {

    static final String T = "f61-ruta";
    static final String OTRO = "f61-otro";
    static final String MARTES = "2026-09-15";
    static long ana;
    static long luis;
    static long delOtro;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrarYSembrar() throws SQLException {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).locations("classpath:db/migration").load().migrate();
        FlywayPedidos.configuracion(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).load().migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + T + "', 'Ruta', 'pro'), ('" + OTRO + "', 'Otro', 'pro')");
            ana = id(s, "INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES ('ana@f61.invalid', '!', '" + T + "', 'vendedor', 'Ana') RETURNING id");
            luis = id(s, "INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES ('luis@f61.invalid', '!', '" + T + "', 'vendedor', 'Luis') RETURNING id");
            delOtro = id(s, "INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES ('x@f61.invalid', '!', '" + OTRO + "', 'vendedor', 'X') RETURNING id");
            s.execute("SELECT set_config('app.user_id', '" + ana + "', false)");
            s.execute("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, creado_por) VALUES "
                    + "('" + T + "', 'M1', 'Martes uno', " + ana + ", 's'), ('" + T + "', 'M2', 'Martes dos', " + ana + ", 's'), "
                    + "('" + T + "', 'J1', 'Jueves', " + ana + ", 's'), ('" + T + "', 'Q1', 'Quincenal', " + ana + ", 's'), "
                    + "('" + T + "', 'L1', 'De Luis los martes', " + luis + ", 's'), ('" + T + "', 'I1', 'Inactivo martes', " + ana + ", 's')");
            s.execute("UPDATE clientes SET activo = false WHERE tenant_id = '" + T + "' AND documento = 'I1'");
        }
        try (Connection a = como(T, ana); Statement s = a.createStatement()) {
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('M1', 2::smallint, 'SEMANAL', NULL, NULL, NULL)");
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('M2', (2 | 8)::smallint, 'SEMANAL', NULL, '07:00', '10:00')");
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('J1', 8::smallint, 'SEMANAL', NULL, NULL, NULL)");
            // Quincenal anclada una semana antes: el martes 15 no toca; el 22, sí.
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('Q1', 2::smallint, 'QUINCENAL', DATE '2026-09-08', NULL, NULL)");
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('L1', 2::smallint, 'SEMANAL', NULL, NULL, NULL)");
            s.execute("SELECT pedidos.fn_plan_de_visita_fijar('I1', 2::smallint, 'SEMANAL', NULL, NULL, NULL)");
        }
    }

    private static long id(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection como(String negocio, long usuario) throws SQLException {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + negocio + "', false), set_config('app.user_id', '" + usuario + "', false)");
        }
        return c;
    }

    private static List<String> ruta(long vendedor, String fecha) throws SQLException {
        List<String> docs = new ArrayList<>();
        try (Connection c = como(T, vendedor); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT cliente_documento FROM pedidos.fn_ruta_del_dia(" + vendedor + ", DATE '" + fecha + "') ORDER BY 1")) {
            while (rs.next()) {
                docs.add(rs.getString(1));
            }
        }
        return docs;
    }

    @Test
    @DisplayName("🔴 F6.1: la ruta del martes de Ana son sus clientes activos de los martes; la quincenal toca en su semana; la de Luis es otra")
    void laRutaDelMartes() throws Exception {
        assertThat(ruta(ana, MARTES)).containsExactly("M1", "M2");
        assertThat(ruta(ana, "2026-09-22")).as("la quincenal toca una semana sí y otra no").containsExactly("M1", "M2", "Q1");
        assertThat(ruta(ana, "2026-09-17")).as("jueves").containsExactly("J1", "M2");
        assertThat(ruta(luis, MARTES)).containsExactly("L1");
        assertThat(ruta(ana, "2026-09-16")).as("miércoles: nadie").isEmpty();
    }

    @Test
    @DisplayName("🔴 F6.1: una visita SIN_PEDIDO sin motivo se rechaza; con motivo entra y el reintento sale repetido")
    void sinPedidoSinMotivo() throws Exception {
        try (Connection a = como(T, ana); Statement s = a.createStatement()) {
            try {
                s.executeQuery("SELECT * FROM pedidos.fn_visita_registrar('M1', 'SIN_PEDIDO', NULL, NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'f61-sm')");
                fail("entró una visita SIN_PEDIDO sin motivo");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514");
                assertThat(e.getMessage()).contains("ck_visitas_sin_pedido_con_motivo");
            }
            try (ResultSet rs = s.executeQuery("SELECT repetida FROM pedidos.fn_visita_registrar('M1', 'SIN_PEDIDO', 'PIDE_OTRO_DIA', NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'f61-ok')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }
            try (ResultSet rs = s.executeQuery("SELECT repetida FROM pedidos.fn_visita_registrar('M1', 'SIN_PEDIDO', 'PIDE_OTRO_DIA', NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'f61-ok')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("🔴 F6.1: app_user no escribe visitas, planes ni orden sin las funciones; otro negocio no ve las visitas")
    void aislamiento() throws Exception {
        try (Connection a = como(T, ana); Statement s = a.createStatement()) {
            s.executeQuery("SELECT * FROM pedidos.fn_visita_registrar('L1', 'CERRADO', NULL, NULL, NULL, NULL, false, NULL, NULL, NULL, NULL, now(), 'f61-otro-vendedor')").close();
            try (ResultSet rs = s.executeQuery("SELECT de_otro_vendedor FROM pedidos.visitas WHERE idempotency_key = 'f61-otro-vendedor'")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("D4: la visita al cliente de Luis queda marcada").isTrue();
            }
            for (String sql : new String[] {
                    "INSERT INTO pedidos.visitas (tenant_id, vendedor_id, cliente_documento, resultado, ocurrido_en, idempotency_key) VALUES ('" + T + "', " + ana + ", 'M1', 'CERRADO', now(), 'directa')",
                    "UPDATE pedidos.planes_de_visita SET dias = 1",
                    "DELETE FROM pedidos.orden_en_ruta"}) {
                try {
                    s.execute(sql);
                    fail("app_user escribió directo: " + sql);
                } catch (SQLException e) {
                    assertThat(e.getSQLState()).as(sql).isEqualTo("42501");
                }
            }
        }
        try (Connection o = como(OTRO, delOtro); Statement s = o.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pedidos.visitas")) {
            rs.next();
            assertThat(rs.getLong(1)).as("otro negocio no ve las visitas").isZero();
        }
        try (Connection d = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = d.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pedidos.visitas WHERE tenant_id = '" + T + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("control: están").isPositive();
        }
    }
}
