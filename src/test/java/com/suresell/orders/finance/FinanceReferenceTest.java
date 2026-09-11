package com.suresell.orders.finance;

import com.suresell.orders.application.dto.dto.CashCountDetail;
import com.suresell.orders.domain.service.CashflowCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Referencia "golden" de la lógica de dinero autoritativa (Java). Confirma los
 * valores exactos que el motor financiero en TypeScript (F2) debe reproducir al
 * centavo. Ver docs/30-offline-y-sync.md §6 y el spec TS finance.spec.ts.
 *
 * Usa la clase real CashflowCalculator; el descuento se calcula con las mismas
 * operaciones BigDecimal de DiscountHandler.applyDiscount.
 */
class FinanceReferenceTest {

    private final CashflowCalculator cash = new CashflowCalculator();

    private final CashCountDetail sample = new CashCountDetail(
            3, 4, 12, 20, 6, 5, 8, 3, 7, 10, 4);

    /** Réplica exacta de: baseAmount.multiply(pct).divide(100, 2, HALF_UP). */
    private BigDecimal discount(String base, String pct) {
        return new BigDecimal(base).multiply(new BigDecimal(pct))
                .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
    }

    @Test
    void efectivoTotalContado_referencia() {
        assertEquals(0, cash.calculateTotalCash(sample).compareTo(new BigDecimal("992100")));
    }

    // `baseDiaSiguiente_referencia` se fue con `calculateBaseForNextDay` (V55):
    // la base ya no se calcula por denominaciones; la configura el negocio
    // (`sites.base_caja`) y la declara el cajero al cerrar. Si el motor TS
    // conserva su vector de base, ya no tiene contraparte en Java.

    @Test
    void descuentos_referencia() {
        assertEquals(0, discount("46700", "10").compareTo(new BigDecimal("4670.00")));
        assertEquals(0, discount("25900", "15").compareTo(new BigDecimal("3885.00")));
        assertEquals(0, discount("1", "12.5").compareTo(new BigDecimal("0.13")));   // HALF_UP de 0.125
        assertEquals(0, discount("333", "10").compareTo(new BigDecimal("33.30")));
        assertEquals(0, discount("105", "15").compareTo(new BigDecimal("15.75")));
        assertEquals(0, discount("155", "10").compareTo(new BigDecimal("15.50")));
    }
}
