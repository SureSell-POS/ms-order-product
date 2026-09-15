package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.MenuCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, String> {
    /**
     * F5.8e: las categorías del negocio, con el negocio escrito, en el orden de siempre (por nombre). Antes era
     * {@code LEFT JOIN FETCH c.products} sin filtro: la colección traída no se podía filtrar por negocio sin dejarla a
     * medias, así que los productos van en otra consulta y se juntan en {@code MenuCatalogHandler}.
     */
    @Query("SELECT c FROM MenuCategory c WHERE c.tenantId = :tenantId ORDER BY c.nameCategory ASC")
    List<MenuCategory> findDelNegocio(@org.springframework.data.repository.query.Param("tenantId") String tenantId);

    /**
     * Las categorías EN EL ORDEN QUE ELIGIÓ EL NEGOCIO.
     *
     * <p>`display_order` primero; las que no lo tengan van al final, por
     * nombre. Sin esto el orden dependía de lo que devolviera Postgres, que no
     * garantiza ninguno: dos llamadas podían traer el menú distinto.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT c FROM MenuCategory c
            WHERE c.tenantId = :tenantId
            ORDER BY CASE WHEN c.displayOrder IS NULL THEN 1 ELSE 0 END,
                     c.displayOrder,
                     c.nameCategory
            """)
    List<MenuCategory> findAllOrdenadas(@org.springframework.data.repository.query.Param("tenantId") String tenantId);
}
