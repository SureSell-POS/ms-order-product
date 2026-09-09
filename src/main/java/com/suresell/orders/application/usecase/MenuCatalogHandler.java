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
    public List<MenuCategoryResponse> getCategoriesWithProducts() {
        java.util.Map<String, List<com.suresell.orders.application.dto.CodigoDeProductoResponse>> codigos =
                codigosDeProducto.vigentesPorProducto();
        return productCatalogPort.findAllCategoriesWithProducts().stream()
                .map(c -> toCategoryResponse(c, codigos))
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
    private MenuCategoryResponse toCategoryResponse(
            MenuCategory category,
            java.util.Map<String, List<com.suresell.orders.application.dto.CodigoDeProductoResponse>> codigos) {
        List<MenuProductResponse> products = category.getProducts() == null
                ? List.of()
                : category.getProducts().stream()
                        .map(p -> toProductResponse(p, codigos))
                        .toList();
        return new MenuCategoryResponse(category.getIdCategory(), category.getNameCategory(), products);
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
