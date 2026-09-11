package com.suresell.orders.application.dto;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lo que el POS pinta antes de cerrar.
 *
 * <p>Campos de V55 (aditivos; un POS viejo los ignora):
 * <ul>
 *   <li>{@code turno}: número del turno que se va a cerrar (cierres de hoy + 1).
 *   <li>{@code cierresHoy}: cuántos cierres lleva el día.
 *   <li>{@code abiertoDesde}: closing_time del último cierre (también si fue
 *       hoy), o el inicio de la ventana si es el primero de la historia.
 *   <li>{@code baseInicial}: con lo que arrancó este turno —la base que dejó el
 *       cierre anterior; si no hay, {@code base_caja}; si no, 0.
 *   <li>{@code baseSugerida}: {@code base_caja} del negocio, para precargar el
 *       campo «base que dejas para el siguiente turno».
 * </ul>
 * {@code previousBaseBalance} se conserva con el mismo valor que
 * {@code baseInicial} para los clientes que ya lo leían.
 */
public record ClosurePreviewResponse(
        LocalDateTime openingTime,
        LocalDateTime currentTime,
        int totalOrders,
        BigDecimal totalExpectedCash,
        BigDecimal totalExpectedCard,
        BigDecimal totalExpectedQr,
        BigDecimal totalExpected,
        BigDecimal previousBaseBalance,
        String message,
        int turno,
        int cierresHoy,
        LocalDateTime abiertoDesde,
        BigDecimal baseInicial,
        BigDecimal baseSugerida) {
}
