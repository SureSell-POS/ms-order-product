package com.suresell.orders.infrastructure.web;

import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.multitenant.TenantContext;
import com.suresell.orders.multitenant.UsuarioDeLaPeticion;
import com.suresell.orders.pedidos.Pedidos;
import com.suresell.orders.pedidos.Pedidos.Accion;
import com.suresell.orders.pedidos.Pedidos.Quien;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * El pedido del lado del proveedor (plan de mayoristas F5.3, contrato §7.5). La ruta
 * exige el módulo {@code mayorista} ({@code ModuleAccessFilter}); los roles los decide
 * {@link Pedidos}. Las respuestas van en camelCase. Despachar y entregar llegan con
 * F5.5 y F5.7.
 */
@RestController
@RequestMapping("/api/pedidos")
@Tag(name = "Pedidos", description = "Pedidos del mayorista: tomar, bandeja, detalle con línea de tiempo, confirmar y ajustar")
public class PedidosController {

    private final Pedidos pedidos;
    private final JwtTenantResolver tokens;
    private final UsuarioDeLaPeticion usuarios;

    public PedidosController(Pedidos pedidos, JwtTenantResolver tokens, UsuarioDeLaPeticion usuarios) {
        this.pedidos = pedidos;
        this.tokens = tokens;
        this.usuarios = usuarios;
    }

    @PostMapping
    @Operation(summary = "F5.3 — Tomar un pedido. Nace ENVIADO; si lo toma un admin o cajero, CONFIRMADO. "
            + "201 nuevo; 200 si es el reintento de la misma clave")
    public ResponseEntity<Map<String, Object>> crear(@RequestBody Pedidos.PedidoNuevo cuerpo, HttpServletRequest http) {
        Pedidos.Resultado r = pedidos.crear(quien(http), cuerpo);
        return ResponseEntity.status(r.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(r.pedido());
    }

    @GetMapping
    @Operation(summary = "F5.3 — Bandeja «Pedidos recibidos»: todos los orígenes, lo más reciente primero; un vendedor ve los suyos")
    public Map<String, Object> bandeja(@RequestParam(required = false) String estado,
                                       @RequestParam(required = false) Long vendedorId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
                                       @RequestParam(required = false) String clienteDocumento,
                                       @RequestParam(required = false) Integer limite,
                                       @RequestParam(required = false) Long antesDe,
                                       HttpServletRequest http) {
        return pedidos.bandeja(quien(http), estado, vendedorId, fecha, clienteDocumento, limite, antesDe);
    }

    @GetMapping("/{id}")
    @Operation(summary = "F5.3 — Detalle: líneas con cantidades por etapa y precios, y la línea de tiempo de eventos")
    public Map<String, Object> detalle(@PathVariable UUID id, HttpServletRequest http) {
        return pedidos.detalle(quien(http), id);
    }

    @PostMapping("/{id}/confirmar")
    @Operation(summary = "F5.3 — Confirmar (admin, cajero). Con cantidades distintas, AJUSTADO y luego CONFIRMADO. Congela el precio de la captura")
    public Map<String, Object> confirmar(@PathVariable UUID id, @RequestBody(required = false) Accion cuerpo, HttpServletRequest http) {
        return pedidos.confirmar(quien(http), id, cuerpo);
    }

    @PostMapping("/{id}/ajustar")
    @Operation(summary = "F5.3 — Ajustar cantidades (admin, cajero) o, solo admin, el precio con ERROR_DE_PRECIO")
    public Map<String, Object> ajustar(@PathVariable UUID id, @RequestBody Accion cuerpo, HttpServletRequest http) {
        return pedidos.ajustar(quien(http), id, cuerpo);
    }

    @PostMapping("/{id}/rechazar")
    @Operation(summary = "F5.3 — Rechazar con motivo (admin)")
    public Map<String, Object> rechazar(@PathVariable UUID id, @RequestBody Accion cuerpo, HttpServletRequest http) {
        return pedidos.rechazar(quien(http), id, cuerpo);
    }

    @PostMapping("/{id}/cancelar")
    @Operation(summary = "F5.3 — Cancelar con motivo (admin). Lo ya despachado exige la reversa: 409 REVERSA_PENDIENTE")
    public Map<String, Object> cancelar(@PathVariable UUID id, @RequestBody Accion cuerpo, HttpServletRequest http) {
        return pedidos.cancelar(quien(http), id, cuerpo);
    }

    @PostMapping("/{id}/retener")
    @Operation(summary = "F5.3 — Retener con motivo de crédito (admin)")
    public Map<String, Object> retener(@PathVariable UUID id, @RequestBody Accion cuerpo, HttpServletRequest http) {
        return pedidos.retener(quien(http), id, cuerpo);
    }

    @PostMapping("/{id}/liberar")
    @Operation(summary = "F5.3 — Liberar un pedido retenido (admin), con el porqué en «nota»")
    public Map<String, Object> liberar(@PathVariable UUID id, @RequestBody Accion cuerpo, HttpServletRequest http) {
        return pedidos.liberar(quien(http), id, cuerpo);
    }

    private Quien quien(HttpServletRequest http) {
        String rol = tokens.resolveRole(http.getHeader("Authorization")).orElse("");
        return new Quien(TenantContext.get(), rol, usuarios.id().orElse(null));
    }
}
