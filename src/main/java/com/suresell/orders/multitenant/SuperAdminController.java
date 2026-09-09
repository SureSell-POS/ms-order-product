package com.suresell.orders.multitenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API del panel de super-admin (KAM) GLOBAL. Bajo `/admin/**`, exento del filtro de
 * tenant (no está scopeado por tenant): `/admin/login` es público (emite el JWT de
 * super-admin) y el resto exige un JWT de super-admin válido. F3, Inc.3, docs/160.
 */
@RestController
@Profile("cloud")
public class SuperAdminController {

    private final SuperAdminService svc;
    private final JwtTenantResolver resolver;
    private final AltaDeNegocioService alta;

    public SuperAdminController(SuperAdminService svc, JwtTenantResolver resolver,
                                AltaDeNegocioService alta) {
        this.svc = svc;
        this.resolver = resolver;
        this.alta = alta;
    }

    /**
     * Alta de un negocio completa: negocio, usuario administrador, sede con su
     * modo, mesas y plan. Todo en una transacción.
     *
     * <p>Antes eran cinco pasos repartidos en dos sesiones distintas (el token
     * del KAM y el del administrador del negocio recién creado). Era el paso más
     * propenso a error de toda la operación.
     */
    @PostMapping("/admin/tenants")
    public ResponseEntity<?> darDeAltaNegocio(@RequestBody AltaDeNegocioService.Solicitud req,
                                              HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            // Quién da de alta sale del JWT del KAM: queda como responsable en
            // el libro del perfil (V50: `asignado_por_suresell`).
            String quien = resolver.superAdminEmail(http.getHeader("Authorization"));
            return ResponseEntity.ok(alta.darDeAlta(req, quien));
        } catch (AltaDeNegocioService.AltaInvalidaException e) {
            return ResponseEntity.status(e.codigo()).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(svc.login(req.email(), req.password()));
        } catch (AuthException e) {
            return err(e);
        }
    }

    @GetMapping("/admin/tenants")
    public ResponseEntity<?> listTenants(HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        return guard != null ? guard : ResponseEntity.ok(svc.listTenants());
    }

    @GetMapping("/admin/tenants/{id}")
    public ResponseEntity<?> getTenant(@PathVariable String id, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.getTenant(id));
        } catch (AuthException e) {
            return err(e);
        }
    }

    /**
     * Ola 4: la cuenta del negocio como la ve el KAM (correo principal,
     * administradores con estado, estado de la cuenta). Sin hashes ni tokens.
     */
    @GetMapping("/admin/tenants/{id}/cuenta")
    public ResponseEntity<?> cuenta(@PathVariable String id, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.cuenta(id));
        } catch (AuthException e) {
            return err(e);
        }
    }

    /**
     * Ola 4: el KAM restablece la clave de un administrador con el flujo de
     * reset que ya existe (token de un solo uso, con vencimiento, por correo).
     * No hay clave temporal. Deja huella en el log con quién lo pidió.
     */
    @PostMapping("/admin/tenants/{id}/administradores/{email}/restablecer-clave")
    public ResponseEntity<?> restablecerClave(@PathVariable String id, @PathVariable String email,
                                              HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            String quien = resolver.superAdminEmail(http.getHeader("Authorization"));
            return ResponseEntity.ok(svc.restablecerClave(id, email, quien));
        } catch (AuthException e) {
            return err(e);
        }
    }

    @PutMapping("/admin/tenants/{id}/plan")
    public ResponseEntity<?> setPlan(@PathVariable String id, @RequestBody PlanRequest req,
                                     HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            svc.setPlan(id, req.plan());
            return ResponseEntity.ok(svc.getTenant(id));
        } catch (AuthException e) {
            return err(e);
        }
    }

    @PutMapping("/admin/tenants/{id}/modules")
    public ResponseEntity<?> setModules(@PathVariable String id, @RequestBody ModulesRequest req,
                                        HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.setModules(id, req.overrides()));
        } catch (AuthException e) {
            return err(e);
        }
    }

    /** Sedes de un negocio y su modo de POS (Inc. 1 del modo Restaurante). */
    @GetMapping("/admin/tenants/{id}/sites")
    public ResponseEntity<?> getSites(@PathVariable String id, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.getSites(id));
        } catch (AuthException e) {
            return err(e);
        }
    }

    /** Cambiar el modo de una sede. SOLO el KAM: el modo se vende, no se elige. */
    @PutMapping("/admin/tenants/{id}/sites/{siteId}/mode")
    public ResponseEntity<?> setSiteMode(@PathVariable String id, @PathVariable Long siteId,
                                         @RequestBody SiteModeRequest req, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            // Quién hace el cambio sale del JWT de super-admin, no del cuerpo:
            // así no se puede falsear desde el cliente.
            String quien = resolver.superAdminEmail(http.getHeader("Authorization"));
            return ResponseEntity.ok(svc.setSiteMode(id, siteId, req.pedido(), quien));
        } catch (AuthException e) {
            return err(e);
        }
    }

    /**
     * V48: el KAM nuevo manda {@code flujoDeVenta}; el viejo, {@code posMode}.
     * Los dos se resuelven contra el catálogo.
     */
    public record SiteModeRequest(String posMode, String flujoDeVenta) {
        String pedido() {
            return flujoDeVenta != null && !flujoDeVenta.isBlank() ? flujoDeVenta : posMode;
        }
    }

    // ------------------------------------------------------------------
    // V48 (ola 3) — Perfil vertical, catálogos y cuentas de KAM.
    // ------------------------------------------------------------------

    /** Los perfiles verticales con los flujos que admite cada uno. Para el alta. */
    @GetMapping("/admin/perfiles")
    public ResponseEntity<?> perfiles(HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        return guard != null ? guard : ResponseEntity.ok(svc.perfiles());
    }

    /** El catálogo de flujos de venta. */
    @GetMapping("/admin/flujos")
    public ResponseEntity<?> flujos(HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        return guard != null ? guard : ResponseEntity.ok(svc.flujos());
    }

    /** El perfil vigente de un negocio; 200 con `asignado:false` si no tiene. */
    @GetMapping("/admin/tenants/{id}/perfil")
    public ResponseEntity<?> perfil(@PathVariable String id, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        return ResponseEntity.ok(svc.perfilVigente(id)
                .<Map<String, Object>>map(v -> Map.of("asignado", true, "codigo", v.codigo(),
                        "nombre", v.nombre(), "fuente", v.fuente(), "confianza", v.confianza(),
                        "asignadoPor", v.asignadoPor(), "asignadoEn", String.valueOf(v.asignadoEn())))
                .orElse(Map.of("asignado", false)));
    }

    public record PerfilRequest(String codigo) {
    }

    /** Cambiar el perfil. SOLO el KAM. No reconvierte el catálogo existente. */
    @PutMapping("/admin/tenants/{id}/perfil")
    public ResponseEntity<?> setPerfil(@PathVariable String id, @RequestBody PerfilRequest req,
                                       HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            String quien = resolver.superAdminEmail(http.getHeader("Authorization"));
            var v = svc.setPerfil(id, req.codigo(), quien);
            return ResponseEntity.ok(Map.of("asignado", true, "codigo", v.codigo(), "nombre", v.nombre(),
                    "fuente", v.fuente(), "confianza", v.confianza(), "asignadoPor", v.asignadoPor()));
        } catch (AuthException e) {
            return err(e);
        }
    }

    public record SuperAdminRequest(String email, String password) {
    }

    /** Otra cuenta de KAM. Exige ser KAM: la primera la crea el arranque por variable. */
    @PostMapping("/admin/super-admins")
    public ResponseEntity<?> crearSuperAdmin(@RequestBody SuperAdminRequest req, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            String quien = resolver.superAdminEmail(http.getHeader("Authorization"));
            return ResponseEntity.status(201).body(svc.createSuperAdmin(req.email(), req.password(), quien));
        } catch (AuthException e) {
            return err(e);
        }
    }

    // ------------------------------------------------------------------
    // N4 — Catálogo y planes (docs/investigacion/19).
    // ------------------------------------------------------------------

    /** Módulos conocidos + planes. El KAM los tenía QUEMADOS y mostraba 4 de 16. */
    @GetMapping("/admin/catalog")
    public ResponseEntity<?> catalog(HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        return ResponseEntity.ok(svc.catalog());
    }

    @PostMapping("/admin/plans")
    public ResponseEntity<?> createPlan(@RequestBody PlanUpsertRequest req, HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.createPlan(req.id(), req.name(), req.description(), req.modules()));
        } catch (AuthException e) {
            return err(e);
        }
    }

    @PutMapping("/admin/plans/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable String id, @RequestBody PlanUpsertRequest req,
                                        HttpServletRequest http) {
        ResponseEntity<?> guard = requireSuper(http);
        if (guard != null) {
            return guard;
        }
        try {
            return ResponseEntity.ok(svc.updatePlan(id, req.name(), req.description(),
                    req.active(), req.modules()));
        } catch (AuthException e) {
            return err(e);
        }
    }

    public record PlanUpsertRequest(String id, String name, String description, Boolean active,
                                    java.util.List<String> modules) {
    }

    private ResponseEntity<?> requireSuper(HttpServletRequest http) {
        if (!resolver.isSuperAdmin(http.getHeader("Authorization"))) {
            return ResponseEntity.status(403).body(Map.of("error", "Requiere super-admin"));
        }
        return null;
    }

    private ResponseEntity<?> err(AuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    public record LoginRequest(String email, String password) {}

    public record PlanRequest(String plan) {}

    public record ModulesRequest(Map<String, Boolean> overrides) {}
}
