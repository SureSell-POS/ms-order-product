package com.suresell.orders.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")  
@Data
@NoArgsConstructor
@AllArgsConstructor
@jakarta.persistence.EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
public class Order implements org.springframework.data.domain.Persistable<java.util.UUID>, com.suresell.orders.multitenant.TenantOwned {
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "id_order", insertable = true, updatable = true)
    private Long idOrder;

    @Id
    @Column(name = "uuid_id", unique = true, nullable = false)
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

    public Long getIdOrder() { return idOrder; }
    public void setIdOrder(Long idOrder) { this.idOrder = idOrder; }
    public java.util.UUID getUuidId() { return uuidId; }
    public void setUuidId(java.util.UUID uuidId) { this.uuidId = uuidId; }
    public String getPagerColor() { return pagerColor; }
    public void setPagerColor(String pagerColor) { this.pagerColor = pagerColor; }
    public String getPagerNumber() { return pagerNumber; }
    public void setPagerNumber(String pagerNumber) { this.pagerNumber = pagerNumber; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
    public OrderDeliveryTracking getDeliveryTracking() { return deliveryTracking; }
    public void setDeliveryTracking(OrderDeliveryTracking deliveryTracking) { this.deliveryTracking = deliveryTracking; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getDiscountCode() { return discountCode; }
    public void setDiscountCode(String discountCode) { this.discountCode = discountCode; }
    public BigDecimal getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(BigDecimal discountPercentage) { this.discountPercentage = discountPercentage; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public Boolean getSynced() { return synced; }
    public void setSynced(Boolean synced) { this.synced = synced; }
    public Boolean getIsPrinted() { return isPrinted; }
    public void setIsPrinted(Boolean isPrinted) { this.isPrinted = isPrinted; }

    @Column(name = "pager_color")
    private String pagerColor;
    @Column(name = "pager_number")
    private String pagerNumber;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
    private BigDecimal subtotal;
    private BigDecimal total;
    @Enumerated(EnumType.STRING)  
    private OrderStatus status;
    @Column(name = "payment_method")
    private String paymentMethod;
    @Column(name = "discount_code")
    private String discountCode;
    @Column(name = "discount_percentage")
    private BigDecimal discountPercentage;
    @Column(name = "discount_amount")
    private BigDecimal discountAmount;
        @Column(name = "synced", nullable = false)
        private Boolean synced = false;
    
        @Column(name = "is_printed", nullable = false)
    private Boolean isPrinted = false;

    // F5 A13: soft-delete por admin — las órdenes borradas desaparecen de TODAS
    // las consultas de la entidad (historial, cocina, pagers, cierres) por el
    // filtro global de abajo; el rastro queda en order_deletions.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    @Column(name = "deleted_by")
    private String deletedBy;

    // F4 Inc.3 (docs/200): dedupe de reintentos del móvil + autoría del mesero.
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "waiter_id")
    private Long waiterId;
    @Column(name = "waiter_session_id")
    private java.util.UUID waiterSessionId;

    // N3 — Modo Restaurante: cuenta de mesa a la que pertenece la orden.
    // NULL en plazoleta y en todo el histórico.
    @Column(name = "table_session_id")
    private java.util.UUID tableSessionId;

    // ------------------------------------------------------------------
    // V36 — MODELO TEMPORAL Y PROCEDENCIA.
    //
    // `createdAt` NO se toca: cinco servicios lo leen y de él dependen los
    // cierres, la analítica y los rastreadores. Estas columnas van al lado.
    //
    // La diferencia entre las dos fechas ES el dato:
    // `registradoEn - ocurridoEn` responde "¿cuánto estuvo este local sin
    // conexión?", que hoy no se puede responder de ninguna manera.
    // ------------------------------------------------------------------

    /**
     * Cuándo ocurrió, según el reloj del DISPOSITIVO, tal como lo mandó.
     * NULL si el cliente no la envía — y se queda nulo. No se rellena con la
     * del servidor: un nulo honesto vale más que un dato inventado que después
     * nadie puede distinguir de uno real.
     */
    @Column(name = "ocurrido_en")
    private java.time.OffsetDateTime ocurridoEn;

    /** Cuándo lo supo el SERVIDOR. Siempre reloj del servidor, nunca del cliente. */
    @Column(name = "registrado_en")
    private java.time.OffsetDateTime registradoEn;

    /** Qué caja la produjo. FK a `terminals` (V35). */
    @Column(name = "terminal_id")
    private java.util.UUID terminalId;

    /** Vida del terminal; sube cuando pierde su estado local. Ver V35. */
    @Column(name = "epoch")
    private Integer epoch;

    /**
     * Secuencia monotónica del EVENTO DEL OUTBOX que produjo esta orden, dentro
     * de `(terminalId, epoch)`. Es del evento, no de la orden: en la Fase 3
     * llegan más tipos de evento y todos entran en la misma secuencia.
     */
    @Column(name = "seq")
    private Long seq;

    /**
     * SHA-256 hex del evento anterior de ese terminal en ese epoch. NULL en el
     * primero de cada epoch. La definición exacta del hash está en la cabecera
     * de V36 y no se cambia sin migrar la cadena entera.
     */
    @Column(name = "hash_anterior")
    private String hashAnterior;

    /**
     * Si la fecha del dispositivo era creíble:
     * {@code sin_fecha | creible | adelantado | muy_atrasado}.
     * NUNCA se rechaza una venta por esto; solo se deja constancia. La deriva
     * de un reloj es un dato, no un error.
     */
    @Column(name = "reloj_veredicto")
    private String relojVeredicto;

    /**
     * V36 — Total del cliente menos total del servidor. SEÑAL, NO AUTORIDAD.
     *
     * <p>{@code 0} = comparados y coinciden. {@code NULL} = el cliente no mandó
     * total, así que no había con qué comparar — que no es lo mismo que cero.
     *
     * <p>El servidor usa siempre su propio cálculo; esta columna no participa en
     * ninguno. Existe porque descartar el importe del cliente sin compararlo
     * desperdiciaba una señal que ya llegaba gratis.
     */
    // Ola 2 (mayorista, V45): a quién se le vendió, con qué lista, y si el
    // crédito superó el cupo. `excede_cupo` lo escribe el trigger de la base
    // al insertar; aquí solo se lee.
    @Column(name = "cliente_documento")
    private String clienteDocumento;
    @Column(name = "lista_precio_id")
    private java.util.UUID listaPrecioId;
    @Column(name = "excede_cupo", insertable = false, updatable = false)
    private Boolean excedeCupo;

    @Column(name = "total_discrepancia")
    private BigDecimal totalDiscrepancia;

    /**
     * V37 — Quién registró la venta. FK a `users(id)`, no un nombre suelto: un
     * cambio de nombre o dos empleados homónimos no pueden romper la
     * trazabilidad. Regla 4 de LINEAMIENTOS.
     *
     * <p>NULL en el histórico anterior a V37. `waiterId` es otra cosa y se
     * queda: identifica al mesero, no a quien operó la caja.
     */
    @Column(name = "created_by")
    private Long createdBy;

    @OneToMany(mappedBy = "order", orphanRemoval = true)
    private List<OrderItem> items;
    @OneToOne(mappedBy = "order", orphanRemoval = true, fetch = FetchType.EAGER)
    private OrderDeliveryTracking deliveryTracking;
}
