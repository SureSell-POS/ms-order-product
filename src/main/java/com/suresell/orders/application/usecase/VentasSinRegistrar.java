package com.suresell.orders.application.usecase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * La cola de «vendidos sin registrar» (VENTA-RAPIDA §4.3, módulo
 * `venta_sin_registro`): las líneas cuyo `product_id` empieza por
 * `sin-registrar:` — un producto que el catálogo no conocía, con el nombre
 * que tecleó la cajera en `instructions` (y el código leído entre corchetes,
 * si lo hubo) y el precio declarado con origen POS.
 *
 * <p>Se agrupa por nombre y código: «Galleta importada [7702…] · $3.500 · 4
 * veces · la última el …». Es lo que el panel lista para registrarlos; el
 * botón «Registrar» (alta única de B) queda documentado como siguiente paso.
 * Una consulta agrupada; el aislamiento lo pone RLS.
 */
@Service
public class VentasSinRegistrar {

    public static final String PREFIJO = "sin-registrar:";

    public record Pendiente(String nombre, String codigo, BigDecimal precio, long veces,
                            OffsetDateTime ultimaVenta, Long ultimaOrden) {
    }

    private final JdbcTemplate jdbc;

    public VentasSinRegistrar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lo vendido sin registrar en los últimos `dias` días, lo más reciente primero. */
    public List<Pendiente> pendientes(int dias) {
        int ventana = Math.max(1, Math.min(dias, 365));
        return jdbc.query("""
                SELECT nombre, codigo, precio, count(*) AS veces,
                       max(created_at) AS ultima_venta, max(order_id) AS ultima_orden
                  FROM (
                    SELECT btrim(regexp_replace(COALESCE(oi.instructions, ''), '\\s*\\[[^\\]]*\\]\\s*$', '')) AS nombre,
                           NULLIF(substring(COALESCE(oi.instructions, '') FROM '\\[([^\\]]*)\\]\\s*$'), '') AS codigo,
                           oi.unit_price AS precio,
                           -- Medido en staging: `order_item.created_at` llega NULL en las
                           -- ventas reales del POS; la fecha que vale es la de la orden.
                           COALESCE(oi.created_at, o.created_at) AS created_at, oi.order_id
                      FROM public.order_item oi
                      JOIN public.orders o ON o.id_order = oi.order_id AND o.tenant_id = oi.tenant_id
                     WHERE oi.product_id LIKE ?
                       AND COALESCE(oi.created_at, o.created_at) >= now() - make_interval(days => ?)
                  ) lineas
                 GROUP BY nombre, codigo, precio
                 ORDER BY ultima_venta DESC""",
                (rs, i) -> new Pendiente(
                        rs.getString("nombre"),
                        rs.getString("codigo"),
                        rs.getBigDecimal("precio"),
                        rs.getLong("veces"),
                        rs.getObject("ultima_venta", OffsetDateTime.class),
                        rs.getObject("ultima_orden", Long.class)),
                PREFIJO + "%", ventana);
    }

    /** Para pintar «N sin registrar» sin traer la lista. */
    public Map<String, Object> resumen(int dias) {
        List<Pendiente> lista = pendientes(dias);
        long lineas = lista.stream().mapToLong(Pendiente::veces).sum();
        return Map.of("distintos", lista.size(), "lineas", lineas, "dias", Math.max(1, Math.min(dias, 365)));
    }
}
