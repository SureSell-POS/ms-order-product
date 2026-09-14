package com.suresell.orders.mayorista;

import com.suresell.orders.application.dto.OrderItemRequestRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * El precio que corresponde a cada línea cuando la venta trae CLIENTE.
 *
 * <p>Hasta la ola 2 el precio lo mandaba el POS y el servidor lo guardaba tal
 * cual ({@code OrderHandler.createOrderItems}); solo comparaba el total (V36).
 * Para un restaurante vale. Para un mayorista con listas por cliente, no: el
 * precio depende de QUIÉN compra y CUÁNTO, y eso lo sabe la base
 * ({@code fn_precio_para}, V45), no el mostrador.
 *
 * <p>Con cliente, el precio del POS se DESCARTA y se guarda el resuelto, con
 * su origen (LISTA / BASE) y la línea versionada que se aplicó. Sin cliente,
 * no se toca nada: la venta de plazoleta sigue igual que siempre, origen POS.
 */
@Component
public class ResolucionDePrecios {

    /** Lo que la base resolvió para una línea. */
    public record Precio(BigDecimal precio, String origen, UUID listaPrecioItemId, UUID listaPrecioId) {
    }

    private final JdbcTemplate jdbc;

    public ResolucionDePrecios(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Un precio por producto de la orden, con la cantidad TOTAL del producto en la orden. */
    public Map<String, Precio> resolver(String clienteDocumento, List<OrderItemRequestRecord> lineas) {
        return resolver(clienteDocumento, lineas, true);
    }

    /**
     * Resuelve el precio de cada producto en la base: la lista del cliente si
     * la tiene, y si no el precio BASE del catálogo.
     *
     * <p>Integración de la ola 2: <b>se llama SIEMPRE</b>, con o sin cliente.
     * Antes solo se llamaba con cliente, y sin cliente el servidor aceptaba el
     * precio que mandaba el POS: una venta sin cliente con precio 1 se cobraba a
     * 1 (medido en staging, orden 4 de {@code qa-zeta-v38}, producto de 120.000).
     * Es el mismo hueco que la Fase 2 cerró para los importes, abierto por otro
     * lado.
     *
     * @param exigirCatalogo con {@code true}, un producto que no está en el
     *        catálogo es un error (venta con cliente: no hay lista que aplicar).
     *        Con {@code false} se omite del mapa y la línea conserva el precio
     *        declarado por el POS, marcado como tal: una venta no se pierde por
     *        un producto que el catálogo todavía no conoce.
     */
    public Map<String, Precio> resolver(String clienteDocumento, List<OrderItemRequestRecord> lineas,
                                        boolean exigirCatalogo) {
        Map<String, Integer> cantidadPorProducto = new LinkedHashMap<>();
        for (OrderItemRequestRecord l : lineas) {
            cantidadPorProducto.merge(l.productId(), l.quantity(), Integer::sum);
        }
        // F1.5 (V61): todas las líneas en UNA consulta. Antes, una por producto
        // (anexo A-4): 50 viajes a la base para un pedido de 50 líneas.
        Map<String, Precio> resueltos = enLote(clienteDocumento, cantidadPorProducto, null);
        Map<String, Precio> resultado = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cantidadPorProducto.entrySet()) {
            Precio encontrado = resueltos.get(e.getKey());
            if (encontrado == null) {
                // Sin fila no hay producto: ni en la lista ni en el catálogo.
                if (exigirCatalogo) {
                    // Con cliente: no se vende algo que no existe a un precio
                    // que mandó el POS.
                    throw new IllegalArgumentException(
                            "El producto " + e.getKey() + " no existe en el catálogo: no se le puede poner precio.");
                }
                // Sin cliente: no hay catálogo con qué contradecir al POS. La
                // línea conserva lo declarado, con origen POS, que es visible
                // (en producción: 15 de 2.407 líneas en 30 días, productos ya
                // retirados del menú). Una venta no se pierde por eso.
                continue;
            }
            resultado.put(e.getKey(), encontrado);
        }
        return resultado;
    }

    /** Una línea resuelta por el lote, con la cantidad con la que se evaluó la escala. */
    public record PrecioDeLinea(String productoId, int cantidad, BigDecimal precio, String origen,
                                UUID listaPrecioItemId, UUID listaPrecioId) {}

    /**
     * F1.5: los precios de varias líneas para un cliente en un momento, en una
     * sola consulta ({@code fn_precios_para}, V61). Sirve a la venta y a
     * {@code POST /api/mayorista/precios}. Un producto que no existe no aparece.
     *
     * @param momento null = ahora
     */
    public List<PrecioDeLinea> preciosDeLineas(String clienteDocumento, List<String> productos, List<Integer> cantidades,
                                               java.time.OffsetDateTime momento) {
        if (productos.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT producto_id, cantidad, precio, origen, lista_precio_item_id, lista_precio_id
                  FROM fn_precios_para(?, ?::text[], ?::integer[], COALESCE(?::timestamptz, now()))""",
                (rs, i) -> new PrecioDeLinea(rs.getString("producto_id"), rs.getInt("cantidad"),
                        rs.getBigDecimal("precio"), rs.getString("origen"),
                        rs.getObject("lista_precio_item_id", UUID.class), rs.getObject("lista_precio_id", UUID.class)),
                clienteDocumento, productos.toArray(new String[0]), cantidades.toArray(new Integer[0]), momento);
    }

    private Map<String, Precio> enLote(String clienteDocumento, Map<String, Integer> cantidadPorProducto,
                                       java.time.OffsetDateTime momento) {
        Map<String, Precio> porProducto = new LinkedHashMap<>();
        for (PrecioDeLinea l : preciosDeLineas(clienteDocumento, new ArrayList<>(cantidadPorProducto.keySet()),
                new ArrayList<>(cantidadPorProducto.values()), momento)) {
            porProducto.put(l.productoId(), new Precio(l.precio(), l.origen(), l.listaPrecioItemId(), l.listaPrecioId()));
        }
        return porProducto;
    }

    /** Las mismas líneas con el precio resuelto en vez del que mandó el POS. */
    public List<OrderItemRequestRecord> conPrecios(List<OrderItemRequestRecord> lineas, Map<String, Precio> precios) {
        List<OrderItemRequestRecord> salida = new ArrayList<>(lineas.size());
        for (OrderItemRequestRecord l : lineas) {
            Precio p = precios.get(l.productId());
            salida.add(new OrderItemRequestRecord(l.productId(), l.quantity(),
                    p == null ? l.unitPrice() : p.precio(), l.instructions(), l.comboGroup()));
        }
        return salida;
    }
}
