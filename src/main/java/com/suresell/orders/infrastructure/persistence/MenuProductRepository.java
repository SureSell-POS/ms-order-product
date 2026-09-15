package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.MenuProduct;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface MenuProductRepository extends JpaRepository<MenuProduct, String> {
    /** F5.8e: el negocio va escrito (regla «RLS no es lógica de negocio»); RLS sigue debajo. */
    @Query("SELECT p FROM MenuProduct p LEFT JOIN FETCH p.category WHERE p.tenantId = :tenantId ORDER BY p.nameProduct ASC")
    List<MenuProduct> findAllWithCategory(@org.springframework.data.repository.query.Param("tenantId") String tenantId);
    /** F5.8e: los productos del negocio por id, para agruparlos por categoría (ver MenuCatalogHandler). */
    @Query("SELECT p FROM MenuProduct p LEFT JOIN FETCH p.category WHERE p.tenantId = :tenantId ORDER BY p.idProduct ASC")
    List<MenuProduct> findDelNegocioPorId(@org.springframework.data.repository.query.Param("tenantId") String tenantId);
    @Query("SELECT p FROM MenuProduct p LEFT JOIN FETCH p.category WHERE p.idProduct IN :productIds")
    List<MenuProduct> findByIdProductInWithCategory(Collection<String> productIds);
}
