package com.suresell.orders.domain.service;
import com.suresell.orders.application.dto.dto.CashCountDetail;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

/**
 * Suma el conteo de billetes y monedas.
 *
 * <p>Aquí vivía también {@code calculateBaseForNextDay}, que decidía la base
 * del día siguiente por denominaciones (dos de 50k, diez de 20k, quince de
 * 10k…) con números escritos en el código. Se eliminó en V55: la base la
 * configura el negocio ({@code sites.base_caja}) y el cajero la declara al
 * cerrar; el sistema no la calcula.
 */
@Service
public class CashflowCalculator {
    private static final BigDecimal V_100K = BigDecimal.valueOf(100_000);
    private static final BigDecimal V_50K = BigDecimal.valueOf(50_000);
    private static final BigDecimal V_20K = BigDecimal.valueOf(20_000);
    private static final BigDecimal V_10K = BigDecimal.valueOf(10_000);
    private static final BigDecimal V_5K = BigDecimal.valueOf(5_000);
    private static final BigDecimal V_2K = BigDecimal.valueOf(2_000);
    public BigDecimal calculateTotalCash(CashCountDetail d) {
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(V_100K.multiply(BigDecimal.valueOf(d.bill100k())));
        total = total.add(V_50K.multiply(BigDecimal.valueOf(d.bill50k())));
        total = total.add(V_20K.multiply(BigDecimal.valueOf(d.bill20k())));
        total = total.add(V_10K.multiply(BigDecimal.valueOf(d.bill10k())));
        total = total.add(V_5K.multiply(BigDecimal.valueOf(d.bill5k())));
        total = total.add(V_2K.multiply(BigDecimal.valueOf(d.bill2k())));
        total = total.add(BigDecimal.valueOf(1000).multiply(BigDecimal.valueOf(d.coin1000())));
        total = total.add(BigDecimal.valueOf(500).multiply(BigDecimal.valueOf(d.coin500())));
        total = total.add(BigDecimal.valueOf(200).multiply(BigDecimal.valueOf(d.coin200())));
        total = total.add(BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(d.coin100())));
        total = total.add(BigDecimal.valueOf(50).multiply(BigDecimal.valueOf(d.coin50())));
        return total;
    }
}
