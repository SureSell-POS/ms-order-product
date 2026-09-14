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
 * {@code vendidoACredito} (plan de mayoristas F1.13, aditivo): lo vendido a
 * crédito en el turno. Es INFORMATIVO: no entra en {@code totalExpected} ni en
 * el efectivo esperado, porque ese dinero no está en el cajón (queda en cuentas
 * por cobrar).
 * {@code recaudoCarteraEfectivo} (F4.5, aditivo): abonos de cartera en efectivo
 * del turno, menos sus anulaciones. YA está sumado en {@code totalExpectedCash} y
 * {@code totalExpected} (está en el cajón), y se informa aparte porque no es venta.
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
        BigDecimal baseSugerida,
        BigDecimal vendidoACredito,
        BigDecimal recaudoCarteraEfectivo) {
}
