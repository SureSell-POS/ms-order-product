package com.suresell.orders.application.dto.request;
import com.suresell.orders.application.dto.dto.CashCountDetail;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/closures}.
 *
 * <p>{@code baseForNextDay} (V55): la base que el cajero deja para el turno
 * siguiente. Opcional: si viene, tiene que ser ≥ 0 y es la base del cierre;
 * si no viene, se usa {@code base_caja} de la sede por defecto; si tampoco
 * está configurada, 0. El POS ya lo mandaba y nadie lo leía: la base salía de
 * un cálculo por denominaciones que ningún negocio usaba.
 */
public record ExecuteClosureRequest(
        CashCountDetail cashDetail,
        BigDecimal countedCash,
        BigDecimal countedCard,
        BigDecimal countedNequi,
        BigDecimal countedQr,
        String notes,
        String sellerId,
        List<PettyCashExpenseRequest> pettyCashExpenses,
        BigDecimal baseForNextDay
) {
}
