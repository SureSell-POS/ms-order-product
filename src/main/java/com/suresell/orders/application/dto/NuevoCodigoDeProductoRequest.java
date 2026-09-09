package com.suresell.orders.application.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** Cuerpo de `POST /api/menu/products/{id}/codigos`. `cantidad` y `tipo` son opcionales (1 y EAN). */
public record NuevoCodigoDeProductoRequest(
        @NotBlank(message = "El código no puede estar vacío.") String codigo,
        BigDecimal cantidad,
        String tipo
) {
}
