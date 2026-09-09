package com.suresell.orders.application.dto;

import java.math.BigDecimal;

/** Un código con el que la caja encuentra el producto (V51). `cantidad` es lo que añade al leerlo. */
public record CodigoDeProductoResponse(String codigo, BigDecimal cantidad, String tipo) {
}
