package com.suresell.orders.domain.port.out;

import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepositoryPort {
    Order save(Order order);
    Optional<Order> findById(Long id);
    /**
     * Busca una orden por la clave de idempotencia del cliente (N2/D1). Es lo que
     * permite que un segundo POST de la misma venta devuelva la orden existente en
     * vez de crear otra con folio nuevo. RLS acota la búsqueda al tenant.
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    /** N3 — Recalcula los totales de la orden de una mesa (UPDATE dirigido). */
    int actualizarTotales(Long idOrder, java.math.BigDecimal subtotal, java.math.BigDecimal total);
    /** N3 — Órdenes de una cuenta de mesa (para acumular las rondas). */
    List<Order> findByTableSessionId(java.util.UUID tableSessionId);
    Optional<Long> findNumericIdByUuid(java.util.UUID uuidId);
    List<Order> findAll();
    Page<Order> findAll(Pageable pageable);
    Optional<Order> findOccupiedPagerOrder(
            String pagerColor, String pagerNumber, OrderStatus status);
    List<Order> findActiveOrders(OrderStatus status);
    List<Order> findActiveOrdersWithItems(OrderStatus status);
    List<Object[]> findTotalByPaymentMethodAndStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay);
    Optional<LocalDateTime> findMinCreatedAtByStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay);
    Integer countByStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay);
    Optional<Order> findFirstByOrderByCreatedAtAsc();
    Optional<LocalDateTime> findMinCreatedAt();
    Optional<Long> findMaxIdOrder();
    List<Order> findAllWithItems();
    Page<Order> findAllOrdersOnly(String pagerColor, String pagerNumber, Long idOrder, String reciboEstado, Pageable pageable);
    List<Order> findOrdersAfter(Long afterId, Pageable pageable);
    List<Order> findByStatusAndPaymentMethodIsNotNullAndCreatedAtBetween(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay);
}
