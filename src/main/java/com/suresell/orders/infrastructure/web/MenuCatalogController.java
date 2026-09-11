package com.suresell.orders.infrastructure.web;
import com.suresell.orders.application.dto.MenuCategoryResponse;
import com.suresell.orders.application.dto.MenuProductResponse;
import com.suresell.orders.domain.port.in.MenuCatalogPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/menu")
@Tag(name = "Menu Catalog", description = "Catálogo local de categorías y productos")
public class MenuCatalogController {
    private final MenuCatalogPort menuCatalogPort;
    private final com.suresell.orders.application.usecase.CodigosDeProducto codigosDeProducto;
    private final com.suresell.orders.multitenant.JwtTenantResolver jwt;
    private final com.suresell.orders.application.usecase.VentasSinRegistrar ventasSinRegistrar;
    private final com.suresell.orders.application.usecase.RegistroRapidoEnCaja registroRapido;

    public MenuCatalogController(MenuCatalogPort menuCatalogPort,
                                 com.suresell.orders.application.usecase.CodigosDeProducto codigosDeProducto,
                                 com.suresell.orders.multitenant.JwtTenantResolver jwt,
                                 com.suresell.orders.application.usecase.VentasSinRegistrar ventasSinRegistrar,
                                 com.suresell.orders.application.usecase.RegistroRapidoEnCaja registroRapido) {
        this.menuCatalogPort = menuCatalogPort;
        this.codigosDeProducto = codigosDeProducto;
        this.jwt = jwt;
        this.ventasSinRegistrar = ventasSinRegistrar;
        this.registroRapido = registroRapido;
    }

    /**
     * V55 — Registrar un producto desde la caja con el código que acaba de
     * leer el lector. Devuelve el MISMO producto que el catálogo
     * ({@code categories-with-products}) para que el POS lo meta al índice
     * sin traducir. Códigos: 201; 409 YA_EXISTE (con productoId); 403
     * PIN_INCORRECTO o SIN_PIN; 409 LIMITE_DIARIO; 400 con campo.
     */
    @PostMapping("/products/registro-rapido")
    @Operation(summary = "Registrar un producto desde la caja (nombre, precio, código, PIN)",
            description = "201 con el producto del catálogo; 409 YA_EXISTE + productoId; 403 PIN_INCORRECTO | SIN_PIN; 409 LIMITE_DIARIO; 400 con campo.")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public MenuProductResponse registroRapido(
            @org.springframework.web.bind.annotation.RequestBody
            com.suresell.orders.application.dto.RegistroRapidoRequest cuerpo,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return registroRapido.registrar(cuerpo, usuario(authorization));
    }

    /**
     * V55 — Los productos que nacieron en la caja (registro rápido), para la
     * pastilla «Registrado en la caja · falta enlazar» del panel. Solo
     * lectura, lo más reciente primero, máximo 200.
     */
    @GetMapping("/products/registrados-en-caja")
    @Operation(summary = "Productos registrados desde la caja (creado_en_caja_en no nulo), lo más reciente primero, máx. 200")
    public List<com.suresell.orders.application.usecase.RegistroRapidoEnCaja.RegistradoEnCaja> registradosEnCaja() {
        return registroRapido.registradosEnCaja();
    }

    /**
     * V51 §4.3: lo vendido sin registrar (módulo venta_sin_registro), agrupado, para la cola del panel.
     * V55: sin las líneas cuyo código ya es un código vigente de un producto del negocio.
     */
    @GetMapping("/sin-registrar")
    @Operation(summary = "Productos vendidos sin registrar en los últimos días, agrupados por nombre, código y precio")
    public List<com.suresell.orders.application.usecase.VentasSinRegistrar.Pendiente> sinRegistrar(
            @org.springframework.web.bind.annotation.RequestParam(value = "dias", defaultValue = "30") int dias) {
        return ventasSinRegistrar.pendientes(dias);
    }
    @GetMapping("/categories-with-products")
    @Operation(summary = "Listar categorías con sus productos")
    public List<MenuCategoryResponse> getCategoriesWithProducts() {
        return menuCatalogPort.getCategoriesWithProducts();
    }
    @GetMapping("/products")
    @Operation(summary = "Listar productos del catálogo")
    public List<MenuProductResponse> getProducts() {
        return menuCatalogPort.getProducts();
    }
    @PostMapping("/sync")
    @Operation(summary = "Forzar sincronización de catálogo con la nube", description = "Descarga y actualiza categorías y productos desde PostgreSQL a SQLite local.")
    public void syncCatalog() {
        menuCatalogPort.syncCatalog();
    }

    // ------------------------------------------------------------------
    // V51 — los códigos de un producto (EAN, PLU…). Mínimo: listar, añadir,
    // retirar. El catálogo del POS los trae dentro de cada producto.
    // ------------------------------------------------------------------

    @GetMapping("/products/{id}/codigos")
    @Operation(summary = "Los códigos vigentes de un producto")
    public List<com.suresell.orders.application.dto.CodigoDeProductoResponse> codigos(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return codigosDeProducto.deProducto(id);
    }

    @PostMapping("/products/{id}/codigos")
    @Operation(summary = "Añadir un código a un producto",
            description = "409 YA_EXISTE si el código ya está vigente en este negocio; 404 si el producto no existe.")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public com.suresell.orders.application.dto.CodigoDeProductoResponse agregarCodigo(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.suresell.orders.application.dto.NuevoCodigoDeProductoRequest cuerpo,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return codigosDeProducto.agregar(id, cuerpo.codigo(), cuerpo.cantidad(), cuerpo.tipo(),
                "panel", usuario(authorization));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/products/{id}/codigos/{codigo}")
    @Operation(summary = "Retirar un código de un producto (se cierra, no se borra)")
    public org.springframework.http.ResponseEntity<Void> retirarCodigo(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.PathVariable String codigo,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false)
            String authorization) {
        int cerrados = codigosDeProducto.retirar(id, codigo, usuario(authorization));
        return cerrados == 0
                ? org.springframework.http.ResponseEntity.notFound().build()
                : org.springframework.http.ResponseEntity.noContent().build();
    }

    /** Quién lo hizo (regla 4 de los lineamientos): el sujeto del JWT, o nulo si no se puede leer. */
    private String usuario(String authorization) {
        try {
            return jwt.resolveSubject(authorization).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
