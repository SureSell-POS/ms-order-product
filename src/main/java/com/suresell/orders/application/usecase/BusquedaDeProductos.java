package com.suresell.orders.application.usecase;

import com.suresell.orders.application.dto.CodigoDeProductoResponse;
import com.suresell.orders.shared.exception.DatoInvalidoException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F5.8e: buscar productos del negocio por código o nombre, paginado por tope, para «Nuevo pedido» del panel (un
 * mayorista tiene de 1.000 a 5.000 referencias y el panel bajaba 500).
 *
 * <ul>
 *   <li>Filtro de negocio ESCRITO ({@code tenant_id = ?}); RLS sigue debajo. Solo productos activos.</li>
 *   <li>Coincide por código vigente exacto o por prefijo (V51), o por nombre que contiene {@code q}, sin distinguir
 *       tildes ni mayúsculas ({@link NormalizacionDeBusqueda}). El código se compara tal cual: un EAN no se normaliza.</li>
 *   <li>Orden estable: código exacto, prefijo de código, nombre que empieza por {@code q}, nombre que lo contiene; y en
 *       cada grupo por nombre y por id.</li>
 *   <li>SIN precio: el del pedido lo pone la lista del cliente.</li>
 *   <li>{@code q} de menos de 3 caracteres → 400 {@code q}. {@code limit} por defecto 20, máximo 50.</li>
 * </ul>
 *
 * <p><b>Costo y umbral de revisión</b> (medido el 2026-09-15, CostoDeLaBusquedaTest, sin JIT, como app_user): 10–15 ms
 * con 5.000 productos; 62–68 ms con 50.000. No lleva índice: un GIN trigram sobre el nombre normalizado NO se usa con RLS,
 * porque {@code textlike} no es leakproof (como dueño sin RLS sí, 9 ms). Decidido con ECM: si aparece un negocio real con
 * más de ~20.000 productos activos, o esta búsqueda pasa de ~300 ms por API en staging, la vía es una función SECURITY
 * DEFINER de búsqueda con el filtro de negocio escrito + índice GIN trigram, con pg_trgm medido en producción, en
 * migración propia.
 */
@Component
public class BusquedaDeProductos {

    public static final int LIMITE_POR_DEFECTO = 20;
    public static final int LIMITE_MAXIMO = 50;
    public static final int MINIMO_DE_CARACTERES = 3;

    /**
     * La consulta, expuesta para medir su costo (CostoDeLaBusquedaTest). Dos ramas unidas (por código y por nombre) en
     * vez de un OR: con el OR sobre el LEFT JOIN, un índice trigram sobre el nombre no se puede usar.
     * Parámetros, en orden: q, negocio, qPrefijo, negocio, qnContiene, qnPrefijo, qnContiene, negocio, limit.
     */
    public static final String CONSULTA = """
            WITH por_codigo AS (
                SELECT k.producto_id, min(CASE WHEN k.codigo = ? THEN 0 ELSE 1 END) AS grupo
                  FROM public.codigos_de_producto k
                 WHERE k.tenant_id = ? AND k.retirado_en IS NULL AND k.codigo LIKE ? ESCAPE '\\'
                 GROUP BY k.producto_id
            ), por_nombre AS (
                SELECT p.id_product
                  FROM public.menu_products p
                 WHERE p.tenant_id = ? AND p.active AND %1$s LIKE ? ESCAPE '\\'
            ), ids AS (
                SELECT producto_id AS id FROM por_codigo
                UNION
                SELECT id_product FROM por_nombre
            )
            SELECT p.id_product, p.name_product,
                   LEAST(COALESCE(c.grupo, 9),
                         CASE WHEN %1$s LIKE ? ESCAPE '\\' THEN 2 WHEN %1$s LIKE ? ESCAPE '\\' THEN 3 ELSE 9 END) AS grupo
              FROM ids
              JOIN public.menu_products p ON p.id_product = ids.id AND p.tenant_id = ? AND p.active
              LEFT JOIN por_codigo c ON c.producto_id = p.id_product
             ORDER BY grupo, p.name_product, p.id_product
             LIMIT ?""".formatted(NormalizacionDeBusqueda.sql("p.name_product"));

    public record Encontrado(String id, String nombre, List<CodigoDeProductoResponse> codigos) {
    }

    private final JdbcTemplate jdbc;

    public BusquedaDeProductos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Encontrado> buscar(String q, Integer limit) {
        String texto = q == null ? "" : q.trim();
        if (texto.length() < MINIMO_DE_CARACTERES) {
            throw new DatoInvalidoException("q", "Escribe al menos " + MINIMO_DE_CARACTERES + " caracteres para buscar.");
        }
        int tope = limit == null ? LIMITE_POR_DEFECTO : Math.max(1, Math.min(limit, LIMITE_MAXIMO));
        String negocio = com.suresell.orders.multitenant.TenantContext.get();
        if (negocio == null) {
            return List.of();
        }
        String literal = escaparLike(texto);
        String normalizado = escaparLike(NormalizacionDeBusqueda.normalizar(texto));
        List<Object[]> filas = jdbc.query(CONSULTA, (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)},
                texto, negocio, literal + "%", negocio, "%" + normalizado + "%", normalizado + "%", "%" + normalizado + "%", negocio, tope);
        if (filas.isEmpty()) {
            return List.of();
        }
        Map<String, List<CodigoDeProductoResponse>> codigos = new LinkedHashMap<>();
        String[] ids = filas.stream().map(f -> (String) f[0]).toArray(String[]::new);
        jdbc.query("""
                SELECT producto_id, codigo, cantidad, tipo
                  FROM public.codigos_de_producto
                 WHERE tenant_id = ? AND producto_id = ANY (?) AND retirado_en IS NULL
                 ORDER BY producto_id, creado_en""",
                rs -> {
                    codigos.computeIfAbsent(rs.getString("producto_id"), k -> new ArrayList<>())
                            .add(new CodigoDeProductoResponse(rs.getString("codigo"), rs.getBigDecimal("cantidad"), rs.getString("tipo")));
                }, negocio, ids);
        return filas.stream()
                .map(f -> new Encontrado((String) f[0], (String) f[1], codigos.getOrDefault((String) f[0], List.of())))
                .toList();
    }

    /** {@code %} y {@code _} de lo que escribe la persona no son comodines. */
    static String escaparLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
