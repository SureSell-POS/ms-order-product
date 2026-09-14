package com.suresell.orders.application.dto.responses;
import java.math.BigDecimal;
import java.util.Map;
/**
 * Resultado del cierre de caja.
 *
 * <p>{@code roundingAdjustment} es ADITIVO (contrato de compatibilidad, fase A):
 * un POS que no lo conozca simplemente lo ignora. Es lo que el negocio dejó de
 * cobrar al dividir cuentas de mesa entre comensales — se reporta como línea
 * propia porque un descuadre silencioso rompería la promesa de un cierre
 * auditable al peso.
 *
 * <p>{@code turno} (V55, aditivo): el número del turno que acaba de cerrarse.
 * {@code baseToKeep} es el eco de la base usada: la declarada por el cajero,
 * o la {@code base_caja} del negocio, o 0.
 *
 * <p>{@code vendidoACredito} (plan de mayoristas F1.13, aditivo): lo vendido a
 * crédito en el turno, informativo. No suma al efectivo esperado ni a ninguna
 * diferencia: ese dinero está en cuentas por cobrar, no en el cajón.
 *
 * <p>{@code recaudoCarteraEfectivo} (F4.5, aditivo): abonos de cartera en efectivo
 * del turno (menos sus anulaciones). YA está sumado al efectivo esperado; se
 * informa aparte porque no es venta.
 */
public record CashierClosureResponse(
        String status,
        String message,
        Map<String, BigDecimal> shortages,
        BigDecimal baseToKeep,
        BigDecimal amountToDeposit,
        BigDecimal roundingAdjustment,
        Integer turno,
        BigDecimal vendidoACredito,
        BigDecimal recaudoCarteraEfectivo
) {}
