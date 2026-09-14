package com.suresell.orders.multitenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Operaciones sobre la cuenta del usuario autenticado (perfil `cloud`). A
 * diferencia de {@link AuthController} (`/auth/**`, exento del filtro), esto vive
 * bajo `/account/**`, así que {@link TenantContextFilter} EXIGE un JWT válido: la
 * identidad (email + tenant) sale del token, no del body. Ver docs/110 §8.
 */
@RestController
@Profile("cloud")
public class AccountController {

    private final AuthService auth;
    private final JwtTenantResolver resolver;

    public AccountController(AuthService auth, JwtTenantResolver resolver) {
        this.auth = auth;
        this.resolver = resolver;
    }

    @PostMapping("/account/password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req,
                                            HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        // El filtro ya validó el token; el tenant vive en el contexto del request.
        String tenantId = TenantContext.get();
        String email = resolver.resolveSubject(header).orElse(null);
        if (email == null || email.isBlank() || tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Sesión inválida"));
        }
        try {
            auth.changePassword(email, tenantId, req.currentPassword(), req.newPassword());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }

    /** Datos del negocio del tenant autenticado (para imprimirlos en el ticket). */
    @GetMapping("/account/business")
    public ResponseEntity<?> getBusiness() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Sesión inválida"));
        }
        try {
            return ResponseEntity.ok(auth.getBusiness(tenantId));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/account/business")
    public ResponseEntity<?> updateBusiness(@RequestBody BusinessRequest req) {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Sesión inválida"));
        }
        try {
            return ResponseEntity.ok(auth.updateBusiness(tenantId, req.name(), req.nit(),
                    req.address(), req.phone(), req.ticketFooter(), req.editPassword()));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- Gestión de usuarios (F3) — solo admin ----------

    /**
     * Sin {@code rol}: la gestión de usuarios, solo admin. Con {@code rol}
     * (plan de mayoristas, F1.2): la lista activa de ese rol para el selector de
     * vendedor de la caja, que también abre un cajero. Nunca devuelve hashes.
     */
    @GetMapping("/account/users")
    public ResponseEntity<?> listUsers(@RequestParam(value = "rol", required = false) String rol,
                                       HttpServletRequest http) {
        String tenantId = TenantContext.get();
        if (rol != null && !rol.isBlank()) {
            ResponseEntity<?> guard = requireRole(http, tenantId, Set.of("admin", "cajero"));
            if (guard != null) {
                return guard;
            }
            return ResponseEntity.ok(auth.listUsers(tenantId, rol));
        }
        ResponseEntity<?> guard = requireAdmin(http, tenantId);
        if (guard != null) {
            return guard;
        }
        return ResponseEntity.ok(auth.listUsers(tenantId));
    }

    @PostMapping("/account/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req, HttpServletRequest http) {
        String tenantId = TenantContext.get();
        ResponseEntity<?> guard = requireAdmin(http, tenantId);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(auth.createUser(tenantId, req.email(), req.password(), req.role(), req.nombre()));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }

    // ---------- Módulos del tenant (F3, Inc.2) — solo admin ----------

    @GetMapping("/account/modules")
    public ResponseEntity<?> getModules(HttpServletRequest http) {
        String tenantId = TenantContext.get();
        ResponseEntity<?> guard = requireAdmin(http, tenantId);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(auth.getModuleConfig(tenantId));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cerrada (plan de mayoristas, F0.4). Hasta aquí el admin del negocio podía
     * regalarse cualquier módulo, incluido `mayorista` o `cartera`, que se
     * venden. Los módulos los decide el KAM con
     * {@code PUT /admin/tenants/{id}/modules}, que no pasa por aquí (un token
     * de KAM no lleva negocio y esta ruta exige uno). Ningún cliente del
     * monorepo la llamaba (medido el 2026-09-13), así que el 403 no rompe a
     * nadie. Se deja la ruta, en vez de quitarla, para que quien la llame lea
     * por qué y no un 404.
     */
    @PutMapping("/account/modules")
    public ResponseEntity<?> setModules(@RequestBody(required = false) ModulesRequest req, HttpServletRequest http) {
        String texto = "Los módulos del negocio los activa SureSell desde el KAM.";
        Map<String, String> cuerpo = new java.util.LinkedHashMap<>();
        cuerpo.put("error", "SOLO_KAM");
        cuerpo.put("codigo", "SOLO_KAM");
        cuerpo.put("message", texto);
        cuerpo.put("mensaje", texto);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(cuerpo);
    }

    /** F1.2: nombre, rol o activo de un usuario del negocio. Desactivar, nunca borrar. Solo admin. */
    @PutMapping("/account/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable("id") long id, @RequestBody UpdateUserRequest req,
                                        HttpServletRequest http) {
        String tenantId = TenantContext.get();
        ResponseEntity<?> guard = requireAdmin(http, tenantId);
        if (guard != null) {
            return guard;
        }
        String quien = resolver.resolveSubject(http.getHeader("Authorization")).orElse(null);
        try {
            return ResponseEntity.ok(auth.updateUser(tenantId, id, req.nombre(), req.rol(), req.activo(), quien));
        } catch (AuthException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage(), "mensaje", e.getMessage()));
        }
    }

    private ResponseEntity<?> requireRole(HttpServletRequest http, String tenantId, Set<String> roles) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Sesión inválida"));
        }
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!roles.contains(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Tu rol no puede ver esta lista"));
        }
        return null;
    }

    /** null si es admin válido; si no, la respuesta 401/403 a devolver. */
    private ResponseEntity<?> requireAdmin(HttpServletRequest http, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Sesión inválida"));
        }
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Solo un administrador puede gestionar usuarios"));
        }
        return null;
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record BusinessRequest(String name, String nit, String address, String phone,
                                  String ticketFooter, String editPassword) {}

    /** {@code nombre} (V60) es opcional: un cliente viejo no lo manda. */
    public record CreateUserRequest(String email, String password, String role, String nombre) {}

    /** Campos nulos = no cambian. {@code rol} ∈ admin|cajero|vendedor. */
    public record UpdateUserRequest(String nombre, String rol, Boolean activo) {}

    public record ModulesRequest(Map<String, Boolean> overrides) {}
}
