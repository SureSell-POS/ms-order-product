package com.suresell.orders.infrastructure.web;

import com.suresell.orders.application.usecase.SiteService;
import com.suresell.orders.domain.model.Site;
import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.shared.exception.SoloAdministradorException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Sedes y modo de POS (Inc. 1 del modo Restaurante).
 *
 * El POS consulta su modo al arrancar para saber qué flujo dibujar. Cambiarlo
 * NO está aquí: es potestad del KAM y vive en /admin (ver SuperAdminController).
 *
 * <p>V55: la configuración de caja (base, PIN de registro, tope diario) sí la
 * cambia el negocio, pero solo un administrador: el claim {@code role} del
 * JWT, igual que {@code PagerConfigController} y {@code OrderDeletionController}.
 */
@RestController
@RequestMapping("/account/sites")
@RequiredArgsConstructor
@Tag(name = "Sedes", description = "Sedes y flujo de venta (catálogo flujos_de_venta; posMode legado)")
public class SiteController {

    private final SiteService service;
    private final JwtTenantResolver resolver;

    @GetMapping
    @Operation(summary = "Sedes del negocio")
    public ResponseEntity<List<Site>> listar() {
        return ResponseEntity.ok(service.listar());
    }

    /**
     * V48 — {@code posMode} y {@code restaurante} se conservan con sus valores
     * de siempre para los POS sin actualizar. Un cliente nuevo lee
     * {@code flujoDeVenta}, {@code usaMesas} y {@code usaRastreador}.
     */
    @GetMapping("/mode")
    @Operation(summary = "Flujo de venta efectivo del negocio (y su posMode legado)")
    public ResponseEntity<Map<String, Object>> modo() {
        var flujo = service.flujoEfectivo();
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("posMode", flujo.posModeLegado());
        r.put("restaurante", flujo.usaMesas());
        r.put("flujoDeVenta", flujo.codigo());
        r.put("usaMesas", flujo.usaMesas());
        r.put("usaRastreador", flujo.usaRastreador());
        // V52 — un POS viejo lo ignora; uno nuevo deja de sondear al agente si es false.
        r.put("imprimeTirilla", service.imprimeTirillaEfectiva());
        // V55 — la base configurada (null si no la hay) y si la caja puede
        // registrar productos. El PIN mismo nunca viaja.
        r.put("baseCaja", service.baseCajaConfigurada().orElse(null));
        r.put("tienePinRegistro", service.tienePinRegistro());
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------------
    // V55 — Configuración de caja. Solo administrador.
    // ------------------------------------------------------------------

    /** Cuerpo de {@code PUT /account/sites/caja}. Todo opcional; {@code pin: ""} quita el PIN. */
    public record CajaRequest(BigDecimal baseCaja, String pin, Integer maxRegistrosCajaPorDia) {
    }

    @GetMapping("/caja")
    @Operation(summary = "Configuración de caja del negocio (solo administrador)")
    public ResponseEntity<SiteService.ConfiguracionDeCaja> caja(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        exigirAdministrador(authorization, "ver la configuración de caja");
        return ResponseEntity.ok(service.configuracionDeCaja());
    }

    @PutMapping("/caja")
    @Operation(summary = "Cambiar la base de caja, el PIN de registro o el tope diario (solo administrador)",
            description = "`pin` de 4 a 12 caracteres, se guarda con hash; `pin: \"\"` lo quita. 400 con `campo`.")
    public ResponseEntity<SiteService.ConfiguracionDeCaja> configurarCaja(
            @RequestBody CajaRequest cuerpo,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        exigirAdministrador(authorization, "configurar la caja");
        return ResponseEntity.ok(service.configurarCaja(
                new SiteService.CambioDeCaja(cuerpo.baseCaja(), cuerpo.pin(), cuerpo.maxRegistrosCajaPorDia())));
    }

    private void exigirAdministrador(String authorization, String queCosa) {
        if (!resolver.resolveRole(authorization).map("admin"::equalsIgnoreCase).orElse(false)) {
            throw new SoloAdministradorException(queCosa);
        }
    }
}
