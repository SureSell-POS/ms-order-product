package com.suresell.orders.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.domain.port.out.OrderCloudSyncPort;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "sync.cloud", name = "enabled", havingValue = "true")
public class PostgresOrderCloudSyncAdapter implements OrderCloudSyncPort {

    private final @Qualifier("cloudJdbcTemplate") JdbcTemplate cloudJdbcTemplate;
    private final @Qualifier("cloudTransactionTemplate") TransactionTemplate cloudTransactionTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void syncOrderCreatedPayload(String payloadJson) {
        cloudTransactionTemplate.executeWithoutResult(status -> {
            try {
                JsonNode root = objectMapper.readTree(payloadJson);
                String eventType = root.path("eventType").asText();
                switch (eventType) {
                    case "ORDER_CREATED":
                        syncOrder(root);
                        break;
                    case "ORDER_PRINTED_UPDATED":
                        updateOrderPrintedInCloud(root);
                        break;
                    case "CLOSURE_CREATED":
                        upsertClosure(root.path("closure"));
                        break;
                    case "COUPON_SAVED":
                        upsertCoupon(root.path("coupon"));
                        break;
                    case "COUPON_PRODUCT_SAVED":
                        upsertCouponProduct(root);
                        break;
                    case "TRACKING_UPDATED":
                        java.util.UUID orderUuidTracking = asUuid(root.path("orderUuidId"));
                        ensureOrderExistsInCloud(orderUuidTracking);
                        upsertDeliveryTracking(root.path("tracking"), orderUuidTracking);
                        break;
                    case "DISCOUNT_USAGE_CREATED":
                        Long orderIdDiscount = asLong(root.path("usage").path("orderId"));
                        // Para discount_usage seguimos usando order_id (bigint) según esquema
                        upsertDiscountUsage(root.path("usage"));
                        break;
                    case "EDIT_HISTORY_CREATED":
                        Long orderIdHistory = asLong(root.path("history").path("orderId"));
                        // Para edit_history seguimos usando order_id (bigint) según esquema
                        upsertEditHistory(root.path("history"));
                        break;
                    case "DAILY_PAYMENT_RECORD_SAVED":
                        upsertDailyPaymentRecord(root.path("record"));
                        break;
                    default:
                        log.warn("Evento de sincronización no reconocido: {}", eventType);
                }
            } catch (Exception ex) {
                if (ex instanceof OrderNotFoundInCloudException) {
                    log.warn("Pospuesta sincronización: {}", ex.getMessage());
                } else {
                    log.error("Error detallado de sincronización cloud: ", ex);
                }
                throw new IllegalStateException("Error sincronizando a cloud: " + ex.getMessage(), ex);
            }
        });
    }

    private void ensureOrderExistsInCloud(java.util.UUID orderUuid) {
        if (orderUuid == null) return;
        Integer count = cloudJdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE uuid_id = ?",
                Integer.class,
                orderUuid);
        if (count == null || count == 0) {
            throw new OrderNotFoundInCloudException("Orden con UUID " + orderUuid + " aún no existe en cloud.");
        }
    }

    private void ensureOrderExistsInCloud(Long orderId) {
        if (orderId == null) return;
        Integer count = cloudJdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE id_order = ?",
                Integer.class,
                orderId);
        if (count == null || count == 0) {
            throw new OrderNotFoundInCloudException("Orden #" + orderId + " aún no existe en cloud.");
        }
    }

    private static class OrderNotFoundInCloudException extends RuntimeException {
        public OrderNotFoundInCloudException(String message) {
            super(message);
        }
    }

    private void syncOrder(JsonNode root) {
        JsonNode orderNode = root.path("order");
        JsonNode trackingNode = root.path("tracking");
        java.util.UUID orderUuid = asUuid(orderNode.path("uuidId"));
        upsertOrder(orderNode, orderUuid);
        upsertDeliveryTracking(trackingNode, orderUuid);
        upsertOrderItems(orderNode.path("items"), orderUuid);
    }

    private void updateOrderPrintedInCloud(JsonNode root) {
        java.util.UUID orderUuid = asUuid(root.path("uuidId"));
        if (orderUuid == null) {
            // Fallback to idOrder if uuidId not in payload (legacy events)
            Long orderId = asLong(root.path("idOrder"));
            Boolean isPrinted = asBoolean(root.path("isPrinted"));
            cloudJdbcTemplate.update(
                    "UPDATE orders SET is_printed = ?, synced = true WHERE id_order = ?",
                    isPrinted, orderId);
            log.info("Bandera is_printed de orden #{} actualizada a {} en cloud.", orderId, isPrinted);
            return;
        }
        Boolean isPrinted = asBoolean(root.path("isPrinted"));

        cloudJdbcTemplate.update(
                "UPDATE orders SET is_printed = ?, synced = true WHERE uuid_id = ?",
                isPrinted, orderUuid);

        log.info("Bandera is_printed de orden con UUID {} actualizada a {} en cloud.", orderUuid, isPrinted);
    }

    private void upsertOrder(JsonNode orderNode, java.util.UUID orderUuid) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO orders (
                    uuid_id, created_at, discount_amount, discount_code, discount_percentage,
                    pager_color, pager_number, payment_method, status, subtotal, total, synced, is_printed
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    subtotal = EXCLUDED.subtotal,
                    total = EXCLUDED.total,
                    pager_color = EXCLUDED.pager_color,
                    pager_number = EXCLUDED.pager_number,
                    discount_amount = EXCLUDED.discount_amount,
                    discount_code = EXCLUDED.discount_code,
                    discount_percentage = EXCLUDED.discount_percentage,
                    payment_method = EXCLUDED.payment_method,
                    is_printed = EXCLUDED.is_printed,
                    synced = true
                """,
                orderUuid,
                asTimestamp(orderNode.path("createdAt")),
                asBigDecimal(orderNode.path("discountAmount")),
                asString(orderNode.path("discountCode")),
                asBigDecimal(orderNode.path("discountPercentage")),
                asString(orderNode.path("pagerColor")),
                asString(orderNode.path("pagerNumber")),
                asString(orderNode.path("paymentMethod")),
                asString(orderNode.path("status")),
                asBigDecimal(orderNode.path("subtotal")),
                asBigDecimal(orderNode.path("total")),
                true,
                asBoolean(orderNode.path("isPrinted")));
    }

    private void upsertDeliveryTracking(JsonNode trackingNode, java.util.UUID orderUuid) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO order_delivery_tracking (
                    order_id_uuid, delivered, pager_returned, preparation_duration_seconds
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (order_id_uuid) DO UPDATE SET
                    delivered = EXCLUDED.delivered,
                    pager_returned = EXCLUDED.pager_returned,
                    preparation_duration_seconds = EXCLUDED.preparation_duration_seconds
                """,
                orderUuid,
                asBoolean(trackingNode.path("delivered")),
                asBoolean(trackingNode.path("pagerReturned")),
                asInteger(trackingNode.path("preparationDurationSeconds")));
    }

    private void upsertOrderItems(JsonNode itemsNode, java.util.UUID orderUuid) {
        cloudJdbcTemplate.update("DELETE FROM order_item WHERE order_uuid_id = ?", orderUuid);
        if (itemsNode == null || itemsNode.isMissingNode() || !itemsNode.isArray()) return;
        for (JsonNode itemNode : itemsNode) {
            cloudJdbcTemplate.update(
                    """
                    INSERT INTO order_item (
                        uuid_id, combo_group, instructions, product_id, quantity,
                        total_price, unit_price, order_uuid_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    asUuid(itemNode.path("uuidId")),
                    asInteger(itemNode.path("comboGroup")),
                    asString(itemNode.path("instructions")),
                    asString(itemNode.path("productId")),
                    asInteger(itemNode.path("quantity")),
                    asBigDecimal(itemNode.path("totalPrice")),
                    asBigDecimal(itemNode.path("unitPrice")),
                    orderUuid);
        }
    }

    private void upsertClosure(JsonNode n) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO daily_closures (
                    id, user_name, opening_time, closing_time, total_expected_cash, total_expected_card,
                    total_expected_nequi, total_expected_qr, total_counted_cash, total_counted_card,
                    total_counted_nequi, total_counted_qr, difference_amount, status, notes, 
                    base_balance_for_next_day, cash_count_audit, closure_date, petty_cash_expenses, petty_cash_expenses_audit,
                    turno
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 1))
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    notes = EXCLUDED.notes,
                    total_counted_cash = EXCLUDED.total_counted_cash,
                    total_counted_card = EXCLUDED.total_counted_card,
                    total_counted_nequi = EXCLUDED.total_counted_nequi,
                    total_counted_qr = EXCLUDED.total_counted_qr,
                    difference_amount = EXCLUDED.difference_amount,
                    closing_time = EXCLUDED.closing_time,
                    base_balance_for_next_day = EXCLUDED.base_balance_for_next_day,
                    petty_cash_expenses = EXCLUDED.petty_cash_expenses,
                    petty_cash_expenses_audit = EXCLUDED.petty_cash_expenses_audit
                """,
                asUuid(n.path("id")), asString(n.path("userName")), asTimestamp(n.path("openingTime")),
                asTimestamp(n.path("closingTime")), asBigDecimal(n.path("totalExpectedCash")),
                asBigDecimal(n.path("totalExpectedCard")), asBigDecimal(n.path("totalExpectedNequi")),
                asBigDecimal(n.path("totalExpectedQr")), asBigDecimal(n.path("totalCountedCash")),
                asBigDecimal(n.path("totalCountedCard")), asBigDecimal(n.path("totalCountedNequi")),
                asBigDecimal(n.path("totalCountedQr")), asBigDecimal(n.path("differenceAmount")),
                asString(n.path("status")), asString(n.path("notes")),
                asBigDecimal(n.path("baseBalanceForNextDay")), asString(n.path("cashCountAudit")),
                asLocalDate(n.path("closureDate")), asBigDecimal(n.path("pettyCashExpenses")),
                asString(n.path("pettyCashExpensesAudit")),
                // V55: sin el turno, el segundo cierre del día caería en el
                // DEFAULT 1 y chocaría con (negocio, fecha, turno).
                asInteger(n.path("turno")));
    }

    private void upsertCoupon(JsonNode n) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO discount_coupon (
                    id, code, name, description, discount_percentage, valid_from, valid_to, 
                    valid_weekdays, is_active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    code = EXCLUDED.code,
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    discount_percentage = EXCLUDED.discount_percentage,
                    valid_from = EXCLUDED.valid_from,
                    valid_to = EXCLUDED.valid_to,
                    valid_weekdays = EXCLUDED.valid_weekdays,
                    is_active = EXCLUDED.is_active,
                    updated_at = EXCLUDED.updated_at
                """,
                asLong(n.path("id")), asString(n.path("code")), asString(n.path("name")),
                asString(n.path("description")), asBigDecimal(n.path("discountPercentage")),
                asLocalDate(n.path("validFrom")), asLocalDate(n.path("validTo")),
                asString(n.path("validWeekdays")), asBoolean(n.path("isActive")),
                asTimestamp(n.path("createdAt")), asTimestamp(n.path("updatedAt")));
    }

    private void upsertCouponProduct(JsonNode root) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO coupon_product (id, coupon_id, product_id, product_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    product_id = EXCLUDED.product_id,
                    product_name = EXCLUDED.product_name
                """,
                asLong(root.path("id")), asLong(root.path("couponId")),
                asString(root.path("productId")), asString(root.path("productName")));
    }

    private void upsertDiscountUsage(JsonNode n) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO discount_usage (
                    id, order_id, coupon_id, discount_code, subtotal_before_discount, 
                    discount_amount, total_after_discount, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                asLong(n.path("id")), asLong(n.path("orderId")), asLong(n.path("coupon").path("id")),
                asString(n.path("discountCode")), asBigDecimal(n.path("subtotalBeforeDiscount")),
                asBigDecimal(n.path("discountAmount")), asBigDecimal(n.path("totalAfterDiscount")),
                asTimestamp(n.path("createdAt")));
    }

    private void upsertEditHistory(JsonNode n) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO order_edit_history (
                    id, order_id, edit_type, product_id, product_name, old_quantity, new_quantity,
                    old_total, new_total, edited_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                asLong(n.path("id")), asLong(n.path("orderId")), asString(n.path("editType")),
                asString(n.path("productId")), asString(n.path("productName")), asInteger(n.path("oldQuantity")),
                asInteger(n.path("newQuantity")), asBigDecimal(n.path("oldTotal")),
                asBigDecimal(n.path("newTotal")), asTimestamp(n.path("editedAt")));
    }

    private void upsertDailyPaymentRecord(JsonNode n) {
        cloudJdbcTemplate.update(
                """
                INSERT INTO daily_payment_records (
                    id, record_date, payment_method, amount, registered_by, notes, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    amount = EXCLUDED.amount,
                    registered_by = EXCLUDED.registered_by,
                    notes = EXCLUDED.notes,
                    updated_at = EXCLUDED.updated_at
                """,
                asUuid(n.path("id")),
                asLocalDate(n.path("recordDate")),
                asString(n.path("paymentMethod")),
                asBigDecimal(n.path("amount")),
                asString(n.path("registeredBy")),
                asString(n.path("notes")),
                asTimestamp(n.path("createdAt")),
                asTimestamp(n.path("updatedAt")));
        log.info("Daily payment record sincronizado a cloud: {}", asUuid(n.path("id")));
    }

    private String asString(JsonNode node) { return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asText(); }
    private Long asLong(JsonNode node) { return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asLong(); }
    private Integer asInteger(JsonNode node) { return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asInt(); }
    private Boolean asBoolean(JsonNode node) { return (node == null || node.isNull() || node.isMissingNode()) ? null : node.asBoolean(); }

    private java.util.UUID asUuid(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return java.util.UUID.fromString(node.asText());
        } catch (Exception e) {
            log.warn("Error convirtiendo a UUID: {}", node.asText());
            return null;
        }
    }

    /**
     * Convierte un importe del JSON de sincronización.
     *
     * <h3>Un importe ilegible es un HECHO, no un {@code null}</h3>
     *
     * La versión anterior devolvía {@code null} ante cualquier error de
     * conversión, <b>sin una sola línea de log</b>. Un importe corrupto se
     * convertía en columna vacía y de ahí en cero, en silencio absoluto, y esto
     * es dinero. Era el fallback más sigiloso del inventario de
     * `discovery/FALLBACK-SILENCIOSO.md` (D10) — más que el del cierre de caja,
     * que al menos dejaba un WARN.
     *
     * <p>Ahora se distinguen los dos casos, que no son el mismo:
     *
     * <ul>
     *   <li><b>Ausente</b> ({@code null}, faltante o cadena vacía) → {@code null}.
     *       El campo no venía; es información legítima y silenciosa.</li>
     *   <li><b>Presente pero ilegible</b> → <b>excepción</b>. El campo venía y
     *       no se pudo leer: eso NO es "no había importe", es un dato corrupto.
     *       El evento del outbox queda FAILED con el motivo y se puede
     *       investigar, en vez de escribir un cero que nadie sabrá de dónde
     *       salió.</li>
     * </ul>
     *
     * <p>Fallar aquí no pierde la venta: el outbox reintenta con backoff y
     * conserva el evento (`SyncOutboxScheduler.java:53-70`). Lo que se pierde es
     * la posibilidad de escribir un importe equivocado.
     */
    private BigDecimal asBigDecimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String text = node.asText();
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(text);
        } catch (Exception e) {
            throw new ImporteIlegibleException(text, e);
        }
    }

    /**
     * Un importe venía en el evento y no se pudo leer. Se lanza en vez de
     * devolver {@code null} porque un cero inventado y un cero real son
     * indistinguibles una vez escritos.
     */
    public static class ImporteIlegibleException extends RuntimeException {
        public ImporteIlegibleException(String textoRecibido, Throwable causa) {
            super("Importe ilegible en el evento de sincronizacion: '" + textoRecibido + "'", causa);
        }
    }

    private java.sql.Date asLocalDate(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            if (node.isNumber()) {
                return new java.sql.Date(node.asLong());
            }
            return java.sql.Date.valueOf(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private Timestamp asTimestamp(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) {
            return new Timestamp(node.asLong());
        }
        LocalDateTime dateTime = asLocalDateTime(node);
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    private LocalDateTime asLocalDateTime(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(node.asLong()), java.time.ZoneId.systemDefault());
        }
        if (node.isTextual()) {
            try { return LocalDateTime.parse(node.asText()); } catch (Exception e) { return null; }
        }
        if (node.isArray() && node.size() >= 6) {
            return LocalDateTime.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt(),
                                    node.get(3).asInt(), node.get(4).asInt(), node.get(5).asInt(),
                                    node.size() > 6 ? node.get(6).asInt() : 0);
        }
        return null;
    }
}
