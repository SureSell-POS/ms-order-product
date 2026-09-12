package com.suresell.orders.infrastructure.web;

import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderDeletion;
import com.suresell.orders.infrastructure.persistence.OrderDeletionRepository;
import com.suresell.orders.infrastructure.persistence.OrderRepository;
import com.suresell.orders.multitenant.JwtTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Borrado de órdenes por ADMINISTRADOR (F5 A13, anti-robo): SOFT-DELETE con
 * auditoría — nunca borrado físico. La orden desaparece de historial, cocina,
 * pagers y cierres (filtro global de la entidad Order) y queda el rastro en
 * order_deletions (quién, cuándo, motivo, monto). Solo rol admin (JWT).
 * SOLO perfil cloud (multi-tenant).
 */
@RestController
@Profile("cloud")
@RequestMapping("/api/orders")
public class OrderDeletionController {

    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");

    private final OrderRepository orderRepository;
    private final OrderDeletionRepository deletionRepository;
    private final JwtTenantResolver resolver;

    public OrderDeletionController(OrderRepository orderRepository,
                                   OrderDeletionRepository deletionRepository,
                                   JwtTenantResolver resolver) {
        this.orderRepository = orderRepository;
        this.deletionRepository = deletionRepository;
        this.resolver = resolver;
    }

    public record DeleteOrderRequest(String reason, String authorizationPassword) {
    }

    /**
     * N2/6.5 — segundo factor para borrar desde el POS.
     *
     * El rol admin + motivo obligatorio + auditoría ya existían, pero en el POS
     * la sesión de admin suele quedar abierta en el mostrador: quien pase por el
     * equipo puede borrar una venta. Con esto hace falta además una clave que el
     * dueño no deja pegada en la caja.
     *
     * Semántica: si `order.delete.password` NO está configurada, se conserva el
     * comportamiento anterior (admin + motivo). Es deliberado — hacerlo
     * fail-closed dejaría a los despliegues actuales sin poder borrar de un día
     * para otro. Al configurarla, la protección queda activa.
     */
    @org.springframework.beans.factory.annotation.Value("${order.delete.password:}")
    private String deletePassword;

    @DeleteMapping("/{idOrder}")
    @Transactional
    public ResponseEntity<?> softDelete(@PathVariable Long idOrder,
                                        @RequestBody(required = false) DeleteOrderRequest request,
                                        HttpServletRequest http) {
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Solo un administrador puede borrar órdenes"));
        }
        if (deletePassword != null && !deletePassword.isBlank()) {
            String enviada = request != null && request.authorizationPassword() != null
                    ? request.authorizationPassword().trim() : "";
            if (!deletePassword.equals(enviada)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Clave de autorización incorrecta"));
            }
        }
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("El motivo del borrado es obligatorio");
        }
        Order order = orderRepository.findByIdOrder(idOrder)
                .orElseThrow(() -> new IllegalArgumentException("Orden no encontrada: #" + idOrder));

        String deletedBy = resolver.resolveSubject(http.getHeader("Authorization")).orElse("admin");
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);
        int updated = orderRepository.softDelete(order.getUuidId(), now, deletedBy);
        if (updated == 0) {
            throw new IllegalArgumentException("La orden ya estaba borrada: #" + idOrder);
        }

        OrderDeletion audit = new OrderDeletion();
        audit.setOrderUuidId(order.getUuidId());
        audit.setIdOrder(order.getIdOrder());
        audit.setTotal(order.getTotal());
        audit.setPaymentMethod(order.getPaymentMethod());
        audit.setReason(reason);
        audit.setDeletedBy(deletedBy);
        audit.setCreatedAt(now);
        deletionRepository.save(audit);

        return ResponseEntity.ok(Map.of(
                "message", "Orden #" + idOrder + " borrada (soft-delete, con auditoría)",
                "idOrder", idOrder));
    }

    /**
     * N2/doc-17 — DESHACER un borrado. El soft-delete conserva el dato, pero no
     * había forma de restaurar sin entrar a la base: un borrado por error era
     * irreversible en la práctica. La restauración queda auditada igual que el
     * borrado (una fila con motivo "RESTAURADA: ...").
     */
    @PostMapping("/{idOrder}/restore")
    @Transactional
    public ResponseEntity<?> restore(@PathVariable Long idOrder,
                                     @RequestBody(required = false) DeleteOrderRequest request,
                                     HttpServletRequest http) {
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Solo un administrador puede restaurar órdenes"));
        }
        if (deletePassword != null && !deletePassword.isBlank()) {
            String enviada = request != null && request.authorizationPassword() != null
                    ? request.authorizationPassword().trim() : "";
            if (!deletePassword.equals(enviada)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Clave de autorización incorrecta"));
            }
        }
        // findByIdOrder NO la encuentra: el @SQLRestriction de la entidad esconde
        // las borradas. Por eso la restauración va por UPDATE directo.
        int updated = orderRepository.restoreDeleted(idOrder);
        if (updated == 0) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "No hay una orden borrada con el número #" + idOrder));
        }
        String by = resolver.resolveSubject(http.getHeader("Authorization")).orElse("admin");
        LocalDateTime now = LocalDateTime.now(BOGOTA_ZONE);

        // ------------------------------------------------------------------
        // POR QUE ESTO SE LEE AQUI Y NO ANTES (2026-09-12).
        //
        // La fila de auditoria se construia SIN `order_uuid_id`, y esa columna
        // es `UUID NOT NULL` (V15:15, y la entidad la mapea nullable = false).
        // Resultado: restore() fallaba SIEMPRE, en el INSERT, con la orden ya
        // restaurada y la transaccion revertida. Nadie lo habia visto porque
        // no tenia ni un test.
        //
        // La orden no se puede leer ANTES del UPDATE —el @SQLRestriction de la
        // entidad esconde las borradas—, pero DESPUES si: ya no tiene
        // `deleted_at`. Por eso el orden es restaurar, leer, auditar. Si por lo
        // que sea no aparece, se revierte todo: una restauracion sin rastro es
        // exactamente lo que esta auditoria existe para impedir.
        // ------------------------------------------------------------------
        Order restaurada = orderRepository.findByIdOrder(idOrder)
                .orElseThrow(() -> new IllegalStateException(
                        "La orden #" + idOrder + " se restauro pero no se pudo leer para dejar "
                        + "rastro; no se restaura sin auditoria"));

        OrderDeletion audit = new OrderDeletion();
        audit.setOrderUuidId(restaurada.getUuidId());
        audit.setIdOrder(idOrder);
        audit.setTotal(restaurada.getTotal());
        audit.setPaymentMethod(restaurada.getPaymentMethod());
        audit.setReason("RESTAURADA: " + (request != null && request.reason() != null
                ? request.reason().trim() : "sin motivo"));
        audit.setDeletedBy(by);
        audit.setCreatedAt(now);
        deletionRepository.save(audit);
        return ResponseEntity.ok(Map.of(
                "message", "Orden #" + idOrder + " restaurada", "idOrder", idOrder));
    }

    /** Auditoría de borrados del negocio (admin). */
    @GetMapping("/deletions")
    public ResponseEntity<?> listDeletions(HttpServletRequest http) {
        String role = resolver.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Solo un administrador puede ver la auditoría"));
        }
        List<OrderDeletion> deletions = deletionRepository.findAllByOrderByCreatedAtDesc();
        return ResponseEntity.ok(deletions);
    }
}
