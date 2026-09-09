package com.suresell.orders.infrastructure.web;

import com.suresell.orders.application.usecase.SiteService;
import com.suresell.orders.domain.model.Site;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 */
@RestController
@RequestMapping("/account/sites")
@RequiredArgsConstructor
@Tag(name = "Sedes", description = "Sedes y flujo de venta (catálogo flujos_de_venta; posMode legado)")
public class SiteController {

    private final SiteService service;

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
        return ResponseEntity.ok(r);
    }
}
