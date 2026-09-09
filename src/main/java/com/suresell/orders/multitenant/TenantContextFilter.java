package com.suresell.orders.multitenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Fija el tenant del request (desde el JWT) en TenantContext y lo limpia al final.
 *
 * SOLO activo en el perfil `cloud` (multi-tenant sobre Postgres) — el arranque
 * local-first por defecto NO lo carga, así que es aditivo y no rompe el flujo actual.
 *
 * Pendiente (siguiente paso): a partir de TenantContext, fijar `app.tenant_id` en
 * la conexión Postgres por transacción para que RLS aplique. Ver docs/40-multitenant.md.
 */
@Component
@Profile("cloud")
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);


    private final JwtTenantResolver resolver;

    public TenantContextFilter(JwtTenantResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * NO se filtra: los preflight CORS (OPTIONS, que el navegador manda sin
     * Authorization antes de cada request con headers custom — si se rechazaran
     * con 401, TODAS las llamadas del browser fallarían con ERR_FAILED), la
     * emisión de token (chicken-and-egg) y la documentación. Todo lo demás exige
     * un JWT de tenant válido.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (CorsUtils.isPreFlightRequest(req)) {
            return true;
        }
        String path = req.getRequestURI();
        if (path == null) {
            return false;
        }
        return path.startsWith("/auth/")
                || path.startsWith("/admin/") // super-admin (KAM): no scopeado por tenant, valida su propio JWT
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> tenant = resolver.resolveTenant(req.getHeader("Authorization"));
        if (tenant.isEmpty()) {
            // Al cliente, ambiguo a propósito. Al log, el motivo: si no vino
            // cabecera lo dice aquí; si vino y no vale, JwtTenantResolver ya
            // dejó el porqué en la línea anterior.
            String cabecera = req.getHeader("Authorization");
            log.warn("401 en {} {}: {}", req.getMethod(), req.getRequestURI(),
                    cabecera == null || cabecera.isBlank() ? "sin cabecera Authorization"
                            : "token rechazado (ver la linea anterior)");
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token de tenant ausente o inválido");
            return;
        }
        try {
            TenantContext.set(tenant.get());
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
