package com.suresell.orders.infrastructure.web;

import com.suresell.orders.cartera.Cartera;
import com.suresell.orders.cartera.Cartera.Quien;
import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.multitenant.TenantContext;
import com.suresell.orders.multitenant.UsuarioDeLaPeticion;
import com.suresell.orders.shared.exception.SoloAdministradorException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cuentas por cobrar (plan de mayoristas F4.4, contrato §7.4). La ruta exige el
 * módulo {@code cartera} ({@code ModuleAccessFilter}). Las respuestas van en
 * camelCase. Un vendedor ve y cobra solo a sus clientes; el filtro lo pone el
 * servidor con el id del token.
 */
@RestController
@RequestMapping("/api/cartera")
@Tag(name = "Cartera", description = "Cuentas por cobrar por documento: recibos, estado de cuenta, cupo")
public class CarteraController {

    static final Set<String> ROLES_QUE_COBRAN = Set.of("admin", "cajero", "vendedor");

    private final Cartera cartera;
    private final JwtTenantResolver tokens;
    private final UsuarioDeLaPeticion usuarios;

    public CarteraController(Cartera cartera, JwtTenantResolver tokens, UsuarioDeLaPeticion usuarios) {
        this.cartera = cartera;
        this.tokens = tokens;
        this.usuarios = usuarios;
    }

    @GetMapping("/clientes")
    @Operation(summary = "F4.4 — Resumen por cliente: saldo, vencido, factura más vieja, cupo, excede")
    public List<Map<String, Object>> clientes(@RequestParam(required = false) String edad,
                                              @RequestParam(required = false) Long vendedorId,
                                              @RequestParam(required = false) String q,
                                              HttpServletRequest http) {
        return cartera.clientes(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), edad, vendedorId, q);
    }

    @GetMapping("/clientes/{documento}/estado-de-cuenta")
    @Operation(summary = "F4.4 — Facturas con saldo, recibos del periodo, edades y la frase para WhatsApp")
    public Map<String, Object> estadoDeCuenta(@PathVariable String documento,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                              HttpServletRequest http) {
        return cartera.estadoDeCuenta(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), documento, desde, hasta);
    }

    @GetMapping("/clientes/{documento}/comportamiento-de-pago")
    @Operation(summary = "F10.3 — Cómo paga el cliente: días promedio de pago por factura, % a tiempo, atraso; solo lectura")
    public Map<String, Object> comportamientoDePago(@PathVariable String documento,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                    HttpServletRequest http) {
        return cartera.comportamientoDePago(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), documento, desde, hasta);
    }

    @PostMapping("/recibos")
    @Operation(summary = "F4.4 — Registrar un abono: recibo con número, aplicado a la más antigua si no se eligen facturas. "
            + "201 nuevo; 200 si es el reintento de la misma clave")
    public ResponseEntity<Map<String, Object>> registrarRecibo(@RequestBody Cartera.NuevoRecibo cuerpo,
                                                               HttpServletRequest http) {
        Cartera.Registro registro = cartera.registrarRecibo(quien(http, ROLES_QUE_COBRAN, "registrar un abono"), cuerpo);
        return ResponseEntity.status(registro.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(registro.recibo());
    }

    public record Anulacion(String motivo) {}

    @PostMapping("/recibos/{id}/anular")
    @Operation(summary = "F4.4 — Anular un recibo (solo admin): otro recibo con el motivo; la deuda vuelve")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> anular(@PathVariable UUID id, @RequestBody Anulacion cuerpo, HttpServletRequest http) {
        return cartera.anular(quien(http, Set.of("admin"), "anular un recibo"), id, cuerpo == null ? null : cuerpo.motivo());
    }

    public record CambioDeCupo(BigDecimal cupo) {}

    @PutMapping("/clientes/{documento}/cupo")
    @Operation(summary = "F4.4 — Cambiar el cupo de crédito (solo admin); queda en la historia del cliente")
    public Map<String, Object> cambiarCupo(@PathVariable String documento, @RequestBody CambioDeCupo cuerpo,
                                           HttpServletRequest http) {
        return cartera.cambiarCupo(quien(http, Set.of("admin"), "cambiar el cupo de un cliente"), documento,
                cuerpo == null ? null : cuerpo.cupo());
    }

    public record Insolvencia(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde) {}

    @PostMapping("/clientes/{documento}/insolvencia")
    @Operation(summary = "F4.4 — Marcar (o con desde=null, levantar) la insolvencia de un cliente (solo admin)")
    public Map<String, Object> insolvencia(@PathVariable String documento, @RequestBody Insolvencia cuerpo,
                                           HttpServletRequest http) {
        return cartera.marcarInsolvencia(quien(http, Set.of("admin"), "marcar la insolvencia de un cliente"), documento,
                cuerpo == null ? null : cuerpo.desde());
    }

    @GetMapping("/resumen")
    @Operation(summary = "F4.4 — Por cobrar, vencido por edad y los que más deben (solo admin)")
    public Map<String, Object> resumen(HttpServletRequest http) {
        return cartera.resumenDelNegocio(quien(http, Set.of("admin"), "ver el resumen de cartera"));
    }

    private Quien quien(HttpServletRequest http, Set<String> roles, String queCosa) {
        String rol = tokens.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!roles.contains(rol)) {
            throw new SoloAdministradorException(queCosa);
        }
        return new Quien(TenantContext.get(), rol, usuarios.id().orElse(null));
    }
}
