package com.suresell.orders.application.dto;
import com.suresell.orders.application.dto.OrderItemResponseRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
@Schema(description = "Respuesta detallada de una orden")
public record OrderResponseRecord(
    @Schema(description = "ID único de la orden", example = "101")
    Long idOrder, 
    @Schema(description = "Color del pager", example = "AZUL")
    String pagerColor, 
    @Schema(description = "Número del pager", example = "5")
    String pagerNumber, 
    @Schema(description = "Fecha y hora de creación")
    LocalDateTime createdAt, 
    @Schema(description = "Subtotal antes de descuentos", example = "45000")
    BigDecimal subtotal, 
    @Schema(description = "Total final después de descuentos", example = "40500")
    BigDecimal total, 
    @Schema(description = "Estado actual de la orden", example = "pagado")
    String status, 
    @Schema(description = "Método de pago", example = "CARD")
    String paymentMethod, 
    @Schema(description = "Código de descuento aplicado", example = "BIENVENIDA")
    String discountCode, 
    @Schema(description = "Porcentaje de descuento aplicado", example = "10")
    BigDecimal discountPercentage, 
    @Schema(description = "Monto del descuento aplicado", example = "4500")
    BigDecimal discountAmount, 
    @Schema(description = "Indica si la orden ya fue entregada", example = "false")
    Boolean delivered, 
    @Schema(description = "Indica si la orden ya se sincronizó con la nube", example = "true")
    Boolean synced,
    @Schema(description = "Indica si la orden fue impresa físicamente", example = "false")
    Boolean isPrinted,
    @Schema(description = "Duración de preparación en segundos", example = "300")
    Integer preparationDurationSeconds, 
    @Schema(description = "Items incluidos en la orden")
    List<OrderItemResponseRecord> items,
    @Schema(description = "Id del mesero que tomó la orden (null si fue caja)", example = "3")
    Long waiterId,
    @Schema(description = "Nombre del mesero que tomó la orden (null si fue caja)", example = "Angie")
    String waiterName,
    @Schema(description = "N3/#1 — Número de MESA en modo Restaurante; null en Plazoleta. "
            + "El historial NO debe deducirlo de pagerNumber: ahí no viaja la mesa.", example = "12")
    Integer tableNumber,
    @Schema(description = "Etiqueta opcional de la mesa", example = "Terraza")
    String tableLabel,
    @Schema(description = "V52 — Estado de la TIRILLA (no de la comanda): no_solicitado | enviado | "
            + "confirmado | no_impreso | descartado", example = "no_impreso")
    String reciboEstado,
    @Schema(description = "V52 — Por qué no salió la tirilla (solo con no_impreso)", example = "agente_apagado")
    String reciboMotivo,
    @Schema(description = "V52 — Última vez que cambió el estado de la tirilla")
    java.time.OffsetDateTime reciboActualizadoAt
) {
}
