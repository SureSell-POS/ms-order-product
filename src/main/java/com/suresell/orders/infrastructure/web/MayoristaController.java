package com.suresell.orders.infrastructure.web;

import com.suresell.orders.mayorista.ListasDePrecio;
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
 */
@RestController
@RequestMapping("/api/mayorista")
@Tag(name = "Mayorista", description = "Listas de precio por cliente, clientes y su cartera")
public class MayoristaController {

    private final ListasDePrecio listas;

    public MayoristaController(ListasDePrecio listas) {
        this.listas = listas;
    }

    @GetMapping("/listas")
    @Operation(summary = "Las listas de precio del negocio")
    public List<Map<String, Object>> listas() {
        return listas.listas();
    }

    public record NuevaLista(@NotBlank String codigo, @NotBlank String nombre) {}

    @PostMapping("/listas")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearLista(@RequestBody NuevaLista cuerpo,
                                          @RequestHeader("X-User-Name") String usuario) {
        return Map.of("id", listas.crearLista(cuerpo.codigo(), cuerpo.nombre(), usuario));
    }

    @GetMapping("/listas/{id}/lineas")
    @Operation(summary = "Las líneas vigentes de una lista")
    public List<Map<String, Object>> lineas(@PathVariable UUID id) {
        return listas.lineas(id);
    }

    public record NuevoPrecio(@NotBlank String productoId, Integer cantidadMinima,
                              @NotNull BigDecimal precio, String fuente, Integer confianza, String nota) {}

    @PostMapping("/listas/{id}/lineas")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Fija un precio: cierra la línea vigente y abre otra. Nunca edita")
    public Map<String, Object> fijarPrecio(@PathVariable UUID id, @RequestBody NuevoPrecio cuerpo,
                                           @RequestHeader("X-User-Name") String usuario) {
        return Map.of("id", listas.fijarPrecio(id, cuerpo.productoId(),
                cuerpo.cantidadMinima() == null ? 1 : cuerpo.cantidadMinima(), cuerpo.precio(),
                cuerpo.fuente(), cuerpo.confianza() == null ? 1 : cuerpo.confianza(), usuario, cuerpo.nota()));
    }

    @GetMapping("/clientes")
    @Operation(summary = "Los clientes con su lista y su cartera")
    public List<Map<String, Object>> clientes() {
        return listas.clientes();
    }

    public record NuevoCliente(@NotBlank String documento, @NotBlank String nombre, String telefono,
                               UUID listaPrecioId, Integer plazoDias) {}

    @PostMapping("/clientes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> guardarCliente(@RequestBody NuevoCliente cuerpo,
                                              @RequestHeader("X-User-Name") String usuario) {
        return Map.of("id", listas.guardarCliente(cuerpo.documento(), cuerpo.nombre(), cuerpo.telefono(),
                cuerpo.listaPrecioId(), cuerpo.plazoDias(), usuario));
    }

    @GetMapping("/precio")
    @Operation(summary = "Cuánto se le cobraría hoy a un cliente por un producto y una cantidad")
    public List<Map<String, Object>> precio(@RequestParam String documento, @RequestParam String productoId,
                                            @RequestParam(defaultValue = "1") int cantidad) {
        return listas.precioPara(documento, productoId, cantidad);
    }
}
