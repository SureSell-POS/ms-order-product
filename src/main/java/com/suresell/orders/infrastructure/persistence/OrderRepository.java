package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface OrderRepository extends JpaRepository<Order, java.util.UUID> {
    Optional<Order> findByIdOrder(Long idOrder);

    // F4 Inc.3 (docs/200): dedupe de reintentos del móvil (RLS acota al tenant).
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /** F5 A13: soft-delete (idempotente — solo si aún no está borrada). */
    @Modifying
    @Query("UPDATE Order o SET o.deletedAt = :at, o.deletedBy = :by WHERE o.uuidId = :uuid AND o.deletedAt IS NULL")
    int softDelete(@Param("uuid") java.util.UUID uuid,
                   @Param("at") LocalDateTime at,
                   @Param("by") String by);

    /**
     * Deshace un soft-delete. Va por idOrder y NO por la entidad porque el
     * @SQLRestriction("deleted_at IS NULL") esconde las borradas: cualquier
     * find las devolvería vacías. Idempotente (solo si sigue borrada).
     */
    @Modifying
    @Query(value = "UPDATE orders SET deleted_at = NULL, deleted_by = NULL "
            + "WHERE id_order = :idOrder AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreDeleted(@Param("idOrder") Long idOrder);

    /**
     * Marca la orden recién creada con idempotencia + autoría del mesero.
     * UPDATE dirigido (no merge): la entidad Order carga colecciones inmutables
     * que Hibernate no puede reemplazar en un merge.
     */
    @Modifying
    @Query("""
            UPDATE Order o SET o.idempotencyKey = :key, o.waiterId = :waiterId,
                   o.waiterSessionId = :sessionId
            WHERE o.uuidId = :uuid
            """)
    int tagWaiterOrder(@Param("uuid") java.util.UUID uuid,
                       @Param("key") String key,
                       @Param("waiterId") Long waiterId,
                       @Param("sessionId") java.util.UUID sessionId);

    List<Order> findByWaiterSessionId(java.util.UUID waiterSessionId);

    /**
     * N3 — Recalcula los totales de la orden de una mesa al agregarle una ronda.
     * UPDATE dirigido y NO save(): `Order.items` es una colección con
     * orphanRemoval, y reemplazarla con setItems() hace que Hibernate lance
     * "A collection with orphan deletion was no longer referenced...". Misma
     * razón por la que tagWaiterOrder también es un UPDATE.
     */
    @Modifying
    @Query("UPDATE Order o SET o.subtotal = :subtotal, o.total = :total WHERE o.idOrder = :idOrder")
    int actualizarTotales(@Param("idOrder") Long idOrder,
                          @Param("subtotal") java.math.BigDecimal subtotal,
                          @Param("total") java.math.BigDecimal total);

    /** N3 — Órdenes de una cuenta de mesa (para cobrarla completa). */
    List<Order> findByTableSessionId(java.util.UUID tableSessionId);

    /**
     * N3 — Cobra TODAS las órdenes de la cuenta de una sola vez: pasan de
     * `abierta` a `pagado` con el medio elegido. UPDATE dirigido y no merge de
     * entidades, por lo mismo que tagWaiterOrder: Order carga colecciones que
     * Hibernate no puede reemplazar en un merge.
     */
    @Modifying
    @Query("UPDATE Order o SET o.status = com.suresell.orders.domain.model.OrderStatus.pagado, "
            + "o.paymentMethod = :metodo "
            + "WHERE o.tableSessionId = :sesion "
            + "AND o.status = com.suresell.orders.domain.model.OrderStatus.abierta")
    int cobrarOrdenesDeLaMesa(@Param("sesion") java.util.UUID sesion, @Param("metodo") String metodo);

    @Query("""
            SELECT o FROM Order o
            WHERE o.createdAt BETWEEN :start AND :end
            AND (:idOrder IS NULL OR o.idOrder = :idOrder)
            AND (:pagerNumber IS NULL OR o.pagerNumber = :pagerNumber)
            AND (:pagerColor IS NULL OR o.pagerColor = :pagerColor)
            AND (:waiterId IS NULL OR o.waiterId = :waiterId)
            ORDER BY o.createdAt DESC
            """)
    List<Order> findWaiterHistory(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("idOrder") Long idOrder,
                                  @Param("pagerNumber") String pagerNumber,
                                  @Param("pagerColor") String pagerColor,
                                  @Param("waiterId") Long waiterId);

    @Query("SELECT o.idOrder FROM Order o WHERE o.uuidId = :uuidId")
    Optional<Long> findNumericIdByUuid(@Param("uuidId") java.util.UUID uuidId);

    @Query("""
            SELECT o
            FROM Order o
            LEFT JOIN o.deliveryTracking dt
            WHERE o.pagerColor = :pagerColor
              AND o.pagerNumber = :pagerNumber
              AND o.status = :status
              AND (dt IS NULL OR dt.delivered = :delivered)
            ORDER BY o.idOrder DESC
            """)
    List<Order> findOccupiedPagerOrders(
            @Param("pagerColor") String pagerColor,
            @Param("pagerNumber") String pagerNumber,
            @Param("status") OrderStatus status,
            @Param("delivered") Boolean delivered);
    @Query(value="SELECT o FROM Order o WHERE o.status = :status")
    List<Order> findActiveOrders(@Param("status") OrderStatus status);
    @Query("""
            SELECT DISTINCT o
            FROM Order o
            LEFT JOIN FETCH o.items
            LEFT JOIN FETCH o.deliveryTracking dt
            WHERE o.status = :status
              AND (dt IS NULL OR dt.delivered = :delivered)
            """)
    List<Order> findActiveOrdersWithItems(@Param("status") OrderStatus status, @Param("delivered") Boolean delivered);
    @Query("SELECT o.paymentMethod, SUM(o.total) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :startOfDay AND :endOfDay GROUP BY o.paymentMethod")
    List<Object[]> findTotalByPaymentMethodAndStatus(
            @Param("status") OrderStatus status,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);
    @Query("SELECT MIN(o.createdAt) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :startOfDay AND :endOfDay")
    Optional<LocalDateTime> findMinCreatedAtByStatus(
            @Param("status") OrderStatus status,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);
    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :startOfDay AND :endOfDay")
    Integer countByStatus(
            @Param("status") OrderStatus status,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);
    List<Order> findByStatusAndPaymentMethodIsNotNullAndCreatedAtBetween(
            OrderStatus status,
            LocalDateTime startOfDay,
            LocalDateTime endOfDay);
    Optional<Order> findFirstByOrderByCreatedAtAsc();
    @Query("SELECT MAX(o.idOrder) FROM Order o")
    Optional<Long> findMaxIdOrder();
    @Query("SELECT MIN(o.createdAt) FROM Order o")
    Optional<LocalDateTime> findMinCreatedAt();
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.deliveryTracking")
    List<Order> findAllWithItems();
    @Query("SELECT o FROM Order o ORDER BY o.idOrder DESC")
    Page<Order> findAllOrdersOnly(Pageable pageable);
    @Query("""
            SELECT o FROM Order o 
            WHERE (:pagerColor IS NULL OR o.pagerColor = :pagerColor)
              AND (:pagerNumber IS NULL OR o.pagerNumber = :pagerNumber)
              AND (:idOrder IS NULL OR o.idOrder = :idOrder)
              AND (:reciboEstado IS NULL OR o.reciboEstado = :reciboEstado)
            ORDER BY o.idOrder DESC
            """)
    Page<Order> findWithFilters(
            @Param("pagerColor") String pagerColor,
            @Param("pagerNumber") String pagerNumber,
            @Param("idOrder") Long idOrder,
            @Param("reciboEstado") String reciboEstado,
            Pageable pageable);
    @Query("SELECT o FROM Order o WHERE o.idOrder < :afterId ORDER BY o.idOrder DESC")
    List<Order> findOrdersAfter(@Param("afterId") Long afterId, Pageable pageable);
    @Modifying
    @Query("UPDATE Order o SET o.synced = true WHERE o.idOrder = :orderId")
    void markOrderAsSynced(@Param("orderId") Long orderId);
  // F5 multipago: las órdenes MIXED no suman aquí — sus montos por método
  // salen de order_payments (OrderPaymentRepository.sumSplitsByMethod).
  // N3/A1 — Esta consulta NO filtraba por estado. Al aparecer el estado
  // `abierta` (mesas consumiendo sin cobrar), el cierre las habría sumado como
  // venta del día. Se excluyen explícitamente.
  @Query("SELECT o.paymentMethod, SUM(o.total) FROM Order o " +
          "WHERE o.createdAt BETWEEN :startTime AND :endTime " +
          "AND o.paymentMethod <> 'MIXED' " +
          "AND o.status <> com.suresell.orders.domain.model.OrderStatus.abierta " +
          "GROUP BY o.paymentMethod")
  List<Object[]> sumTotalsByPaymentMethodAndSeller(
          @Param("startTime") LocalDateTime startTime,
          @Param("endTime") LocalDateTime endTime
  );

  // ---------------------------------------------------------------------
  // Ventas por MESERO del dia (cierre de caja del POS).
  //
  // Antes el POS pedia esto a ms-core-app, que lee la base LEGACY: tras el
  // cutover mostraba solo las ventas previas al corte y nunca se actualizaba.
  // Estas consultas lo calculan sobre la base de V2.
  //
  // El @SQLRestriction("deleted_at IS NULL") de Order ya excluye las borradas.
  // ---------------------------------------------------------------------

  /**
   * Ordenes por mesero (para ordersCount, sin duplicar por metodo de pago).
   *
   * Excluye las `abierta`: una mesa que todavia esta consumiendo no es una venta.
   */
  @Query("""
          SELECT o.waiterId, w.name, COUNT(o)
          FROM Order o LEFT JOIN Waiter w ON w.id = o.waiterId
          WHERE o.createdAt BETWEEN :start AND :end
          AND o.status <> com.suresell.orders.domain.model.OrderStatus.abierta
          GROUP BY o.waiterId, w.name
          """)
  List<Object[]> contarOrdenesPorMesero(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

  /**
   * Montos por mesero y metodo. Las MIXED se excluyen: sus montos salen de los splits.
   *
   * Tambien excluye las `abierta`. Una orden de mesa NACE con metodo de pago ya
   * puesto (OrderHandler: el estado depende de si hay cuenta de mesa, el metodo
   * se asigna igual), asi que sin este filtro una cuenta sin cobrar entraba como
   * venta en efectivo del mesero y le generaba un faltante por plata que nunca
   * recibio.
   */
  @Query("""
          SELECT o.waiterId, COALESCE(o.paymentMethod, 'UNKNOWN'), COALESCE(SUM(o.total), 0)
          FROM Order o
          WHERE o.createdAt BETWEEN :start AND :end
          AND o.status <> com.suresell.orders.domain.model.OrderStatus.abierta
          AND (o.paymentMethod IS NULL OR o.paymentMethod <> 'MIXED')
          GROUP BY o.waiterId, o.paymentMethod
          """)
  List<Object[]> sumarPorMeseroYMetodo(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);
}
