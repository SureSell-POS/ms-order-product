package com.suresell.orders.multitenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Extrae el `tenant_id` de un JWT firmado (HS256). El secreto viene de
 * configuración (variable de entorno en producción). Ver docs/40-multitenant.md.
 */
@Component
public class JwtTenantResolver {

    private static final Logger log = LoggerFactory.getLogger(JwtTenantResolver.class);

    private static String primeraLinea(String m) {
        if (m == null) {
            return "";
        }
        int corte = m.indexOf('\n');
        return (corte < 0 ? m : m.substring(0, corte)).trim();
    }

    private final SecretKey key;

    public JwtTenantResolver(
            @Value("${security.jwt.secret:cambia-esta-clave-en-produccion-min-32-bytes!}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Devuelve el tenant_id si el token es válido y lo contiene; si no, vacío. */
    public Optional<String> resolveTenant(String authorizationHeader) {
        return parse(authorizationHeader).map(c -> c.get("tenant_id", String.class));
    }

    /** Devuelve el subject (email del usuario) si el token es válido; si no, vacío. */
    public Optional<String> resolveSubject(String authorizationHeader) {
        return parse(authorizationHeader).map(Claims::getSubject);
    }

    /** Rol del usuario (claim `role`: admin|cajero…) para gating por rol (F3). */
    public Optional<String> resolveRole(String authorizationHeader) {
        return parse(authorizationHeader).map(c -> c.get("role", String.class));
    }

    /** true si el token es de un super-admin (KAM) válido (claim `super_admin`). F3. */
    public boolean isSuperAdmin(String authorizationHeader) {
        return parse(authorizationHeader)
                .map(c -> Boolean.TRUE.equals(c.get("super_admin", Boolean.class)))
                .orElse(false);
    }

    /**
     * Email del super-admin del token, para dejar rastro de QUIÉN hizo un cambio
     * de alto impacto. Sale del JWT y no del cuerpo del request: así no se puede
     * falsear desde el cliente.
     */
    public String superAdminEmail(String authorizationHeader) {
        return parse(authorizationHeader)
                .map(c -> c.getSubject() != null ? c.getSubject() : c.get("email", String.class))
                .orElse("desconocido");
    }

    /**
     * Módulos del tenant (claim `modules`) para enforcement por módulo (F3). Vacío
     * si el token no lo trae (tokens viejos) — el {@link ModuleAccessFilter} lo trata
     * como "desconocido" y no bloquea, hasta que todos re-loguean.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> resolveModules(String authorizationHeader) {
        return parse(authorizationHeader)
                .map(c -> {
                    Object m = c.get("modules");
                    return m instanceof java.util.List ? (java.util.List<String>) m
                            : java.util.Collections.<String>emptyList();
                })
                .orElse(java.util.Collections.emptyList());
    }

    private Optional<Claims> parse(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            // El 401 que costó diez minutos: al usuario se le dice «sesión
            // inválida» (a propósito ambiguo: no se le cuenta a un atacante si
            // la firma o la fecha), pero al log SÍ se le cuenta por qué. Sin el
            // token, que es un secreto.
            log.warn("Token rechazado ({}): {}", e.getClass().getSimpleName(),
                    primeraLinea(e.getMessage()));
            return Optional.empty();
        }
    }
}
