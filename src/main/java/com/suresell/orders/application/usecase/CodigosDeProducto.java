package com.suresell.orders.application.usecase;

import com.suresell.orders.application.dto.CodigoDeProductoResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Los códigos con los que la caja encuentra un producto (V51).
 *
 * <h2>Una consulta para todo el catálogo, no una por producto</h2>
 *
 * {@code GET /api/menu/categories-with-products} devuelve el catálogo entero
 * al POS de una vez (así lo cachea offline). Los códigos se leen con UNA
 * consulta agrupada por producto y se adjuntan en memoria; con 2.000
 * productos, lo contrario eran 2.000 consultas para pintar una pantalla
 * ({@code costo-de-infra-al-minimo}).
 *
 * <h2>El aislamiento lo pone la base</h2>
 *
 * Todas las consultas van sin {@code tenant_id} en el WHERE: la política RLS
 * de V51 filtra por {@code app.tenant_id}, y el INSERT toma el negocio del
 * DEFAULT de la columna. Igual que {@code ResolucionDePrecios} (V45).
 *
 * <h2>Retirar no es borrar</h2>
 *
 * Un código se cierra con {@code retirado_en}; la fila se queda. El índice
 * único es parcial sobre los vigentes, así que el mismo código puede pasar a
 * otro producto después.
 */
@Service
public class CodigosDeProducto {

    /** Enum cerrado, el mismo del CHECK de V51. */
    public static final Set<String> TIPOS = Set.of("EAN", "PLU", "BASCULA", "PROVEEDOR");

    private final JdbcTemplate jdbc;

    public CodigosDeProducto(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los códigos vigentes del negocio, agrupados por producto. Una consulta. */
    public Map<String, List<CodigoDeProductoResponse>> vigentesPorProducto() {
        Map<String, List<CodigoDeProductoResponse>> porProducto = new LinkedHashMap<>();
        jdbc.query("""
                SELECT producto_id, codigo, cantidad, tipo
                  FROM public.codigos_de_producto
                 WHERE retirado_en IS NULL
                 ORDER BY producto_id, creado_en""",
                rs -> {
                    porProducto.computeIfAbsent(rs.getString("producto_id"), k -> new ArrayList<>())
                            .add(new CodigoDeProductoResponse(
                                    rs.getString("codigo"), rs.getBigDecimal("cantidad"), rs.getString("tipo")));
                });
        return porProducto;
    }

    /** Los códigos vigentes de un producto. */
    public List<CodigoDeProductoResponse> deProducto(String productoId) {
        return jdbc.query("""
                SELECT codigo, cantidad, tipo
                  FROM public.codigos_de_producto
                 WHERE producto_id = ? AND retirado_en IS NULL
                 ORDER BY creado_en""",
                (rs, i) -> new CodigoDeProductoResponse(
                        rs.getString("codigo"), rs.getBigDecimal("cantidad"), rs.getString("tipo")),
                productoId);
    }

    /**
     * Da de alta un código. Un código repetido en el mismo negocio lo rechaza
     * la base (23505 → 409 {@code YA_EXISTE}, {@code GlobalExceptionHandler});
     * un tipo fuera del enum, aquí (400 con {@code campo}).
     */
    @Transactional
    public CodigoDeProductoResponse agregar(String productoId, String codigo, BigDecimal cantidad,
                                            String tipo, String fuente, String usuario) {
        String limpio = codigo == null ? "" : codigo.trim();
        if (limpio.isEmpty()) {
            throw new CampoInvalido("codigo", "El código no puede estar vacío.");
        }
        String tipoNormalizado = tipo == null || tipo.isBlank() ? "EAN" : tipo.trim().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipoNormalizado)) {
            throw new CampoInvalido("tipo", "El tipo tiene que ser uno de EAN, PLU, BASCULA o PROVEEDOR.");
        }
        BigDecimal cuantos = cantidad == null ? BigDecimal.ONE : cantidad;
        if (cuantos.signum() <= 0) {
            throw new CampoInvalido("cantidad", "La cantidad tiene que ser mayor que cero.");
        }
        Integer existe = jdbc.queryForObject(
                "SELECT count(*) FROM public.menu_products WHERE id_product = ?", Integer.class, productoId);
        if (existe == null || existe == 0) {
            throw new ProductoInexistente(productoId);
        }
        jdbc.update("""
                INSERT INTO public.codigos_de_producto (codigo, producto_id, cantidad, tipo, fuente, creado_por)
                VALUES (?, ?, ?, ?, ?, ?)""",
                limpio, productoId, cuantos, tipoNormalizado, fuente, usuario);
        return new CodigoDeProductoResponse(limpio, cuantos, tipoNormalizado);
    }

    /** Cierra un código vigente. Devuelve cuántos cerró (0 si no estaba). */
    @Transactional
    public int retirar(String productoId, String codigo, String usuario) {
        return jdbc.update("""
                UPDATE public.codigos_de_producto
                   SET retirado_en = now(), retirado_por = ?
                 WHERE producto_id = ? AND codigo = ? AND retirado_en IS NULL""",
                usuario, productoId, codigo == null ? "" : codigo.trim());
    }

    /** Un campo del cuerpo no vale. Sale como 400 con {@code campo}. */
    public static class CampoInvalido extends RuntimeException {
        private final String campo;

        public CampoInvalido(String campo, String mensaje) {
            super(mensaje);
            this.campo = campo;
        }

        public String campo() {
            return campo;
        }
    }

    /** El producto no está en el catálogo de este negocio. Sale como 404. */
    public static class ProductoInexistente extends RuntimeException {
        public ProductoInexistente(String productoId) {
            super("El producto " + productoId + " no existe en el catálogo de este negocio.");
        }
    }
}
