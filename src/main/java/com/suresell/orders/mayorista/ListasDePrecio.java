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
        return clientes(negocio, null, null, null);
    }

    /** Columnas del cliente que viajan a pantalla (F1.6). */
    private static final String COLUMNAS_DEL_CLIENTE = """
            c.id, c.documento, c.nombre, c.telefono, c.plazo_dias, c.activo,
            c.lista_precio_id, l.nombre AS lista,
            c.tipo_documento, c.razon_social, c.tipo_cliente, c.direccion_entrega, c.municipio_dane,
            c.correo, c.whatsapp, c.vendedor_id, v.nombre AS vendedor, c.exige_factura, c.en_insolvencia_desde, c.dv,
            libro.saldo AS deuda, a.credit_limit AS cupo, a.status AS estado_cartera,
            (libro.saldo > a.credit_limit) AS excede_cupo
            """;

    /**
     * El cruce con la cartera es por documento, que se repite entre negocios (un
     * NIT le compra a dos distribuidoras): sin {@code a.tenant_id = c.tenant_id},
     * un rol que salte RLS le pegaría a este cliente la deuda que tiene con otro.
     */
    private static final String DESDE_CLIENTES = """
              FROM clientes c
              LEFT JOIN listas_precio l ON l.tenant_id = c.tenant_id AND l.id = c.lista_precio_id
              LEFT JOIN users v ON v.tenant_id = c.tenant_id AND v.id = c.vendedor_id
              LEFT JOIN accounts_receivable a ON a.tenant_id = c.tenant_id AND a.customer_document = c.documento
              -- F4.4: la deuda sale del libro (DEBIT − CREDIT), nunca de total_debt.
              LEFT JOIN LATERAL (
                  SELECT COALESCE(sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END), 0) AS saldo
                    FROM debt_transactions d
                   WHERE d.tenant_id = a.tenant_id AND d.account_id = a.id) libro ON a.id IS NOT NULL
            """;

    /**
     * F1.6: la lista de clientes, filtrada en el SERVIDOR. {@code vendedorId} no
     * nulo = solo los de ese vendedor (quien llama ya decidió si lo fuerza, ver
     * el controlador); {@code q} busca por nombre, documento o teléfono;
     * {@code activos} true/false filtra, null trae todos.
     */
    public List<Map<String, Object>> clientes(String negocio, Long vendedorId, String q, Boolean activos) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNAS_DEL_CLIENTE).append(DESDE_CLIENTES)
                .append(" WHERE c.tenant_id = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(exigirNegocio(negocio)));
        if (vendedorId != null) {
            sql.append(" AND c.vendedor_id = ?");
            args.add(vendedorId);
        }
        if (activos != null) {
            sql.append(" AND c.activo = ?");
            args.add(activos);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (c.nombre ILIKE ? OR c.documento ILIKE ? OR c.telefono ILIKE ?)");
            String patron = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(patron);
            args.add(patron);
            args.add(patron);
        }
        sql.append(" ORDER BY c.nombre");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** F1.6: la ficha de un cliente del negocio, o vacío. */
    public java.util.Optional<Map<String, Object>> cliente(String negocio, String documento) {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "SELECT " + COLUMNAS_DEL_CLIENTE + DESDE_CLIENTES + " WHERE c.tenant_id = ? AND c.documento = ?",
                exigirNegocio(negocio), documento == null ? null : documento.trim());
        return filas.stream().findFirst();
    }

    /**
     * Los campos editables de un cliente (F1.6, contrato 2026-09-14). REEMPLAZO
     * COMPLETO: llega el formulario entero y {@code null} (o texto en blanco) vacía
     * el campo. {@code nombre} es obligatorio; {@code exigeFactura} null es false;
     * {@code plazoDias} null es «sin plazo pactado». Fuera: documento, activo y cupo.
     */
    public record CambiosDelCliente(String nombre, String telefono, UUID listaPrecioId, Integer plazoDias,
                                    String tipoDocumento, String razonSocial, String tipoCliente,
                                    String direccionEntrega, String municipioDane, String correo, String whatsapp,
                                    Long vendedorId, Boolean exigeFactura) {}

    static final java.util.Set<String> TIPOS_DE_DOCUMENTO = java.util.Set.of("CC", "NIT", "CE", "PAS", "PPT", "TI");
    static final java.util.Set<String> TIPOS_DE_CLIENTE = java.util.Set.of(
            "TIENDA_DE_BARRIO", "MINIMERCADO", "SUPERMERCADO", "GRANERO", "LICORERIA", "DROGUERIA",
            "FERRETERIA", "MISCELANEA", "PAPELERIA", "DULCERIA", "PANADERIA", "RESTAURANTE",
            "CAFETERIA", "BAR_CIGARRERIA", "HOTEL", "INSTITUCIONAL", "SUBDISTRIBUIDOR");

    /**
     * F1.6: reemplaza los campos editables de un cliente del negocio (un PUT a
     * medias BORRA lo que no mande: hoy solo lo llama el panel, que manda el
     * formulario entero). Cada campo que cambia queda en
     * {@code clientes_eventos} con su autor: se fija {@code app.user_id} en esta
     * transacción, que es lo que lee el disparador de V62. Los catálogos se validan
     * aquí para responder con {@code campo}; la base es el suelo.
     *
     * @return false si el cliente no existe en el negocio
     */
    @Transactional
    public boolean actualizarCliente(String negocio, String documento, CambiosDelCliente c, Autor autor) {
        exigirNegocio(negocio);
        exigirAutor(autor);
        String tipoDoc = mayusculasONulo(c.tipoDocumento());
        if (tipoDoc != null && !TIPOS_DE_DOCUMENTO.contains(tipoDoc)) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("tipoDocumento",
                    "Tipo de documento: CC, NIT, CE, PAS, PPT o TI.");
        }
        String tipoCliente = mayusculasONulo(c.tipoCliente());
        if (tipoCliente != null && !TIPOS_DE_CLIENTE.contains(tipoCliente)) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("tipoCliente",
                    "Ese tipo de cliente no está en la lista. Si falta uno, se añade: pídeselo a SureSell.");
        }
        if (c.municipioDane() != null && !c.municipioDane().isBlank() && !c.municipioDane().trim().matches("^[0-9]{5}$")) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("municipioDane",
                    "El código DANE del municipio tiene 5 dígitos.");
        }
        if (c.plazoDias() != null && (c.plazoDias() < 0 || c.plazoDias() > 365)) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("plazoDias", "El plazo va de 0 a 365 días.");
        }
        if (c.nombre() == null || c.nombre().isBlank()) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("nombre", "El nombre no puede quedar vacío.");
        }
        if (c.listaPrecioId() != null && jdbc.queryForList("SELECT 1 FROM listas_precio WHERE tenant_id = ? AND id = ?",
                Integer.class, negocio, c.listaPrecioId()).isEmpty()) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("listaPrecioId",
                    "La lista de precios no es de este negocio.");
        }
        if (c.vendedorId() != null) {
            List<String> rol = jdbc.queryForList("SELECT role FROM users WHERE tenant_id = ? AND id = ?",
                    String.class, negocio, c.vendedorId());
            if (rol.isEmpty()) {
                throw new com.suresell.orders.shared.exception.DatoInvalidoException("vendedorId",
                        "El vendedor no es de este negocio.");
            }
        }
        // F4.10: el DV no lo manda nadie: sale del documento (que no se edita). Sin NIT, no hay DV.
        Integer dv = null;
        if ("NIT".equals(tipoDoc)) {
            dv = Nit.dv(documento.trim());
            if (dv == null) {
                throw new com.suresell.orders.shared.exception.DatoInvalidoException("documento",
                        "Un NIT solo lleva números: este documento no puede ser NIT.");
            }
        }
        fijarAutor(autor);
        int n = jdbc.update("""
                UPDATE clientes SET
                       dv = ?,
                       nombre = ?, telefono = ?, lista_precio_id = ?, plazo_dias = ?, tipo_documento = ?,
                       razon_social = ?, tipo_cliente = ?, direccion_entrega = ?, municipio_dane = ?,
                       correo = ?, whatsapp = ?, vendedor_id = ?, exige_factura = ?,
                       actualizado_en = now()
                 WHERE tenant_id = ? AND documento = ?""",
                dv, c.nombre().trim(), vacioANulo(c.telefono()), c.listaPrecioId(), c.plazoDias(), tipoDoc,
                vacioANulo(c.razonSocial()), tipoCliente, vacioANulo(c.direccionEntrega()), vacioANulo(c.municipioDane()),
                vacioANulo(c.correo()), vacioANulo(c.whatsapp()), c.vendedorId(), Boolean.TRUE.equals(c.exigeFactura()),
                negocio, documento.trim());
        return n > 0;
    }

    /** F1.6: desactivar, nunca borrar (las ventas y la cartera lo nombran). */
    @Transactional
    public boolean desactivarCliente(String negocio, String documento, Autor autor) {
        return fijarActivo(negocio, documento, false, autor);
    }

    /** F1.11: simétrico a desactivar. Reactivar uno activo no escribe evento (el disparador solo anota cambios). */
    @Transactional
    public boolean reactivarCliente(String negocio, String documento, Autor autor) {
        return fijarActivo(negocio, documento, true, autor);
    }

    private boolean fijarActivo(String negocio, String documento, boolean activo, Autor autor) {
        exigirNegocio(negocio);
        exigirAutor(autor);
        fijarAutor(autor);
        return jdbc.update("UPDATE clientes SET activo = ?, actualizado_en = now() WHERE tenant_id = ? AND documento = ?",
                activo, negocio, documento.trim()) > 0;
    }

    public static final int EVENTOS_POR_DEFECTO = 50;
    public static final int EVENTOS_MAXIMO = 200;

    /** Una página del historial y el cursor de la siguiente (null si no hay más). */
    public record PaginaDeEventos(List<Map<String, Object>> eventos, String siguiente) {}

    /**
     * F1.11: el historial de un cliente, lo más reciente primero. Cursor y no
     * offset: un mismo cambio escribe varias filas con el mismo {@code ocurrido_en},
     * así que el cursor es {@code ocurrido_en} + {@code id} y ninguna se salta ni se
     * repite al cortar la página.
     *
     * @return vacío si el cliente no existe en el negocio
     */
    public java.util.Optional<PaginaDeEventos> eventosDelCliente(String negocio, String documento, String antesDe,
                                                                 Integer limite) {
        exigirNegocio(negocio);
        int n = limite == null ? EVENTOS_POR_DEFECTO : limite;
        if (n < 1 || n > EVENTOS_MAXIMO) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("limite",
                    "El límite va de 1 a " + EVENTOS_MAXIMO + ".");
        }
        List<UUID> cliente = jdbc.queryForList("SELECT id FROM clientes WHERE tenant_id = ? AND documento = ?",
                UUID.class, negocio, documento == null ? null : documento.trim());
        if (cliente.isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.campo, e.valor_anterior, e.valor_nuevo, e.usuario_id,
                       COALESCE(u.nombre, u.email) AS usuario, e.ocurrido_en
                  FROM clientes_eventos e
                  LEFT JOIN users u ON u.tenant_id = e.tenant_id AND u.id = e.usuario_id
                 WHERE e.tenant_id = ? AND e.cliente_id = ?""");
        List<Object> args = new java.util.ArrayList<>(List.of(negocio, cliente.get(0)));
        if (antesDe != null && !antesDe.isBlank()) {
            Cursor cursor = Cursor.leer(antesDe);
            sql.append(" AND (e.ocurrido_en, e.id) < (?, ?)");
            args.add(java.sql.Timestamp.from(cursor.ocurridoEn()));
            args.add(cursor.id());
        }
        sql.append(" ORDER BY e.ocurrido_en DESC, e.id DESC LIMIT ?");
        args.add(n + 1);
        List<Map<String, Object>> filas = new java.util.ArrayList<>(jdbc.queryForList(sql.toString(), args.toArray()));
        String siguiente = null;
        if (filas.size() > n) {
            filas = new java.util.ArrayList<>(filas.subList(0, n));
            Map<String, Object> ultima = filas.get(n - 1);
            siguiente = new Cursor(instante(ultima.get("ocurrido_en")), (UUID) ultima.get("id")).escribir();
        }
        for (Map<String, Object> f : filas) {
            f.put("ocurrido_en", instante(f.get("ocurrido_en")).toString());
        }
        return java.util.Optional.of(new PaginaDeEventos(filas, siguiente));
    }

    private static java.time.Instant instante(Object valor) {
        if (valor instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return ((java.sql.Timestamp) valor).toInstant();
    }

    /** {@code <instante ISO en UTC>_<uuid>}. Opaco para el panel: lo devuelve tal cual. */
    record Cursor(java.time.Instant ocurridoEn, UUID id) {
        String escribir() {
            return ocurridoEn + "_" + id;
        }

        static Cursor leer(String texto) {
            try {
                int corte = texto.lastIndexOf('_');
                return new Cursor(java.time.Instant.parse(texto.substring(0, corte)),
                        UUID.fromString(texto.substring(corte + 1)));
            } catch (RuntimeException e) {
                throw new com.suresell.orders.shared.exception.DatoInvalidoException("antesDe",
                        "El cursor no es válido: usa el valor «siguiente» de la página anterior.");
            }
        }
    }

    /** El autor que lee el disparador de `clientes_eventos` (V62), acotado a la transacción. */
    private void fijarAutor(Autor autor) {
        jdbc.query("SELECT set_config('app.user_id', ?, true)", rs -> null,
                autor.id() == null ? "" : String.valueOf(autor.id()));
    }

    private static String recortar(String s) {
        return s == null ? null : s.trim();
    }

    private static String vacioANulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String mayusculasONulo(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }

    public UUID guardarCliente(String negocio, String documento, String nombre, String telefono, UUID listaId,
                               Integer plazoDias, Autor autor) {
        return guardarCliente(negocio, documento, nombre, telefono, listaId, plazoDias, null, autor);
    }

    /**
     * Alta (o actualización) de un cliente. F4.10: un NIT llega con o sin DV
     * («900123456-7» o «9001234567»); el DV se separa del número, se valida si
     * vino ({@code 400 documento} «El dígito de verificación no corresponde») y se
     * guarda en {@code dv}. Un documento con guion y DV sin tipo se toma por NIT.
     */
    // Una sola transacción: el autor (set_config local) y el UPDATE que lee el disparador.
    @Transactional
    public UUID guardarCliente(String negocio, String documento, String nombre, String telefono, UUID listaId,
                               Integer plazoDias, String tipoDocumento, Autor autor) {
        exigirNegocio(negocio);
        exigir(documento, "el documento del cliente");
        exigir(nombre, "el nombre del cliente");
        exigirAutor(autor);
        String tipo = mayusculasONulo(tipoDocumento);
        if (tipo != null && !TIPOS_DE_DOCUMENTO.contains(tipo)) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("tipoDocumento",
                    "Tipo de documento: CC, NIT, CE, PAS, PPT o TI.");
        }
        String doc = documento.trim();
        Integer dv = null;
        boolean traeGuionDeDv = doc.replaceAll("[\\s.]", "").matches("^[0-9]+-[0-9]$");
        if ("NIT".equals(tipo) || (tipo == null && traeGuionDeDv)) {
            Nit.Separado separado = Nit.separar(doc);
            Integer calculado = Nit.dv(separado.numero());
            if (calculado == null) {
                throw new com.suresell.orders.shared.exception.DatoInvalidoException("documento",
                        "Un NIT solo lleva números y, si acaso, el guion del dígito de verificación.");
            }
            if (separado.dv() != null && !separado.dv().equals(calculado)) {
                throw new com.suresell.orders.shared.exception.DatoInvalidoException("documento",
                        "El dígito de verificación no corresponde");
            }
            doc = separado.numero();
            dv = calculado;
            tipo = "NIT";
        } else if (traeGuionDeDv) {
            throw new com.suresell.orders.shared.exception.DatoInvalidoException("documento",
                    "Un documento " + tipo + " no lleva dígito de verificación.");
        }
        List<UUID> ids = jdbc.query("SELECT id FROM clientes WHERE tenant_id = ? AND documento = ?",
                (rs, i) -> rs.getObject("id", UUID.class), negocio, doc);
        if (ids.isEmpty()) {
            return jdbc.queryForObject("""
                    INSERT INTO clientes (tenant_id, documento, nombre, telefono, lista_precio_id, plazo_dias,
                                          tipo_documento, dv, creado_por, autor_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    UUID.class, negocio, doc, nombre.trim(), telefono, listaId, plazoDias, tipo, dv,
                    autor.correo(), autor.id());
        }
        // `autor_id` es de quien lo REGISTRÓ: editarlo no cambia el autor. La
        // historia de los cambios es `clientes_eventos` (V62), con el autor de aquí.
        fijarAutor(autor);
        // Sin tipo en la petición, el tipo y el DV guardados no cambian.
        jdbc.update("""
                UPDATE clientes SET nombre = ?, telefono = ?, lista_precio_id = ?, plazo_dias = ?,
                       tipo_documento = COALESCE(?, tipo_documento),
                       dv = CASE WHEN ?::text IS NULL THEN dv ELSE ?::smallint END,
                       actualizado_en = now()
                 WHERE tenant_id = ? AND id = ?""", nombre.trim(), telefono, listaId, plazoDias,
                tipo, tipo, dv, negocio, ids.get(0));
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
