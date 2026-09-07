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
        Map<String, Integer> cantidadPorProducto = new LinkedHashMap<>();
        for (OrderItemRequestRecord l : lineas) {
            cantidadPorProducto.merge(l.productId(), l.quantity(), Integer::sum);
        }
        Map<String, Precio> resultado = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cantidadPorProducto.entrySet()) {
            List<Precio> filas = jdbc.query("""
                    SELECT precio, origen, lista_precio_item_id, lista_precio_id
                      FROM fn_precio_para(?, ?, ?, now())""",
                    (rs, i) -> new Precio(rs.getBigDecimal("precio"), rs.getString("origen"),
                            rs.getObject("lista_precio_item_id", UUID.class),
                            rs.getObject("lista_precio_id", UUID.class)),
                    clienteDocumento, e.getKey(), e.getValue());
            if (filas.isEmpty()) {
                // Sin fila no hay producto: ni en la lista ni en el catálogo.
                // No se vende algo que no existe a un precio que mandó el cliente.
                throw new IllegalArgumentException(
                        "El producto " + e.getKey() + " no existe en el catálogo: no se le puede poner precio.");
            }
            resultado.put(e.getKey(), filas.get(0));
        }
        return resultado;
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
