package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.application.dto.ProductResponse;
import com.suresell.orders.domain.model.MenuCategory;
import com.suresell.orders.domain.model.MenuProduct;
import com.suresell.orders.domain.port.out.ProductCatalogPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class ProductCatalogRepositoryAdapter implements ProductCatalogPort {
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuProductRepository menuProductRepository;
    @Override
    public Map<String, ProductResponse> findProductsByIds(Set<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<MenuProduct> products = menuProductRepository.findByIdProductInWithCategory(productIds);
        Map<String, ProductResponse> result = new LinkedHashMap<>();
        for (MenuProduct product : products) {
            String categoryName = product.getCategory() != null ? product.getCategory().getNameCategory() : null;
            result.put(product.getIdProduct(), new ProductResponse(product.getIdProduct(), product.getNameProduct(), categoryName));
        }
        return result;
    }
    @Override
    public List<MenuCategory> findAllCategories() {
        String negocio = com.suresell.orders.multitenant.TenantContext.get();
        return negocio == null ? List.of() : menuCategoryRepository.findDelNegocio(negocio);
    }
    @Override
    public List<MenuProduct> findAllProductsById() {
        String negocio = com.suresell.orders.multitenant.TenantContext.get();
        return negocio == null ? List.of() : menuProductRepository.findDelNegocioPorId(negocio);
    }
    @Override
    public List<MenuProduct> findAllProducts() {
        String negocio = com.suresell.orders.multitenant.TenantContext.get();
        return negocio == null ? List.of() : menuProductRepository.findAllWithCategory(negocio);
    }
}
