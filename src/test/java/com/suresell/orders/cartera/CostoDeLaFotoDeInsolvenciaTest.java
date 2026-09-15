package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F4.13 (a), medición ANTES de escribir la migración (orden de ECM): el costo de la foto de la deuda a la fecha de inicio
 * (diseño §3.2 y §10) para un cliente con 1.000 facturas y 5.000 recibos aplicados (algunos anulados), más un negocio
 * vecino grande, sin JIT y como app_user con RLS. La consulta es la que hará {@code fn_deuda_a_fecha}: solo hechos con
 * fecha anterior al corte. Con V75, también la función real, informar INICIO con su foto y las lecturas que la usan.
 * Imprime tiempos; no falla por lentitud.
 */
@Testcontainers
class CostoDeLaFotoDeInsolvenciaTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    /** La deuda del cliente al corte (timestamp de Bogotá del día de inicio, 00:00): facturas con saldo > 0 al corte. */
    static final String DEUDA_A_FECHA = """
            SELECT d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount AS monto,
                   COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0) AS aplicado_al_corte
              FROM public.debt_transactions d
              JOIN public.accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
              LEFT JOIN public.cartera_aplicaciones a
                     ON a.tenant_id = d.tenant_id AND a.debito_tx_id = d.id AND a.ocurrido_en < ?
              LEFT JOIN public.recibos_de_caja x
                     ON x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id AND x.ocurrido_en < ?
             WHERE d.tenant_id = ? AND ar.customer_document = ? AND d.type = 'DEBIT' AND d.recibo_id IS NULL
               AND d.transaction_date < ?::date
               AND NOT EXISTS (SELECT 1 FROM public.egresos_de_cartera e WHERE e.tenant_id = d.tenant_id AND e.debito_tx_id = d.id)
             GROUP BY d.id, d.order_uuid, d.transaction_date, d.vence_el, d.amount
            HAVING d.amount - COALESCE(sum(a.monto) FILTER (WHERE a.id IS NOT NULL AND x.id IS NULL), 0) > 0
             ORDER BY d.transaction_date, d.id""";

    @BeforeAll
    static void sembrar() throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()).locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String[] t : new String[][] {{"foto-a", "1000", "5"}, {"foto-vecino", "20000", "5"}}) {
                String n = t[0];
                int facturas = Integer.parseInt(t[1]);
                int recibosPorFactura = Integer.parseInt(t[2]);
                st.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + n + "','F','pro')");
                st.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) VALUES ('" + n + "','D1','Tienda',8,'s')");
                st.execute("INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('admin@" + n + ".invalid', '!', '" + n + "', 'admin')");
                st.execute("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) "
                        + "VALUES ('" + n + "-c','" + n + "', now(), 999999999, 'D1', 'Tienda', 'ACTIVE', 0, now())");
                // Facturas repartidas en dos años, cada una de 100.000.
                st.execute("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el, order_uuid) "
                        + "SELECT '" + n + "-d' || g, '" + n + "', '" + n + "-c', 100000, now(), 'x', current_date - (g % 730), 'DEBIT', current_date - (g % 730) + 8, gen_random_uuid() "
                        + "FROM generate_series(1, " + facturas + ") g");
                // Recibos: recibosPorFactura por factura, de 10.000, cada uno aplicado a su factura; uno de cada 7 anulado.
                st.execute("INSERT INTO recibos_de_caja (id, tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key) "
                        + "SELECT gen_random_uuid(), '" + n + "', 0, 'D1', 10000, 'EFECTIVO', now() - ((g % 700) || ' days')::interval, '" + n + "-r' || g "
                        + "FROM generate_series(1, " + (facturas * recibosPorFactura) + ") g");
                st.execute("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, recibo_id) "
                        + "SELECT '" + n + "-k' || r.idempotency_key, r.tenant_id, '" + n + "-c', r.monto, now(), 'x', (r.ocurrido_en AT TIME ZONE 'America/Bogota')::date, 'CREDIT', r.id "
                        + "FROM recibos_de_caja r WHERE r.tenant_id = '" + n + "'");
                st.execute("INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, ocurrido_en) "
                        + "SELECT r.tenant_id, r.id, '" + n + "-k' || r.idempotency_key, '" + n + "-d' || (1 + (substring(r.idempotency_key from '[0-9]+$')::int % " + facturas + ")), "
                        + "r.monto, 'MAS_ANTIGUA_PRIMERO', r.ocurrido_en FROM recibos_de_caja r WHERE r.tenant_id = '" + n + "'");
                st.execute("INSERT INTO recibos_de_caja (id, tenant_id, numero, cliente_documento, monto, medio, ocurrido_en, idempotency_key, anula_recibo_id, motivo_anulacion) "
                        + "SELECT gen_random_uuid(), r.tenant_id, 0, 'D1', r.monto, 'EFECTIVO', r.ocurrido_en + interval '1 day', r.idempotency_key || '-anul', r.id, 'DUPLICADO' "
                        + "FROM recibos_de_caja r WHERE r.tenant_id = '" + n + "' AND substring(r.idempotency_key from '[0-9]+$')::int % 7 = 0");
            }
            // Un negocio con muchos clientes: con dos filas el plan de clientes sería un Seq Scan que no dice nada.
            st.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) "
                    + "SELECT 'foto-vecino', 'V' || g, 'Cliente ' || g, 8, 's' FROM generate_series(1, 5000) g");
            // F4.13 (c): un negocio de 500 clientes con cuenta y 5 facturas cada uno, 10 de ellos en proceso y 5 con crédito habilitado.
            st.execute("INSERT INTO tenants (id, name, plan) VALUES ('lista-500','L','pro')");
            st.execute("INSERT INTO users (email, password_hash, tenant_id, role) VALUES ('admin@lista-500.invalid', '!', 'lista-500', 'admin')");
            st.execute("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, creado_por) "
                    + "SELECT 'lista-500', 'L' || g, 'Cliente ' || g, 8, 's' FROM generate_series(1, 500) g");
            st.execute("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) "
                    + "SELECT 'lista-500-c' || g, 'lista-500', now(), 1000000, 'L' || g, 'Cliente', 'ACTIVE', 0, now() FROM generate_series(1, 500) g");
            st.execute("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el) "
                    + "SELECT 'lista-500-d' || g || '-' || k, 'lista-500', 'lista-500-c' || g, 10000, now(), 'x', current_date - 30, 'DEBIT', current_date - 22 "
                    + "FROM generate_series(1, 500) g, generate_series(1, 5) k");
            st.execute("BEGIN");
            st.execute("SELECT set_config('app.tenant_id', 'lista-500', true), "
                    + "set_config('app.user_id', (SELECT id::text FROM users WHERE tenant_id = 'lista-500'), true)");
            st.execute("SELECT fn_insolvencia_informar_etapa('L' || g, 'INICIO', current_date - 5, 'Auto', NULL, 'abogado', NULL, NULL, NULL) "
                    + "FROM generate_series(1, 10) g");
            st.execute("SELECT fn_insolvencia_credito_posterior('L' || g, true, 8, 'Prueba de costo') FROM generate_series(1, 5) g");
            st.execute("COMMIT");
            st.execute("ANALYZE");
        }
    }

    private static double ejecucion(Statement st, String explain) throws Exception {
        double ms = -1;
        try (ResultSet rs = st.executeQuery(explain)) {
            while (rs.next()) {
                if (rs.getString(1).startsWith("Execution Time:")) {
                    ms = Double.parseDouble(rs.getString(1).replaceAll("[^0-9.]", ""));
                }
            }
        }
        return ms;
    }

    @Test
    @DisplayName("F4.13 (a): costo de la foto de la deuda al inicio, 1.000 facturas y 5.000 recibos (sin JIT, app_user)")
    void costo() throws Exception {
        Map<String, Double> tiempos = new LinkedHashMap<>();
        Map<String, Integer> filas = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            st.execute("SELECT set_config('app.tenant_id', 'foto-a', false)");
            for (int dias : new int[] {1, 180, 365}) {
                String etiqueta = "corte hace " + dias + " días";
                java.sql.Timestamp corte = java.sql.Timestamp.valueOf(java.time.LocalDate.now().minusDays(dias).atStartOfDay());
                for (int vuelta = 0; vuelta < 3; vuelta++) {
                    try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + DEUDA_A_FECHA)) {
                        ps.setTimestamp(1, corte);
                        ps.setTimestamp(2, corte);
                        ps.setString(3, "foto-a");
                        ps.setString(4, "D1");
                        ps.setDate(5, java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(dias)));
                        StringBuilder plan = new StringBuilder();
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String l = rs.getString(1);
                                plan.append(l).append('\n');
                                if (l.startsWith("Execution Time:")) {
                                    tiempos.put(etiqueta, Double.parseDouble(l.replaceAll("[^0-9.]", "")));
                                }
                            }
                        }
                        if (vuelta == 2 && dias == 1) {
                            System.out.println("── plan foto " + etiqueta + " ──\n" + plan);
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM (" + DEUDA_A_FECHA + ") q")) {
                    ps.setTimestamp(1, corte);
                    ps.setTimestamp(2, corte);
                    ps.setString(3, "foto-a");
                    ps.setString(4, "D1");
                    ps.setDate(5, java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(dias)));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        filas.put(etiqueta, rs.getInt(1));
                    }
                }
            }
        }
        // Con V75 escrita: lo mismo por la función real, informar INICIO (foto incluida) y las lecturas que la usan.
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            st.execute("SELECT set_config('app.tenant_id', 'foto-a', false)");
            st.execute("SELECT set_config('app.user_id', (SELECT id::text FROM users WHERE tenant_id = 'foto-a'), false)");
            tiempos.put("fn_deuda_a_fecha a 180 días", ejecucion(st, "EXPLAIN (ANALYZE) SELECT * FROM fn_deuda_a_fecha('foto-a', 'D1', "
                    + "(current_date - 180)::timestamp AT TIME ZONE 'America/Bogota')"));
            tiempos.put("informar INICIO a 180 días (foto incluida)", ejecucion(st, "EXPLAIN (ANALYZE) SELECT fn_insolvencia_informar_etapa("
                    + "'D1', 'INICIO', current_date - 180, 'Auto', NULL, 'abogado', NULL, NULL, NULL)"));
            tiempos.put("estado de cuenta sin clasificación (control)", ejecucion(st, "EXPLAIN (ANALYZE) SELECT d.debito_tx_id, d.saldo "
                    + "FROM v_cartera_por_documento d WHERE d.tenant_id = 'foto-a' AND d.cliente_documento = 'D1' "
                    + "AND (d.saldo > 0 OR d.fecha BETWEEN current_date - 90 AND current_date)"));
            // La clasificación va aparte, una lectura por cliente (unida a la lista se evaluaba por factura).
            StringBuilder planDeLaClasificacion = new StringBuilder();
            try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE) SELECT debito_tx_id, clasificacion FROM v_insolvencia_clasificacion "
                    + "WHERE tenant_id = 'foto-a' AND cliente_documento = 'D1'")) {
                while (rs.next()) {
                    planDeLaClasificacion.append(rs.getString(1)).append('\n');
                    if (rs.getString(1).startsWith("Execution Time:")) {
                        tiempos.put("estado de cuenta: clasificación del cliente (aparte)", Double.parseDouble(rs.getString(1).replaceAll("[^0-9.]", "")));
                    }
                }
            }
            System.out.println("── plan de la clasificación de un cliente ──\n" + planDeLaClasificacion);
            StringBuilder planDeLaDeudaPosterior = new StringBuilder();
            double deudaPosteriorMs = 0;
            for (String lectura : List.of(ProcesoDeInsolvencia.POSTERIORES_DEL_CLIENTE, ProcesoDeInsolvencia.SALDOS_VIVOS_DEL_CLIENTE)) {
                try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE) " + lectura.replaceFirst("\\?", "'foto-a'").replaceFirst("\\?", "'D1'"))) {
                    while (rs.next()) {
                        planDeLaDeudaPosterior.append(rs.getString(1)).append('\n');
                        if (rs.getString(1).startsWith("Execution Time:")) {
                            deudaPosteriorMs += Double.parseDouble(rs.getString(1).replaceAll("[^0-9.]", ""));
                        }
                    }
                }
            }
            tiempos.put("GET insolvencia: deuda posterior al inicio (dos lecturas)", deudaPosteriorMs);
            System.out.println("── plan de la deuda posterior ──\n" + planDeLaDeudaPosterior);
            assertThat(planDeLaDeudaPosterior.toString().lines()
                    .filter(l -> l.contains("insolvencia_") && l.matches(".*loops=([5-9][0-9]{2}|[0-9]{4,}).*"))).as("nada por factura").isEmpty();
            assertThat(planDeLaClasificacion.toString().lines()
                    .filter(l -> l.contains("insolvencia_") && l.matches(".*loops=([5-9][0-9]{2}|[0-9]{4,}).*"))).as("nada por factura").isEmpty();
            tiempos.put("proceso vigente", ejecucion(st, "EXPLAIN (ANALYZE) SELECT * FROM v_insolvencia_vigente "
                    + "WHERE tenant_id = 'foto-a' AND cliente_documento = 'D1' AND abierto"));
            try (ResultSet rs = st.executeQuery("SELECT facturas, total FROM insolvencia_fotos WHERE tenant_id = 'foto-a'")) {
                rs.next();
                filas.put("foto por la función", rs.getInt(1));
            }
        }
        // F4.13 (b), V76: el disparador de cartera_aplicaciones sobre el caso normal, un cliente SIN proceso en un negocio con
        // 20.000 facturas (el vecino). Tiene que ser el recibo y el cliente por índice, y fuera.
        String planDelDisparador;
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            st.execute("SELECT set_config('app.tenant_id', 'foto-vecino', false)");
            String recibo;
            try (ResultSet rs = st.executeQuery("SELECT id::text FROM recibos_de_caja WHERE tenant_id = 'foto-vecino' AND anula_recibo_id IS NULL LIMIT 1")) {
                rs.next();
                recibo = rs.getString(1);
            }
            StringBuilder plan = new StringBuilder();
            // Las dos lecturas del disparador; el cliente, uno del final del orden (V4999) para que un recorrido se notara.
            for (String[] lectura : new String[][] {
                    {"recibo", "EXPLAIN (ANALYZE) SELECT r.cliente_documento FROM recibos_de_caja r WHERE r.tenant_id = 'foto-vecino' AND r.id = '" + recibo + "'"},
                    {"cliente", "EXPLAIN (ANALYZE) SELECT c.en_insolvencia_desde FROM clientes c WHERE c.tenant_id = 'foto-vecino' AND c.documento = 'V4999'"}}) {
                try (ResultSet rs = st.executeQuery(lectura[1])) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                        if (rs.getString(1).startsWith("Execution Time:")) {
                            tiempos.put("disparador V76, cliente sin proceso (" + lectura[0] + ")", Double.parseDouble(rs.getString(1).replaceAll("[^0-9.]", "")));
                        }
                    }
                }
            }
            planDelDisparador = plan.toString();
            c.setAutoCommit(false);
            String credito;
            String debito;
            try (ResultSet rs = st.executeQuery("SELECT (SELECT id FROM debt_transactions WHERE recibo_id = '" + recibo + "'), "
                    + "(SELECT id FROM debt_transactions WHERE tenant_id = 'foto-vecino' AND type = 'DEBIT' LIMIT 1)")) {
                rs.next();
                credito = rs.getString(1);
                debito = rs.getString(2);
            }
            try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE) INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla) "
                    + "VALUES ('foto-vecino', '" + recibo + "', '" + credito + "', '" + debito + "', 1, 'MAS_ANTIGUA_PRIMERO')")) {
                while (rs.next()) {
                    String l = rs.getString(1);
                    if (l.startsWith("Trigger trg_aplicacion_respeta_la_insolvencia")) {
                        tiempos.put("disparador V76 en el INSERT", Double.parseDouble(l.replaceAll(".*time=([0-9.]+).*", "$1")));
                    }
                    if (l.startsWith("Trigger trg_devolucion_solo_cubre_un_egreso")) {
                        tiempos.put("disparador V78 en el INSERT (abono normal)", Double.parseDouble(l.replaceAll(".*time=([0-9.]+).*", "$1")));
                    }
                }
            }
            c.rollback();
        }
        // F4.13 (c): la lista de clientes del mayorista (la de la caja) con el join a las dos vistas, contra la misma sin él.
        java.lang.reflect.Field columnas = com.suresell.orders.mayorista.ListasDePrecio.class.getDeclaredField("COLUMNAS_DEL_CLIENTE");
        java.lang.reflect.Field desde = com.suresell.orders.mayorista.ListasDePrecio.class.getDeclaredField("DESDE_CLIENTES");
        columnas.setAccessible(true);
        desde.setAccessible(true);
        String conInsolvencia = "SELECT " + columnas.get(null) + desde.get(null) + " WHERE c.tenant_id = 'lista-500' ORDER BY c.nombre";
        String sinInsolvencia = conInsolvencia
                .replaceAll("(?s),\\s*vv\\.etapa AS insolvencia_etapa.*?AS insolvencia_credito_plazo", "")
                .replaceAll("(?s)-- F4\\.13 \\(c\\).*?cp\\.proceso_id = vv\\.proceso_id", "");
        assertThat(sinInsolvencia).as("el control quita el join").doesNotContain("v_insolvencia").isNotEqualTo(conInsolvencia);
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET jit = off");
            st.execute("SELECT set_config('app.tenant_id', 'lista-500', false)");
            for (int vuelta = 0; vuelta < 3; vuelta++) {
                tiempos.put("lista de 500 clientes sin insolvencia (control)", ejecucion(st, "EXPLAIN (ANALYZE) " + sinInsolvencia));
                tiempos.put("lista de 500 clientes con insolvencia", ejecucion(st, "EXPLAIN (ANALYZE) " + conInsolvencia));
            }
            StringBuilder planDeLaLista = new StringBuilder();
            try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE) " + conInsolvencia)) {
                while (rs.next()) {
                    planDeLaLista.append(rs.getString(1)).append('\n');
                }
            }
            System.out.println("── plan de la lista de 500 clientes con insolvencia ──\n" + planDeLaLista);
            // Nada por fila de cliente sobre las tablas de insolvencia: ningún nodo suyo se repite 500 veces.
            assertThat(planDeLaLista.toString().lines()
                    .filter(l -> l.contains("insolvencia_") && l.matches(".*loops=([5-9][0-9]{2}|[0-9]{4,}).*"))).isEmpty();
            int enProceso = 0;
            int habilitados = 0;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE insolvencia_en_proceso), count(*) FILTER (WHERE insolvencia_credito_vigente) FROM ("
                    + conInsolvencia + ") q")) {
                rs.next();
                enProceso = rs.getInt(1);
                habilitados = rs.getInt(2);
            }
            assertThat(enProceso).isEqualTo(10);
            assertThat(habilitados).isEqualTo(5);
            // La lectura que añade fn_venta_a_credito, solo con el cliente en proceso.
            tiempos.put("venta a crédito: lectura del crédito posterior (cliente en proceso)", ejecucion(st,
                    "EXPLAIN (ANALYZE) SELECT cp.plazo_maximo_dias FROM v_insolvencia_credito_posterior cp "
                            + "WHERE cp.tenant_id = 'lista-500' AND cp.cliente_documento = 'L3' AND cp.vigente"));
        }
        assertThat(tiempos).as("el disparador de V78 corre sobre el abono normal").containsKey("disparador V78 en el INSERT (abono normal)");
        System.out.println("── plan de la lectura del disparador V76 ──\n" + planDelDisparador);
        assertThat(planDelDisparador).contains("Index Scan using pk_recibos_de_caja", "Index Scan using ux_clientes_documento")
                .contains("Index Cond: ((tenant_id = 'foto-vecino'::text) AND (documento = 'V4999'::text))")
                .doesNotContain("Seq Scan").doesNotContain("Join");
        System.out.println("── foto de insolvencia, tiempos (ms, sin JIT, app_user) ── " + tiempos + " · facturas con saldo al corte " + filas);
        assertThat(filas.get("foto por la función")).isEqualTo(filas.get("corte hace 180 días"));
        assertThat(tiempos.values()).allSatisfy(ms -> assertThat(ms).isPositive());
        assertThat(filas.values()).anySatisfy(n -> assertThat(n).isPositive());
    }
}
