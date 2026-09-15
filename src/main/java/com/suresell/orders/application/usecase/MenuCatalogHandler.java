package com.suresell.orders.application.usecase;
import com.suresell.orders.application.dto.MenuCategoryResponse;
import com.suresell.orders.application.dto.MenuProductResponse;
import com.suresell.orders.domain.model.MenuCategory;
import com.suresell.orders.domain.model.MenuProduct;
import com.suresell.orders.domain.port.in.MenuCatalogPort;
import com.suresell.orders.domain.port.out.ProductCatalogPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
@Service
@Primary
@RequiredArgsConstructor
public class MenuCatalogHandler implements MenuCatalogPort {
    private final ProductCatalogPort productCatalogPort;
    private final CatalogSyncService catalogSyncService;
    /** V51: los códigos se leen con UNA consulta para todo el catálogo y se adjuntan en memoria. */
    private final CodigosDeProducto codigosDeProducto;
    @Override
    /**
     * Las categorías del negocio por nombre (el orden de siempre) y, dentro de cada una, sus productos POR ID.
     *
     * <p>F5.8e: por {@code id_product} y no por nombre, a propósito (ECM, 2026-09-15). El POS pinta la cuadrícula en el
     * orden en que llega, y SIN red la saca de Dexie, que la ordena por clave primaria ({@code idProduct}). Antes el
     * servidor no fijaba ningún orden (la colección {@code @OneToMany} no tenía {@code @OrderBy}); con el id la caja ve
     * lo mismo con red y sin red, sin inventar un criterio nuevo.
     */
    public List<MenuCategoryResponse> getCategoriesWithProducts() {
        java.util.Map<String, List<com.suresell.orders.application.dto.CodigoDeProductoResponse>> codigos =
                codigosDeProducto.vigentesPorProducto();
        java.util.Map<String, List<MenuProduct>> porCategoria = new java.util.HashMap<>();
        for (MenuProduct p : productCatalogPort.findAllProductsById()) {
            if (p.getCategory() != null) {
                porCategoria.computeIfAbsent(p.getCategory().getIdCategory(), k -> new java.util.ArrayList<>()).add(p);
            }
        }
        return productCatalogPort.findAllCategories().stream()
                .map(c -> new MenuCategoryResponse(c.getIdCategory(), c.getNameCategory(),
                        porCategoria.getOrDefault(c.getIdCategory(), List.of()).stream()
                                .map(p -> toProductResponse(p, codigos))
                                .toList()))
                .toList();
    }
    @Override
    public List<MenuProductResponse> getProducts() {
        java.util.Map<String, List<com.suresell.orders.application.dto.CodigoDeProductoResponse>> codigos =
                codigosDeProducto.vigentesPorProducto();
        return productCatalogPort.findAllProducts().stream()
                .map(p -> toProductResponse(p, codigos))
                .toList();
    }
    @Override
    public void syncCatalog() {
        catalogSyncService.syncCatalogFromCloud();
    }
    private MenuProductResponse toProductResponse(
            MenuProduct product,
            java.util.Map<String, List<com.suresell.orders.application.dto.CodigoDeProductoResponse>> codigos) {
        String categoryId = product.getCategory() != null ? product.getCategory().getIdCategory() : null;
        String categoryName = product.getCategory() != null ? product.getCategory().getNameCategory() : null;
        return new MenuProductResponse(
                product.getIdProduct(),
                product.getNameProduct(),
                product.getPrice(),
                product.getActive(),
                categoryId,
                categoryName,
                codigos.getOrDefault(product.getIdProduct(), List.of()));
    }
}
