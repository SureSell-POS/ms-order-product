package com.suresell.orders.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** V52 — Respuesta de {@code PATCH /orders/{id}/recibo}: el estado de la tirilla tal como quedó. */
@Schema(description = "Estado de la tirilla de una venta cobrada")
public record ReciboResponse(
        @Schema(example = "101") Long idOrder,
        @Schema(example = "no_impreso") String reciboEstado,
        @Schema(example = "agente_apagado") String reciboMotivo,
        java.time.OffsetDateTime reciboActualizadoAt) {
}
