package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * 💰 Cuánto cuesta leer la cartera de un mayorista grande: 2.000 clientes,
 * 40.000 facturas y 10.000 recibos con sus aplicaciones, más otro negocio del
 * mismo tamaño al lado. Mide con {@code EXPLAIN ANALYZE} las consultas que hace
 * {@code Cartera}, con sus filtros reales, y deja el plan en la salida.
 *
 * <p>La pregunta: ¿el estado de cuenta de UN cliente cuesta según ese cliente o
 * según todo el negocio?
 */
@Testcontainers
class CostoDeLaCarteraTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Map<String, String> CONSULTAS = new LinkedHashMap<>();

    @BeforeAll
    static void sembrar() throws SQLException {
        var conf = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration");
        // CARTERA_HASTA=67 mide las vistas de V64 (antes de V68), para comparar.
        String hasta = System.getenv("CARTERA_HASTA");
        if (hasta != null && !hasta.isBlank()) {
            conf.target(hasta);
        }
        conf.load().migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String t : new String[] {"perf-a", "perf-b"}) {
                st.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "', '" + t + "', 'pro')");
                st.execute("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) "
                        + "SELECT '" + t + "', 'D' || g, 'Cliente ' || g, 's' FROM generate_series(1, 2000) g");
                st.execute("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                        + "customer_name, status, total_debt, updated_at) "
                        + "SELECT '" + t + "-' || g, '" + t + "', now(), 1000000, 'D' || g, 'Cliente ' || g, 'ACTIVE', 0, now() "
                        + "FROM generate_series(1, 2000) g");
                // 20 facturas por cliente, repartidas en 400 días.
                st.execute("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, transaction_date, type, vence_el) "
                        + "SELECT '" + t + "-d-' || g || '-' || f, '" + t + "', '" + t + "-' || g, 50000, now(), "
                        + "current_date - (f * 20), 'DEBIT', current_date - (f * 20) + 30 "
                        + "FROM generate_series(1, 2000) g, generate_series(1, 20) f");
                // 5 recibos por cliente, cada uno aplicado a una de sus facturas viejas.
                st.execute("INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key) "
                        + "SELECT '" + t + "', 0, 'D' || g, 20000, 'EFECTIVO', now() - (r || ' days')::interval, '" + t + "-r-' || g || '-' || r "
                        + "FROM generate_series(1, 2000) g, generate_series(1, 5) r");
                st.execute("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, transaction_date, type, recibo_id) "
                        + "SELECT 'c' || replace(r.id::text, '-', ''), r.tenant_id, r.tenant_id || '-' || substr(r.cliente_documento, 2), r.monto, now(), "
                        + "current_date, 'CREDIT', r.id FROM recibos_de_caja r WHERE r.tenant_id = '" + t + "'");
                st.execute("INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla) "
                        + "SELECT r.tenant_id, r.id, 'c' || replace(r.id::text, '-', ''), r.tenant_id || '-d-' || substr(r.cliente_documento, 2) || '-' || "
                        + "(20 - (row_number() OVER (PARTITION BY r.cliente_documento ORDER BY r.id))::int), r.monto, 'MAS_ANTIGUA_PRIMERO' "
                        + "FROM recibos_de_caja r WHERE r.tenant_id = '" + t + "'");
            }
            st.execute("ANALYZE");
        }
        CONSULTAS.put("estado de cuenta: documentos de un cliente", """
                SELECT d.debito_tx_id, d.order_uuid, d.fecha, d.vence_el, d.monto, d.aplicado, d.saldo, d.dias_vencido, d.edad
                  FROM v_cartera_por_documento d
                 WHERE d.tenant_id = 'perf-a' AND d.cliente_documento = 'D777'
                   AND (d.saldo > 0 OR d.fecha BETWEEN current_date - 90 AND current_date)
                 ORDER BY d.fecha, d.debito_tx_id""");
        CONSULTAS.put("resumen de un cliente (ficha y estado de cuenta)", """
                SELECT v.cliente_documento, v.nombre, v.cupo, v.saldo, v.vencido, v.factura_mas_vieja_vence_el,
                       v.dias_vencido_max, v.excede_cupo, v.en_insolvencia_desde, v.vendedor_id, u.nombre AS vendedor
                  FROM v_cartera_por_cliente v
                  LEFT JOIN users u ON u.tenant_id = v.tenant_id AND u.id = v.vendedor_id
                 WHERE v.tenant_id = 'perf-a' AND v.cliente_documento = 'D777'""");
        CONSULTAS.put("lista de clientes del negocio", """
                SELECT v.cliente_documento, v.nombre, v.cupo, v.saldo, v.vencido
                  FROM v_cartera_por_cliente v
                 WHERE v.tenant_id = 'perf-a'
                 ORDER BY v.vencido DESC, v.saldo DESC, v.nombre""");
        CONSULTAS.put("facturas vivas de una cuenta (al cobrar)", """
                SELECT v.debito_tx_id, v.order_uuid, v.saldo
                  FROM v_cartera_por_documento v
                  JOIN debt_transactions d ON d.tenant_id = v.tenant_id AND d.id = v.debito_tx_id
                 WHERE v.tenant_id = 'perf-a' AND v.account_id = 'perf-a-777' AND v.saldo > 0
                 ORDER BY v.fecha, d.created_at, v.debito_tx_id""");
        CONSULTAS.put("comportamiento de pago de un cliente (F10.3)", """
                WITH facturas AS (
                    SELECT d.id, d.order_uuid, d.transaction_date AS fecha, d.vence_el, d.amount AS monto
                      FROM debt_transactions d
                      JOIN accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
                     WHERE d.tenant_id = 'perf-a' AND ar.customer_document = 'D777' AND d.type = 'DEBIT' AND d.recibo_id IS NULL
                       AND d.transaction_date BETWEEN current_date - 365 AND current_date
                ), abonos AS (
                    SELECT f.id, f.monto, (r.ocurrido_en AT TIME ZONE 'America/Bogota')::date AS dia,
                           sum(a.monto) OVER (PARTITION BY f.id ORDER BY r.ocurrido_en, r.numero, a.id) AS acumulado
                      FROM facturas f
                      JOIN cartera_aplicaciones a ON a.tenant_id = 'perf-a' AND a.debito_tx_id = f.id
                      JOIN recibos_de_caja r ON r.tenant_id = a.tenant_id AND r.id = a.recibo_id
                     WHERE NOT EXISTS (SELECT 1 FROM recibos_de_caja x WHERE x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id)
                ), pagadas AS (
                    SELECT id, min(dia) AS pagada_el FROM abonos WHERE acumulado >= monto GROUP BY id
                )
                SELECT f.id, f.order_uuid, f.fecha, f.vence_el, f.monto, p.pagada_el
                  FROM facturas f LEFT JOIN pagadas p ON p.id = f.id
                 ORDER BY f.fecha DESC, f.id""");
    }

    /** F4.12: lo que la venta a crédito pregunta antes de aplicar saldo a favor. El recibo más antiguo con saldo sin aplicar. */
    static final String RECIBO_CON_SALDO_A_FAVOR = """
            SELECT r.id, r.monto - COALESCE(sum(a.monto), 0) AS queda
              FROM recibos_de_caja r
              LEFT JOIN cartera_aplicaciones a ON a.tenant_id = r.tenant_id AND a.recibo_id = r.id
             WHERE r.tenant_id = '%s' AND r.cliente_documento = '%s'
               AND r.anula_recibo_id IS NULL
               AND NOT EXISTS (SELECT 1 FROM recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id)
             GROUP BY r.id, r.monto, r.ocurrido_en, r.numero
            HAVING r.monto > COALESCE(sum(a.monto), 0)
             ORDER BY r.ocurrido_en, r.numero
             LIMIT 1""";

    @Test
    @DisplayName("💰 F4.12: ¿tiene saldo a favor? en cada venta a crédito, sin JIT: cliente normal (5 recibos) y extremo (5.000)")
    void costoDelSaldoAFavor() throws SQLException {
        Map<String, Double> tiempos = new LinkedHashMap<>();
        StringBuilder informe = new StringBuilder();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenants (id, name, plan) VALUES ('perf-c', 'perf-c', 'pro') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key) "
                    + "SELECT 'perf-c', 0, 'GRANDE', 20000, 'EFECTIVO', now() - (r || ' minutes')::interval, 'perf-c-r-' || r "
                    + "FROM generate_series(1, 5000) r ON CONFLICT DO NOTHING");
            st.execute("ANALYZE recibos_de_caja");
            st.execute("SET jit = off");
            for (String[] caso : new String[][] {{"perf-a", "D777", "cliente con 5 recibos aplicados (sin saldo)"},
                    {"perf-c", "GRANDE", "cliente con 5.000 recibos sin aplicar"},
                    {"perf-a", "NADIE", "documento sin recibos"}}) {
                for (int vuelta = 0; vuelta < 3; vuelta++) {
                    try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + RECIBO_CON_SALDO_A_FAVOR.formatted(caso[0], caso[1]))) {
                        while (rs.next()) {
                            String l = rs.getString(1);
                            if (vuelta == 2) {
                                informe.append(l).append('\n');
                            }
                            if (l.startsWith("Execution Time:")) {
                                tiempos.put(caso[2] + " #" + vuelta, Double.parseDouble(l.replaceAll("[^0-9.]", "")));
                            }
                        }
                    }
                }
                informe.append("── fin de ").append(caso[2]).append(" ──\n");
            }
        }
        System.out.println(informe);
        System.out.println("── saldo a favor, tiempos (ms, sin JIT) ── " + tiempos);
        assertThat(tiempos.values()).allSatisfy(ms -> assertThat(ms).isPositive());
    }

    @Test
    @DisplayName("💰 cartera de 2.000 clientes y 40.000 facturas: EXPLAIN ANALYZE de las consultas de Cartera")
    void costo() throws SQLException {
        StringBuilder informe = new StringBuilder();
        Map<String, Double> tiempos = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            // La lista se mide también con el work_mem de staging (3500kB, SHOW work_mem del 14/09) y con uno holgado.
            for (String wm : new String[] {"3500kB", "64MB"}) {
                st.execute("SET work_mem = '" + wm + "'");
                for (int vuelta = 0; vuelta < 3; vuelta++) {
                    try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE) " + CONSULTAS.get("lista de clientes del negocio"))) {
                        while (rs.next()) {
                            String l = rs.getString(1);
                            if (l.startsWith("Execution Time:")) {
                                tiempos.put("lista con work_mem " + wm + " #" + vuelta, Double.parseDouble(l.replaceAll("[^0-9.]", "")));
                            }
                        }
                    }
                }
            }
            st.execute("RESET work_mem");
            for (Map.Entry<String, String> q : CONSULTAS.entrySet()) {
                informe.append("\n── ").append(q.getKey()).append(" ──\n");
                double ms = -1;
                try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + q.getValue())) {
                    while (rs.next()) {
                        String l = rs.getString(1);
                        informe.append(l).append('\n');
                        if (l.startsWith("Execution Time:")) {
                            ms = Double.parseDouble(l.replaceAll("[^0-9.]", ""));
                        }
                    }
                }
                tiempos.put(q.getKey(), ms);
            }
        }
        System.out.println(informe);
        System.out.println("── tiempos (ms) ── " + tiempos);
        assertThat(tiempos.values()).allSatisfy(ms -> assertThat(ms).isPositive());
    }
}
