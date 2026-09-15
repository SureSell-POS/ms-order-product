package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.suresell.orders.application.usecase.BusquedaDeProductos;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F5.8e: costo de {@link BusquedaDeProductos#CONSULTA} con 5.000 y 50.000 productos de UN negocio (más 50.000 de otro),
 * cada uno con un código, medido con {@code EXPLAIN ANALYZE}, {@code SET jit = off} (staging no compila con JIT) y como
 * {@code app_user} con RLS, como corre la aplicación. Umbral de ECM: si con 50.000 pasa de ~50 ms, se propone un índice
 * GIN trigram antes de crearlo. Imprime los tiempos; no falla por lentitud, solo por no medir.
 *
 * <p><b>Medido el 2026-09-15:</b> 5.000 → ~10 ms; 50.000 → 62–68 ms en máquina libre (100–125 ms con carga). Con un índice
 * GIN trigram sobre la misma expresión, como {@code app_user} el planificador NO lo usa: {@code textlike} ({@code ~~}) no
 * es leakproof y con RLS activa no puede ir como condición de índice. Como dueño sin RLS sí lo usa (9 ms). Por eso no hay
 * migración con índice: no serviría a la aplicación. El índice aquí vive solo dentro del contenedor, como prueba del
 * mecanismo.
 */
@Testcontainers
class CostoDeLaBusquedaTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void sembrar() throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String[] t : new String[][] {{"busq-5k", "5000"}, {"busq-50k", "50000"}, {"busq-otro", "50000"}}) {
                st.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t[0] + "','C','pro')");
                st.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                        + "SELECT '" + t[0] + "-' || g, '" + t[0] + "', (ARRAY['Tornillo','Café','Arandela','Pañal','Cemento'])[1 + g % 5] || ' referencia ' || g, 1000, g % 10 <> 0 "
                        + "FROM generate_series(1, " + t[1] + ") g");
                st.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) "
                        + "SELECT '" + t[0] + "', '77' || lpad(g::text, 10, '0'), '" + t[0] + "-' || g, 'panel' FROM generate_series(1, " + t[1] + ") g");
            }
            st.execute("ANALYZE menu_products");
            st.execute("ANALYZE codigos_de_producto");
        }
    }

    private static double medir(Statement st, Connection c, String negocio, String q, Map<String, Double> tiempos, String etiqueta) throws Exception {
        String qn = q.toLowerCase().replace("é", "e");
        double ultimo = -1;
        for (int vuelta = 0; vuelta < 3; vuelta++) {
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + BusquedaDeProductos.CONSULTA)) {
                ps.setString(1, q);
                ps.setString(2, negocio);
                ps.setString(3, q + "%");
                ps.setString(4, negocio);
                ps.setString(5, "%" + qn + "%");
                ps.setString(6, qn + "%");
                ps.setString(7, "%" + qn + "%");
                ps.setString(8, negocio);
                ps.setInt(9, 20);
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder plan = new StringBuilder();
                    while (rs.next()) {
                        String l = rs.getString(1);
                        plan.append(l).append('\n');
                        if (l.startsWith("Execution Time:")) {
                            ultimo = Double.parseDouble(l.replaceAll("[^0-9.]", ""));
                        }
                    }
                    if (vuelta == 2) {
                        System.out.println("── plan " + etiqueta + " ──\n" + plan);
                    }
                }
            }
        }
        tiempos.put(etiqueta, ultimo);
        return ultimo;
    }

    @Test
    @DisplayName("F5.8e: costo de buscar con 5.000 y 50.000 productos (sin JIT, como app_user con RLS)")
    void costo() throws Exception {
        Map<String, Double> tiempos = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            for (String negocio : new String[] {"busq-5k", "busq-50k"}) {
                st.execute("SELECT set_config('app.tenant_id', '" + negocio + "', false)");
                medir(st, c, negocio, "tornillo", tiempos, negocio + " nombre que contiene 'tornillo'");
                medir(st, c, negocio, "referencia 4999", tiempos, negocio + " nombre poco frecuente");
                medir(st, c, negocio, "770000004", tiempos, negocio + " prefijo de código");
                medir(st, c, negocio, "inexistente", tiempos, negocio + " nada coincide");
            }
        }
        System.out.println("── búsqueda, tiempos (ms, sin JIT, app_user) ── " + tiempos);

        // La propuesta, SOLO dentro de este contenedor (no es una migración): índice GIN trigram sobre la misma expresión.
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            st.execute("CREATE INDEX ix_menu_products_nombre_busqueda ON public.menu_products USING gin ("
                    + com.suresell.orders.application.usecase.NormalizacionDeBusqueda.sql("name_product") + " gin_trgm_ops)");
            st.execute("ANALYZE menu_products");
        }
        Map<String, Double> conIndice = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            for (String negocio : new String[] {"busq-5k", "busq-50k"}) {
                st.execute("SELECT set_config('app.tenant_id', '" + negocio + "', false)");
                medir(st, c, negocio, "tornillo", conIndice, negocio + " nombre que contiene 'tornillo' (índice)");
                medir(st, c, negocio, "referencia 4999", conIndice, negocio + " nombre poco frecuente (índice)");
                medir(st, c, negocio, "770000004", conIndice, negocio + " prefijo de código (índice)");
                medir(st, c, negocio, "inexistente", conIndice, negocio + " nada coincide (índice)");
            }
        }
        System.out.println("── búsqueda CON índice trigram, tiempos (ms, sin JIT, app_user) ── " + conIndice);

        // Control del mecanismo: la misma consulta con el índice, pero como DUEÑO (sin RLS). Si aquí sí usa el índice y
        // como app_user no, lo que lo impide es RLS (LIKE no es leakproof), no el índice ni la consulta.
        Map<String, Double> sinRls = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            medir(st, c, "busq-50k", "referencia 4999", sinRls, "busq-50k nombre poco frecuente (índice, dueño sin RLS)");
            medir(st, c, "busq-50k", "tornillo", sinRls, "busq-50k nombre que contiene 'tornillo' (índice, dueño sin RLS)");
            try (ResultSet rs = st.executeQuery("SELECT proleakproof FROM pg_proc WHERE proname = 'textlike'")) {
                rs.next();
                System.out.println("── textlike (~~) leakproof: " + rs.getBoolean(1));
            }
        }
        System.out.println("── búsqueda CON índice, como dueño SIN RLS ── " + sinRls);
        assertThat(tiempos.values()).allSatisfy(ms -> assertThat(ms).isPositive());
    }
}
