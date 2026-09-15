package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VentasDelTurnoTest {

    @Test
    @DisplayName("F4.5c: los pagos de las MIXED se suman a su medio y NEQUI se pliega en QR")
    void porMedio() {
        Map<String, BigDecimal> m = VentasDelTurno.porMedio(
                List.<Object[]>of(new Object[] {"CASH", new BigDecimal("5000")}, new Object[] {"NEQUI", new BigDecimal("3000")},
                        new Object[] {"QR", new BigDecimal("1000")}, new Object[] {"CREDITO", new BigDecimal("7000")}),
                List.<Object[]>of(new Object[] {"CASH", new BigDecimal("10000")}, new Object[] {"CARD", new BigDecimal("14000")}));
        assertThat(m.get("CASH")).isEqualByComparingTo("15000");
        assertThat(m.get("CARD")).isEqualByComparingTo("14000");
        assertThat(m.get("QR")).isEqualByComparingTo("4000");
        assertThat(m.get("CREDITO")).isEqualByComparingTo("7000");
        assertThat(m).doesNotContainKey("NEQUI");
    }
}
