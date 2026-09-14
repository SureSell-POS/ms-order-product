package com.suresell.orders.multitenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enforcement de módulos por plan (F3, docs/160 / docs/50 §4): el backend HACE
 * CUMPLIR qué módulos tiene el tenant, no solo la UI. Si el path pertenece a un
 * módulo que el tenant NO tiene (según el claim `modules` del JWT), responde 403.
 *
 * SOLO perfil `cloud`. El {@link TenantContextFilter} sigue siendo quien exige un
 * JWT válido.
 *
 * <h3>Dos guardas: compatible y estricta</h3>
 *
 * <b>Compatible</b> ({@link #PATH_MODULE}): las rutas de F3. Si el token no trae
 * el claim `modules` (tokens de antes de F3), no se bloquea.
 *
 * <b>Estricta</b> ({@link #PATH_MODULE_ESTRICTO}): las rutas de dinero de la
 * vertical mayorista (plan de mayoristas, F0.3). Sin el claim, o con el claim
 * vacío, 403. Hasta F0.3 `/api/mayorista` no estaba en ninguna lista: cualquier
 * negocio autenticado usaba las listas de precio y la cartera sin tener el
 * módulo. La compatibilidad no hace falta aquí: todo token emitido desde F3
 * lleva `modules` ({@code AuthService.issueToken}) y dura 12 h
 * ({@code auth.token.ttl-seconds}), así que un día después del despliegue no
 * queda ninguno vivo sin el claim. Y confundir «sin claim» con «lista vacía»
 * dejaba abierta la puerta justo al negocio al que se le quitaron todos los
 * módulos.
 */
@Component
@Profile("cloud")
public class ModuleAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ModuleAccessFilter.class);

    /** Código estable del rechazo; lo leen el POS y el panel en {@code codigo}. */
    static final String MODULO_NO_INCLUIDO = "MODULO_NO_INCLUIDO";

    /** Prefijo de path → módulo requerido, con compatibilidad para tokens sin claim. */
    private static final Map<String, String> PATH_MODULE = Map.of(
            "/api/discounts", PlanCatalog.DESCUENTOS,
            "/api/kitchen", PlanCatalog.COCINA,
            "/api/waiter", PlanCatalog.MESEROS);

    /**
     * Prefijo de path → módulo requerido, sin compatibilidad. Los pedidos son
     * del mayorista: su bandeja es la del proveedor (plan de mayoristas, D1).
     */
    private static final Map<String, String> PATH_MODULE_ESTRICTO = Map.of(
            "/api/mayorista", PlanCatalog.MAYORISTA,
            "/api/cartera", PlanCatalog.CARTERA,
            "/api/pedidos", PlanCatalog.MAYORISTA,
            "/api/ruta", PlanCatalog.RUTA);

    private final JwtTenantResolver resolver;

    public ModuleAccessFilter(JwtTenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(req)) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI();
        String cabecera = req.getHeader("Authorization");

        String estricto = requiredModule(PATH_MODULE_ESTRICTO, path, true);
        if (estricto != null) {
            Optional<List<String>> claim = resolver.resolveModulesClaim(cabecera);
            if (claim.isEmpty() || !claim.get().contains(estricto)) {
                log.info("403 {} en {} {}: {}", MODULO_NO_INCLUIDO, req.getMethod(), path,
                        claim.isEmpty() ? "token sin claim modules" : "sin el modulo " + estricto);
                rechazar(res, estricto);
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        String required = requiredModule(PATH_MODULE, path, false);
        if (required != null) {
            List<String> modules = resolver.resolveModules(cabecera);
            // Vacío = token sin claim (viejo) → no bloquear (backward-compat).
            if (!modules.isEmpty() && !modules.contains(required)) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Tu plan no incluye el módulo: " + required);
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Mismo cuerpo que {@code GlobalExceptionHandler}: error, codigo, message y mensaje. */
    private static void rechazar(HttpServletResponse res, String modulo) throws IOException {
        String texto = "Tu plan no incluye el módulo: " + modulo;
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"error\":\"" + MODULO_NO_INCLUIDO + "\",\"codigo\":\"" + MODULO_NO_INCLUIDO
                + "\",\"message\":\"" + texto + "\",\"mensaje\":\"" + texto
                + "\",\"modulo\":\"" + modulo + "\"}");
    }

    /**
     * {@code segmento}: la estricta compara por segmento completo
     * ({@code /api/rutas-x} no es {@code /api/ruta}). La compatible conserva el
     * prefijo a secas, porque {@code /api/waiter-sales} vive hoy detrás de
     * {@code meseros} por esa coincidencia y cambiarlo la dejaría abierta.
     */
    private static String requiredModule(Map<String, String> tabla, String path, boolean segmento) {
        if (path == null) {
            return null;
        }
        for (Map.Entry<String, String> e : tabla.entrySet()) {
            String prefijo = e.getKey();
            boolean coincide = segmento
                    ? path.equals(prefijo) || path.startsWith(prefijo + "/")
                    : path.startsWith(prefijo);
            if (coincide) {
                return e.getValue();
            }
        }
        return null;
    }
}
