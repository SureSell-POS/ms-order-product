package com.suresell.orders.infrastructure.web;

import com.suresell.orders.mayorista.ListasDePrecio;
import com.suresell.orders.mayorista.ListasDePrecio.Autor;
import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.multitenant.TenantContext;
import com.suresell.orders.multitenant.UsuarioDeLaPeticion;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Mayorista (ola 2): listas de precio por cliente y clientes con su cartera.
 *
 * <p>Todo pasa por el filtro de tenant y por RLS. El precio de una venta con
 * cliente lo resuelve la base ({@code fn_precio_para}); aquí se administran
 * las listas y se puede preguntar «¿cuánto le cobraría hoy a X por Y?».
 *
 * <p>Plan de mayoristas, F0.3/F0.5: la ruta exige el módulo {@code mayorista}
 * ({@code ModuleAccessFilter}), y el autor sale del token. La cabecera
 * {@code X-User-Name} se sigue aceptando porque el panel la manda, pero no se
 * lee: un precio es un dato de dinero y su autor no lo escribe el cliente HTTP.
 */
@RestController
@RequestMapping("/api/mayorista")
@Tag(name = "Mayorista", description = "Listas de precio por cliente, clientes y su cartera")
public class MayoristaController {

    private final ListasDePrecio listas;
    private final JwtTenantResolver tokens;
    private final UsuarioDeLaPeticion usuarios;

    public MayoristaController(ListasDePrecio listas, JwtTenantResolver tokens, UsuarioDeLaPeticion usuarios) {
        this.listas = listas;
        this.tokens = tokens;
        this.usuarios = usuarios;
    }

    private Autor autor(HttpServletRequest http) {
        return new Autor(tokens.resolveSubject(http.getHeader("Authorization")).orElse(null),
                usuarios.id().orElse(null));
    }

    @GetMapping("/listas")
    @Operation(summary = "Las listas de precio del negocio")
    public List<Map<String, Object>> listas() {
        return listas.listas(TenantContext.get());
    }

    public record NuevaLista(@NotBlank String codigo, @NotBlank String nombre) {}

    @PostMapping("/listas")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearLista(@RequestBody NuevaLista cuerpo, HttpServletRequest http) {
        return Map.of("id", listas.crearLista(TenantContext.get(), cuerpo.codigo(), cuerpo.nombre(), autor(http)));
    }

    @GetMapping("/listas/{id}/lineas")
    @Operation(summary = "Las líneas vigentes de una lista")
    public List<Map<String, Object>> lineas(@PathVariable UUID id) {
        return listas.lineas(TenantContext.get(), id);
    }

    public record NuevoPrecio(@NotBlank String productoId, Integer cantidadMinima,
                              @NotNull BigDecimal precio, String fuente, Integer confianza, String nota) {}

    @PostMapping("/listas/{id}/lineas")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Fija un precio: cierra la línea vigente y abre otra. Nunca edita")
    public Map<String, Object> fijarPrecio(@PathVariable UUID id, @RequestBody NuevoPrecio cuerpo,
                                           HttpServletRequest http) {
        return Map.of("id", listas.fijarPrecio(TenantContext.get(), id, cuerpo.productoId(),
                cuerpo.cantidadMinima() == null ? 1 : cuerpo.cantidadMinima(), cuerpo.precio(),
                cuerpo.fuente(), cuerpo.confianza() == null ? 1 : cuerpo.confianza(), autor(http), cuerpo.nota()));
    }

    @GetMapping("/clientes")
    @Operation(summary = "Los clientes con su lista y su cartera")
    public List<Map<String, Object>> clientes() {
        return listas.clientes(TenantContext.get());
    }

    public record NuevoCliente(@NotBlank String documento, @NotBlank String nombre, String telefono,
                               UUID listaPrecioId, Integer plazoDias) {}

    @PostMapping("/clientes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> guardarCliente(@RequestBody NuevoCliente cuerpo, HttpServletRequest http) {
        return Map.of("id", listas.guardarCliente(TenantContext.get(), cuerpo.documento(), cuerpo.nombre(),
                cuerpo.telefono(), cuerpo.listaPrecioId(), cuerpo.plazoDias(), autor(http)));
    }

    @GetMapping("/precio")
    @Operation(summary = "Cuánto se le cobraría hoy a un cliente por un producto y una cantidad")
    public List<Map<String, Object>> precio(@RequestParam String documento, @RequestParam String productoId,
                                            @RequestParam(defaultValue = "1") int cantidad) {
        return listas.precioPara(documento, productoId, cantidad);
    }
}
