package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderStatus;
import com.suresell.orders.domain.port.out.OrderRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
@Component
@RequiredArgsConstructor
public class OrderRepositoryAdapter implements OrderRepositoryPort {
    private final OrderRepository orderRepository;
    @Override
    public Order save(Order order) {
        return orderRepository.save(order);
    }
    @Override
    public Optional<Order> findById(Long id) {
        return orderRepository.findByIdOrder(id);
    }
    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findByIdempotencyKey(idempotencyKey);
    }
    @Override
    public int actualizarTotales(Long idOrder, java.math.BigDecimal subtotal, java.math.BigDecimal total) {
        return orderRepository.actualizarTotales(idOrder, subtotal, total);
    }
    @Override
    public List<Order> findByTableSessionId(java.util.UUID tableSessionId) {
        return orderRepository.findByTableSessionId(tableSessionId);
    }
    @Override
    public Optional<Long> findNumericIdByUuid(java.util.UUID uuidId) {
        return orderRepository.findNumericIdByUuid(uuidId);
    }
    @Override
    public List<Order> findAll() {
        return orderRepository.findAll();
    }
    @Override
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }
    @Override
    public Optional<Order> findOccupiedPagerOrder(
            String pagerColor, String pagerNumber, OrderStatus status) {
        return orderRepository.findOccupiedPagerOrders(pagerColor, pagerNumber, status, Boolean.FALSE).stream().findFirst();
    }
    @Override
    public List<Order> findActiveOrders(OrderStatus status) {
        return orderRepository.findActiveOrders(status);
    }
    @Override
    public List<Order> findActiveOrdersWithItems(OrderStatus status) {
        return orderRepository.findActiveOrdersWithItems(status, Boolean.FALSE);
    }
    @Override
    public List<Object[]> findTotalByPaymentMethodAndStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return orderRepository.findTotalByPaymentMethodAndStatus(status, startOfDay, endOfDay);
    }
    @Override
    public Optional<LocalDateTime> findMinCreatedAtByStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return orderRepository.findMinCreatedAtByStatus(status, startOfDay, endOfDay);
    }
    @Override
    public Integer countByStatus(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return orderRepository.countByStatus(status, startOfDay, endOfDay);
    }
    @Override
    public Optional<Order> findFirstByOrderByCreatedAtAsc() {
        return orderRepository.findFirstByOrderByCreatedAtAsc();
    }
    @Override
    public Optional<Long> findMaxIdOrder() {
        return orderRepository.findMaxIdOrder();
    }

    @Override
    public Optional<LocalDateTime> findMinCreatedAt() {
        return orderRepository.findMinCreatedAt();
    }
    @Override
    public List<Order> findAllWithItems() {
        return orderRepository.findAllWithItems();
    }
    @Override
    public Page<Order> findAllOrdersOnly(String pagerColor, String pagerNumber, Long idOrder, String reciboEstado, Pageable pageable) {
        return orderRepository.findWithFilters(pagerColor, pagerNumber, idOrder, reciboEstado, pageable);
    }
    @Override
    public List<Order> findOrdersAfter(Long afterId, Pageable pageable) {
        return orderRepository.findOrdersAfter(afterId, pageable);
    }
    @Override
    public List<Order> findByStatusAndPaymentMethodIsNotNullAndCreatedAtBetween(
            OrderStatus status, LocalDateTime startOfDay, LocalDateTime endOfDay) {
        return orderRepository.findByStatusAndPaymentMethodIsNotNullAndCreatedAtBetween(
                status, startOfDay, endOfDay);
    }
}
