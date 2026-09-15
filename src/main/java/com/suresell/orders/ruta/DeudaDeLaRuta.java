package com.suresell.orders.ruta;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F6.0b, D-b4 (ECM, 2026-09-15): la deuda de los clientes de un vendedor viaja entera y compacta en cada paquete
 * ({@code [documento, saldo, vencido, diasVencidoMax]}); no mueve la marca de la ficha. Dos consultas medidas en
 * {@code CostoDeLaCarteraTest} (2.000 clientes, 40.000 facturas, sin JIT, como app_user): con pocos clientes, los del vendedor
 * primero y su cartera por documento debajo (300: 22 ms); con muchos, la vista por cliente del negocio filtrada (2.000:
 * 94 ms), que cuesta lo mismo con 300 que con 2.000. La guarda de costo pide menos de 150 ms a cada una en su tramo.
 */
@Component
public class DeudaDeLaRuta {

    /** Hasta aquí, los clientes del vendedor primero; por encima, la vista del negocio. */
    public static final int UMBRAL_DE_CLIENTES = 500;

    public static final String CLIENTES_PRIMERO = """
            SELECT c.documento, x.saldo, x.vencido, x.dias_vencido_max
              FROM clientes c
              JOIN accounts_receivable a ON a.tenant_id = c.tenant_id AND a.customer_document = c.documento
             CROSS JOIN LATERAL (SELECT COALESCE(sum(d.saldo) FILTER (WHERE d.saldo > 0), 0) AS saldo,
                                        COALESCE(sum(d.saldo) FILTER (WHERE d.saldo > 0 AND d.dias_vencido > 0), 0) AS vencido,
                                        max(d.dias_vencido) AS dias_vencido_max
                                   FROM v_cartera_por_documento d
                                  WHERE d.tenant_id = c.tenant_id AND d.cliente_documento = c.documento) x
             WHERE c.tenant_id = ? AND c.vendedor_id = ?""";

    public static final String VISTA_DEL_NEGOCIO = """
            SELECT v.cliente_documento, v.saldo, v.vencido, v.dias_vencido_max
              FROM v_cartera_por_cliente v
             WHERE v.tenant_id = ? AND v.vendedor_id = ?""";

    private final JdbcTemplate jdbc;

    public DeudaDeLaRuta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code [documento, saldo, vencido, diasVencidoMax]} por cliente del vendedor que tiene cuenta por cobrar. */
    public List<List<Object>> porVendedor(String negocio, long vendedorId) {
        Integer clientes = jdbc.queryForObject("SELECT count(*) FROM clientes WHERE tenant_id = ? AND vendedor_id = ?",
                Integer.class, negocio, vendedorId);
        String sql = clientes != null && clientes > UMBRAL_DE_CLIENTES ? VISTA_DEL_NEGOCIO : CLIENTES_PRIMERO;
        List<List<Object>> filas = new ArrayList<>();
        jdbc.query(sql, rs -> {
            List<Object> f = new ArrayList<>(4);
            f.add(rs.getString(1));
            f.add(rs.getBigDecimal(2));
            f.add(rs.getBigDecimal(3));
            f.add(rs.getObject(4));
            filas.add(f);
        }, negocio, vendedorId);
        return filas;
    }
}
