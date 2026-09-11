package com.suresell.orders.application.dto;

import java.math.BigDecimal;

/**
 * Cuerpo de {@code POST /api/menu/products/registro-rapido} (V55, contrato
 * CAJA-POR-TURNOS-Y-REGISTRO-EN-CAJA §3). El código va tal como lo escribió
 * el lector; el tipo es siempre EAN y la cantidad 1. {@code categoriaId} es
 * opcional: sin ella, «General».
 */
public record RegistroRapidoRequest(
        String nombre,
        BigDecimal precio,
        String codigo,
        String pin,
        String categoriaId
) {
}
