package com.suresell.orders.application.usecase;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Las ventas de un turno por medio de pago, calculadas UNA vez para el preview y para el cierre (plan de
 * mayoristas, cierre imprimible). Antes el preview sumaba solo {@code orders.payment_method} y descartaba las
 * ventas MIXED, mientras el cierre sumaba sus pagos por medio: con una venta mixta en el turno, el esperado que
 * veía el cajero no era el que cuadraba el cierre.
 *
 * <ul>
 *   <li>{@code totales}: filas [medio, suma] de las ventas no MIXED ({@code sumTotalsByPaymentMethodAndSeller}).</li>
 *   <li>{@code splits}: filas [medio, suma] de los pagos de las ventas MIXED ({@code sumSplitsByMethod}).</li>
 *   <li>NEQUI (histórico o de un APK viejo) se pliega dentro de QR: la categoría ya no existe (N2/6.6).</li>
 * </ul>
 *
 * <p>El cierre guardado no cambia para turnos sin MIXED: la consulta agrupa solo por medio, así que el {@code put}
 * del cálculo anterior y el {@code merge} de este dan lo mismo (verificado por ECM al aceptar F4.5c).
 */
public final class VentasDelTurno {

    private VentasDelTurno() {
    }

    public static Map<String, BigDecimal> porMedio(List<Object[]> totales, List<Object[]> splits) {
        Map<String, BigDecimal> medios = new HashMap<>();
        for (Object[] fila : totales == null ? List.<Object[]>of() : totales) {
            if (fila[0] != null) {
                medios.merge(String.valueOf(fila[0]), fila[1] == null ? BigDecimal.ZERO : new BigDecimal(fila[1].toString()), BigDecimal::add);
            }
        }
        for (Object[] fila : splits == null ? List.<Object[]>of() : splits) {
            if (fila[0] != null) {
                medios.merge(String.valueOf(fila[0]), fila[1] == null ? BigDecimal.ZERO : new BigDecimal(fila[1].toString()), BigDecimal::add);
            }
        }
        BigDecimal nequi = medios.remove("NEQUI");
        if (nequi != null && nequi.signum() != 0) {
            medios.merge("QR", nequi, BigDecimal::add);
        }
        return medios;
    }
}
