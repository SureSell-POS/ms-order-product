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
 * <h2>El negocio va escrito en cada consulta</h2>
 *
 * Plan de mayoristas, F0.6. Hasta aquí ninguna consulta filtraba por negocio
 * y todo se delegaba en RLS; el cruce de clientes con la cartera ni siquiera
 * unía por negocio. RLS sigue siendo el suelo, pero lo que decide la respuesta
 * se escribe: delegarlo falla con cualquier rol que salte RLS.
 *
 * <h2>El autor sale del token</h2>
 *
 * F0.5. El correo va a las columnas TEXT de siempre y el {@code users.id} a
 * {@code autor_id} (V59). La cabecera {@code X-User-Name} ya no decide nada.
 */
@Service
public class ListasDePrecio {

    private final JdbcTemplate jdbc;

    public ListasDePrecio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listas(String negocio) {
        return jdbc.queryForList("""
                SELECT l.id, l.codigo, l.nombre, l.activa, l.creado_en,
                       (SELECT count(*) FROM listas_precio_items i
                         WHERE i.tenant_id = l.tenant_id AND i.lista_id = l.id AND i.vigente_hasta IS NULL) AS lineas_vigentes,
                       (SELECT count(*) FROM clientes c
                         WHERE c.tenant_id = l.tenant_id AND c.lista_precio_id = l.id AND c.activo) AS clientes
                  FROM listas_precio l
                 WHERE l.tenant_id = ?
                 ORDER BY l.nombre""", exigirNegocio(negocio));
    }

    @Transactional
    public UUID crearLista(String negocio, String codigo, String nombre, Autor autor) {
        exigir(codigo, "el código de la lista");
        exigir(nombre, "el nombre de la lista");
        return jdbc.queryForObject("""
                INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por, autor_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                UUID.class, exigirNegocio(negocio), codigo.trim().toLowerCase(), nombre.trim(),
                exigirAutor(autor).correo(), autor.id());
    }

    /** Las líneas vigentes de una lista, con el nombre del producto. */
    public List<Map<String, Object>> lineas(String negocio, UUID listaId) {
        return jdbc.queryForList("""
                SELECT i.id, i.producto_id, mp.name_product AS producto, mp.price AS precio_base,
                       i.cantidad_minima, i.precio, i.vigente_desde, i.fuente, i.confianza, i.usuario_id
                  FROM listas_precio_items i
                  LEFT JOIN menu_products mp ON mp.tenant_id = i.tenant_id AND mp.id_product = i.producto_id
                 WHERE i.tenant_id = ? AND i.lista_id = ? AND i.vigente_hasta IS NULL
                 ORDER BY mp.name_product, i.cantidad_minima""", exigirNegocio(negocio), listaId);
    }

    /**
     * Fija el precio de un producto en una lista para una escala. Si ya había
     * una línea vigente para (lista, producto, escala) se cierra y se abre la
     * nueva: la anterior se queda, con las ventas que la usaron.
     */
    @Transactional
    public UUID fijarPrecio(String negocio, UUID listaId, String productoId, int cantidadMinima, BigDecimal precio,
                            String fuente, int confianza, Autor autor, String nota) {
        exigirNegocio(negocio);
        exigir(productoId, "el producto");
        exigirAutor(autor);
        if (precio == null || precio.signum() < 0) {
            throw new IllegalArgumentException("El precio no puede ser negativo.");
        }
        if (cantidadMinima < 1) {
            throw new IllegalArgumentException("La escala empieza en 1 unidad.");
        }
        Integer existe = jdbc.queryForObject(
                "SELECT count(*) FROM menu_products WHERE tenant_id = ? AND id_product = ?",
                Integer.class, negocio, productoId);
        if (existe == null || existe == 0) {
            throw new IllegalArgumentException("El producto " + productoId + " no existe en el catálogo del negocio.");
        }
        Integer lista = jdbc.queryForObject(
                "SELECT count(*) FROM listas_precio WHERE tenant_id = ? AND id = ?", Integer.class, negocio, listaId);
        if (lista == null || lista == 0) {
            throw new IllegalArgumentException("La lista " + listaId + " no existe en el negocio.");
        }
        // Cerrar la vigente (el trigger solo permite este UPDATE) y abrir la nueva.
        jdbc.update("""
                UPDATE listas_precio_items SET vigente_hasta = now()
                 WHERE tenant_id = ? AND lista_id = ? AND producto_id = ? AND cantidad_minima = ?
                   AND vigente_hasta IS NULL""",
                negocio, listaId, productoId, cantidadMinima);
        return jdbc.queryForObject("""
                INSERT INTO listas_precio_items
                    (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, nota, autor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                UUID.class, negocio, listaId, productoId, cantidadMinima, precio, autor.correo(),
                fuente == null ? "declarado_comerciante" : fuente,
                fuente == null ? 1 : confianza, nota, autor.id());
    }

    public List<Map<String, Object>> clientes(String negocio) {
        // El cruce con la cartera es por documento, que se repite entre
        // negocios (un NIT le compra a dos distribuidoras): sin
        // `a.tenant_id = c.tenant_id`, un rol que salte RLS le pegaría a este
        // cliente la deuda que tiene con otro.
        return jdbc.queryForList("""
                SELECT c.id, c.documento, c.nombre, c.telefono, c.plazo_dias, c.activo,
                       c.lista_precio_id, l.nombre AS lista,
                       a.total_debt AS deuda, a.credit_limit AS cupo, a.status AS estado_cartera,
                       (a.total_debt > a.credit_limit) AS excede_cupo
                  FROM clientes c
                  LEFT JOIN listas_precio l ON l.tenant_id = c.tenant_id AND l.id = c.lista_precio_id
                  LEFT JOIN accounts_receivable a ON a.tenant_id = c.tenant_id AND a.customer_document = c.documento
                 WHERE c.tenant_id = ?
                 ORDER BY c.nombre""", exigirNegocio(negocio));
    }

    @Transactional
    public UUID guardarCliente(String negocio, String documento, String nombre, String telefono, UUID listaId,
                               Integer plazoDias, Autor autor) {
        exigirNegocio(negocio);
        exigir(documento, "el documento del cliente");
        exigir(nombre, "el nombre del cliente");
        exigirAutor(autor);
        List<UUID> ids = jdbc.query("SELECT id FROM clientes WHERE tenant_id = ? AND documento = ?",
                (rs, i) -> rs.getObject("id", UUID.class), negocio, documento.trim());
        if (ids.isEmpty()) {
            return jdbc.queryForObject("""
                    INSERT INTO clientes (tenant_id, documento, nombre, telefono, lista_precio_id, plazo_dias,
                                          creado_por, autor_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    UUID.class, negocio, documento.trim(), nombre.trim(), telefono, listaId, plazoDias,
                    autor.correo(), autor.id());
        }
        // `autor_id` es de quien lo REGISTRÓ: editarlo no cambia el autor. La
        // historia de los cambios es `clientes_eventos` (F1.6), no esta fila.
        jdbc.update("""
                UPDATE clientes SET nombre = ?, telefono = ?, lista_precio_id = ?, plazo_dias = ?, actualizado_en = now()
                 WHERE tenant_id = ? AND id = ?""", nombre.trim(), telefono, listaId, plazoDias, negocio, ids.get(0));
        return ids.get(0);
    }

    /**
     * F1.5: las líneas de una lista para la caché del POS. Sin {@code desde}, las
     * vigentes. Con {@code desde}, solo lo que cambió después: las que empezaron a
     * regir y las que se cerraron (con {@code vigenteHasta}), para que la caché
     * quite las cerradas sin descargar la lista entera otra vez.
     *
     * <p>{@code servidoEn} es el reloj del servidor al responder: el POS lo manda
     * como {@code desde} la próxima vez, y así no depende de su propio reloj.
     */
    public Map<String, Object> catalogoDeLista(String negocio, UUID listaId, java.time.OffsetDateTime desde) {
        exigirNegocio(negocio);
        Integer existe = jdbc.queryForObject(
                "SELECT count(*) FROM listas_precio WHERE tenant_id = ? AND id = ?", Integer.class, negocio, listaId);
        if (existe == null || existe == 0) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("listaId",
                    "Esa lista no existe en el negocio.");
        }
        java.time.OffsetDateTime servidoEn = jdbc.queryForObject("SELECT now()", java.time.OffsetDateTime.class);
        List<Map<String, Object>> lineas = desde == null
                ? jdbc.queryForList("""
                    SELECT i.id, i.producto_id AS "productoId", i.cantidad_minima AS "cantidadMinima", i.precio,
                           i.vigente_desde AS "vigenteDesde", i.vigente_hasta AS "vigenteHasta"
                      FROM listas_precio_items i
                     WHERE i.tenant_id = ? AND i.lista_id = ? AND i.vigente_hasta IS NULL
                     ORDER BY i.producto_id, i.cantidad_minima""", negocio, listaId)
                : jdbc.queryForList("""
                    SELECT i.id, i.producto_id AS "productoId", i.cantidad_minima AS "cantidadMinima", i.precio,
                           i.vigente_desde AS "vigenteDesde", i.vigente_hasta AS "vigenteHasta"
                      FROM listas_precio_items i
                     WHERE i.tenant_id = ? AND i.lista_id = ?
                       AND (i.vigente_desde > ? OR i.vigente_hasta > ?)
                     ORDER BY i.producto_id, i.cantidad_minima""", negocio, listaId, desde, desde);
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("listaId", listaId);
        r.put("desde", desde);
        r.put("servidoEn", servidoEn);
        r.put("lineas", lineas);
        return r;
    }

    /** Lo que la base cobraría hoy: para probar una lista sin vender. */
    public List<Map<String, Object>> precioPara(String documento, String productoId, int cantidad) {
        return jdbc.queryForList(
                "SELECT precio, origen, lista_precio_item_id, lista_precio_id FROM fn_precio_para(?, ?, ?, now())",
                documento, productoId, cantidad);
    }

    /** Quién hace la operación, según el token: correo ({@code sub}) y {@code users.id}. */
    public record Autor(String correo, Long id) {}

    private static String exigirNegocio(String negocio) {
        if (negocio == null || negocio.isBlank()) {
            throw new IllegalStateException("Operación de mayorista sin negocio en contexto.");
        }
        return negocio;
    }

    private static Autor exigirAutor(Autor autor) {
        if (autor == null || autor.correo() == null || autor.correo().isBlank()) {
            throw new IllegalStateException("Operación de mayorista sin usuario en el token.");
        }
        return autor;
    }

    private static void exigir(String valor, String que) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Falta " + que + ".");
        }
    }
}
