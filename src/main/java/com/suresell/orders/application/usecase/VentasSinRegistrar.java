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
 *
 * <p>Desde V55 excluye las líneas cuyo código ya es un código vigente de un
 * producto del negocio (antes lo filtraba el panel a mano). La forma de la
 * respuesta no cambia.
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
                       max(created_at) AS ultima_venta, max(orden) AS ultima_orden
                  FROM (
                    SELECT btrim(regexp_replace(COALESCE(oi.instructions, ''), '\\s*\\[[^\\]]*\\]\\s*$', '')) AS nombre,
                           NULLIF(substring(COALESCE(oi.instructions, '') FROM '\\[([^\\]]*)\\]\\s*$'), '') AS codigo,
                           oi.unit_price AS precio, oi.tenant_id AS negocio,
                           -- Medido en staging: `order_item.created_at` llega NULL en las
                           -- ventas reales del POS; la fecha que vale es la de la orden.
                           COALESCE(oi.created_at, o.created_at) AS created_at,
                           COALESCE(o.id_order, oi.order_id) AS orden
                      FROM public.order_item oi
                      -- La línea se une a su orden por UUID, que es la relación de
                      -- verdad (V1__multitenant_baseline.sql:45, @JoinColumn
                      -- "order_uuid_id"). Antes se unía por `oi.order_id`, que es
                      -- una copia: el sincronizador de una caja local escribe la
                      -- línea SOLO con el UUID
                      -- (PostgresOrderCloudSyncAdapter.upsertOrderItems), así que
                      -- esas ventas —cobradas y guardadas— no aparecían en la cola.
                      -- La segunda rama solo entra cuando no hay UUID, así que una
                      -- línea nunca casa con dos órdenes.
                      JOIN public.orders o
                        ON (o.uuid_id = oi.order_uuid_id
                            OR (oi.order_uuid_id IS NULL AND o.id_order = oi.order_id))
                       AND o.tenant_id = oi.tenant_id
                     WHERE oi.product_id LIKE ?
                       AND COALESCE(oi.created_at, o.created_at) >= now() - make_interval(days => ?)
                  ) lineas
                 -- Fuera lo que ya tiene dueño: si el código leído es hoy un código
                 -- VIGENTE de un producto del MISMO negocio (V51), ese pendiente ya
                 -- se resolvió —en la caja (registro rápido, V55) o en el panel— y
                 -- la cola no lo vuelve a ofrecer. Un código retirado no cuenta: ya
                 -- no es de nadie.
                 --
                 -- Sin código NO se filtra, y se dice aparte en vez de dejarlo al
                 -- azar de `= NULL`: una venta tecleada con F4 sin escanear nada es
                 -- justo la que hay que registrar, y no puede depender de una
                 -- comparación que no es verdadera ni falsa.
                 --
                 -- El `tenant_id` va explícito aunque RLS ya acote: este filtro
                 -- BORRA filas de la cola, y si alguna vez el aislamiento falla
                 -- (staging ya tuvo políticas de más, V54), el daño sería una cola
                 -- vacía sin explicación en vez de una fila de más.
                 WHERE lineas.codigo IS NULL
                    OR NOT EXISTS (
                       SELECT 1 FROM public.codigos_de_producto c
                        WHERE c.codigo = lineas.codigo
                          AND c.tenant_id = lineas.negocio
                          AND c.retirado_en IS NULL)
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
