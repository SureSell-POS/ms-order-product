package com.suresell.orders.domain.model;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
@Entity
@Table(name = "order_edit_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@jakarta.persistence.EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
public class OrderEditHistory implements com.suresell.orders.multitenant.TenantOwned {
    @Column(name = "tenant_id")
    private String tenantId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "edit_type")
    private String editType;
    @Column(name = "product_id")
    private String productId;
    @Column(name = "product_name")
    private String productName;
    @Column(name = "old_quantity")
    private Integer oldQuantity;
    @Column(name = "new_quantity")
    private Integer newQuantity;
    @Column(name = "old_total")
    private BigDecimal oldTotal;
    @Column(name = "new_total")
    private BigDecimal newTotal;
    @Column(name = "edited_at", updatable = false)
    private LocalDateTime editedAt;
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");

    /**
     * V37 — Quién editó. FK a `users(id)`. Antes esta tabla guardaba QUÉ cambió
     * y cuándo, pero no quién — que es la mitad que importa para el antifraude.
     */
    @jakarta.persistence.Column(name = "edited_by")
    private Long editedBy;

    /**
     * V57 — Que cupon se aplico, en las filas {@code DISCOUNT_APPLIED}. Antes
     * un descuento cambiaba el total de una venta sin escribir una sola fila;
     * ahora escribe en esta misma tabla y esta columna es lo unico del
     * descuento que no cabia en el modelo de una edicion de items. Nula en las
     * ediciones normales: ahi no hubo cupon.
     */
    @jakarta.persistence.Column(name = "discount_code")
    private String discountCode;

}
