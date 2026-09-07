package com.suresell.orders.mayorista;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listas de precio por cliente (V45).
 *
 * <h2>La base es la LISTA, y la escala vive dentro</h2>
 *
 * El mayorista negocia por cliente, y esa negociación es una lista que
 * comparten varios clientes; el volumen es una propiedad de la lista
 * ({@code cantidad_minima} por línea). Un cliente tiene UNA lista; la base
 * resuelve el precio ({@code fn_precio_para}).
 *
 * <h2>Un precio no se edita</h2>
 *
 * Cambiar un precio es cerrar la línea vigente y abrir otra: la venta de
 * ayer sigue apuntando a la línea con la que se cobró. Lo impone un trigger
 * de la base; aquí solo se sigue el rito.
 *
 * <p>Ninguna consulta filtra por negocio: lo hace RLS con {@code app.tenant_id}.
 */
@Service
public class ListasDePrecio {

    private final JdbcTemplate jdbc;

    public ListasDePrecio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listas() {
        return jdbc.queryForList("""
                SELECT l.id, l.codigo, l.nombre, l.activa, l.creado_en,
                       (SELECT count(*) FROM listas_precio_items i WHERE i.lista_id = l.id AND i.vigente_hasta IS NULL) AS lineas_vigentes,
                       (SELECT count(*) FROM clientes c WHERE c.lista_precio_id = l.id AND c.activo) AS clientes
                  FROM listas_precio l ORDER BY l.nombre""");
    }

    @Transactional
    public UUID crearLista(String codigo, String nombre, String usuario) {
        exigir(codigo, "el código de la lista");
        exigir(nombre, "el nombre de la lista");
        exigir(usuario, "quién la crea");
        return jdbc.queryForObject("""
                INSERT INTO listas_precio (codigo, nombre, creado_por) VALUES (?, ?, ?) RETURNING id""",
                UUID.class, codigo.trim().toLowerCase(), nombre.trim(), usuario);
    }

    /** Las líneas vigentes de una lista, con el nombre del producto. */
    public List<Map<String, Object>> lineas(UUID listaId) {
        return jdbc.queryForList("""
                SELECT i.id, i.producto_id, mp.name_product AS producto, mp.price AS precio_base,
                       i.cantidad_minima, i.precio, i.vigente_desde, i.fuente, i.confianza, i.usuario_id
                  FROM listas_precio_items i
                  LEFT JOIN menu_products mp ON mp.id_product = i.producto_id
                 WHERE i.lista_id = ? AND i.vigente_hasta IS NULL
                 ORDER BY mp.name_product, i.cantidad_minima""", listaId);
    }

    /**
     * Fija el precio de un producto en una lista para una escala. Si ya había
     * una línea vigente para (lista, producto, escala) se cierra y se abre la
     * nueva: la anterior se queda, con las ventas que la usaron.
     */
    @Transactional
    public UUID fijarPrecio(UUID listaId, String productoId, int cantidadMinima, BigDecimal precio,
                            String fuente, int confianza, String usuario, String nota) {
        exigir(productoId, "el producto");
        exigir(usuario, "quién fija el precio");
        if (precio == null || precio.signum() < 0) {
            throw new IllegalArgumentException("El precio no puede ser negativo.");
        }
        if (cantidadMinima < 1) {
            throw new IllegalArgumentException("La escala empieza en 1 unidad.");
        }
        Integer existe = jdbc.queryForObject(
                "SELECT count(*) FROM menu_products WHERE id_product = ?", Integer.class, productoId);
        if (existe == null || existe == 0) {
            throw new IllegalArgumentException("El producto " + productoId + " no existe en el catálogo del negocio.");
        }
        // Cerrar la vigente (el trigger solo permite este UPDATE) y abrir la nueva.
        jdbc.update("""
                UPDATE listas_precio_items SET vigente_hasta = now()
                 WHERE lista_id = ? AND producto_id = ? AND cantidad_minima = ? AND vigente_hasta IS NULL""",
                listaId, productoId, cantidadMinima);
        return jdbc.queryForObject("""
                INSERT INTO listas_precio_items
                    (lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, nota)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                UUID.class, listaId, productoId, cantidadMinima, precio, usuario,
                fuente == null ? "declarado_comerciante" : fuente,
                fuente == null ? 1 : confianza, nota);
    }

    public List<Map<String, Object>> clientes() {
        return jdbc.queryForList("""
                SELECT c.id, c.documento, c.nombre, c.telefono, c.plazo_dias, c.activo,
                       c.lista_precio_id, l.nombre AS lista,
                       a.total_debt AS deuda, a.credit_limit AS cupo, a.status AS estado_cartera,
                       (a.total_debt > a.credit_limit) AS excede_cupo
                  FROM clientes c
                  LEFT JOIN listas_precio l ON l.id = c.lista_precio_id
                  LEFT JOIN accounts_receivable a ON a.customer_document = c.documento
                 ORDER BY c.nombre""");
    }

    @Transactional
    public UUID guardarCliente(String documento, String nombre, String telefono, UUID listaId,
                               Integer plazoDias, String usuario) {
        exigir(documento, "el documento del cliente");
        exigir(nombre, "el nombre del cliente");
        exigir(usuario, "quién lo registra");
        List<UUID> ids = jdbc.query("SELECT id FROM clientes WHERE documento = ?",
                (rs, i) -> rs.getObject("id", UUID.class), documento.trim());
        if (ids.isEmpty()) {
            return jdbc.queryForObject("""
                    INSERT INTO clientes (documento, nombre, telefono, lista_precio_id, plazo_dias, creado_por)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    UUID.class, documento.trim(), nombre.trim(), telefono, listaId, plazoDias, usuario);
        }
        jdbc.update("""
                UPDATE clientes SET nombre = ?, telefono = ?, lista_precio_id = ?, plazo_dias = ?, actualizado_en = now()
                 WHERE id = ?""", nombre.trim(), telefono, listaId, plazoDias, ids.get(0));
        return ids.get(0);
    }

    /** Lo que la base cobraría hoy: para probar una lista sin vender. */
    public List<Map<String, Object>> precioPara(String documento, String productoId, int cantidad) {
        return jdbc.queryForList(
                "SELECT precio, origen, lista_precio_item_id, lista_precio_id FROM fn_precio_para(?, ?, ?, now())",
                documento, productoId, cantidad);
    }

    private static void exigir(String valor, String que) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Falta " + que + ".");
        }
    }
}
