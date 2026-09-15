package com.suresell.orders.domain.port.out;
import com.suresell.orders.application.dto.ProductResponse;
import com.suresell.orders.domain.model.MenuCategory;
import com.suresell.orders.domain.model.MenuProduct;
import java.util.List;
import java.util.Map;
import java.util.Set;
public interface ProductCatalogPort {
    Map<String, ProductResponse> findProductsByIds(Set<String> productIds);
    /** Las categorías del negocio de la sesión, por nombre (F5.8e: filtro escrito). */
    List<MenuCategory> findAllCategories();
    /** Los productos del negocio de la sesión, por nombre (F5.8e: filtro escrito). */
    List<MenuProduct> findAllProducts();
    /** Los productos del negocio de la sesión, por id (F5.8e: el orden de las categorías del POS, ver MenuCatalogHandler). */
    List<MenuProduct> findAllProductsById();
}
