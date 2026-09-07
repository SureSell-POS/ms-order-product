package com.suresell.orders.domain.model;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
@Entity
@Table(name = "order_item")  
@Data
@NoArgsConstructor
@AllArgsConstructor
@jakarta.persistence.EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
public class OrderItem implements org.springframework.data.domain.Persistable<java.util.UUID>, com.suresell.orders.multitenant.TenantOwned {
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "id_order_item", insertable = true, updatable = true)
    private Long idOrderItem;

    @Id
    @Column(name = "uuid_id", nullable = false)
    private java.util.UUID uuidId = java.util.UUID.randomUUID();

    @Transient
    private boolean isNew = true;

    @Override
    public java.util.UUID getId() {
        return uuidId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }

    public Long getIdOrderItem() { return idOrderItem; }
    public void setIdOrderItem(Long idOrderItem) { this.idOrderItem = idOrderItem; }
    public java.util.UUID getUuidId() { return uuidId; }
    public void setUuidId(java.util.UUID uuidId) { this.uuidId = uuidId; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Integer getComboGroup() { return comboGroup; }
    public void setComboGroup(Integer comboGroup) { this.comboGroup = comboGroup; }

    @Column(name = "order_id", insertable = true, updatable = true)
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY)  
    @JoinColumn(name = "order_uuid_id", nullable = false)  
    private Order order;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    @Column(name = "product_id")
    private String productId;
    private int quantity;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
    @Column(name = "total_price")
    private BigDecimal totalPrice;
    private String instructions;
    @Column(name = "combo_group")
    private Integer comboGroup;
    // Ola 2 (V45): de dónde salió el precio y qué línea versionada se aplicó.
    // El precio aplicado se guarda con la venta y no se recalcula después.
    @Column(name = "precio_origen")
    private String precioOrigen;
    @Column(name = "lista_precio_item_id")
    private java.util.UUID listaPrecioItemId;

    // N3/#1 — Cocina: distinguir lo ya preparado de lo recién agregado a la mesa.
    @jakarta.persistence.Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    /** NULL = todavía no se preparó. Es lo que la cocina resalta como nuevo. */
    @jakarta.persistence.Column(name = "prepared_at")
    private java.time.LocalDateTime preparedAt;
}
