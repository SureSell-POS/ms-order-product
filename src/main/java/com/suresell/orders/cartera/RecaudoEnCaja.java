package com.suresell.orders.cartera;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F4.5: lo que la cartera metió al cajón en la ventana de un turno. Suma al
 * efectivo esperado del cierre; nunca a las ventas.
 *
 * <ul>
 *   <li>Solo {@code EFECTIVO}: una transferencia, un QR o un cheque no están en el cajón.</li>
 *   <li>Sin {@code liquidacion_id}: el efectivo de ruta se liquida aparte (F6), no en caja.</li>
 *   <li>Una anulación registrada en la ventana RESTA (el dinero sale del cajón o
 *       nunca entró). Un turno ya cerrado no se toca: si el recibo era de un turno
 *       anterior, la corrección cae en el turno en que se anula, igual que el
 *       patrón ANULAR.</li>
 *   <li>La ventana es la del cierre ({@code registrado_en} en hora de Bogotá, ambos
 *       extremos incluidos, como las ventas).</li>
 *   <li>Por negocio y no por sede: el cierre de caja es por negocio y turno (V55).</li>
 * </ul>
 *
 * <p>F4.12: la devolución de saldo a favor en efectivo SALE del cajón. Se informa
 * aparte ({@link #devolucionesDeSaldoAFavorEnEfectivoEntre}) y resta del efectivo
 * esperado, con la misma ventana. Un egreso no se anula (la tabla solo anexa).
 */
@Component
@Profile("cloud")
public class RecaudoEnCaja {

    private final JdbcTemplate jdbc;

    public RecaudoEnCaja(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal efectivoEntre(String negocio, LocalDateTime desde, LocalDateTime hasta) {
        if (negocio == null || desde == null || hasta == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN r.anula_recibo_id IS NULL THEN r.monto ELSE -r.monto END), 0)
                  FROM recibos_de_caja r
                  LEFT JOIN recibos_de_caja o ON o.tenant_id = r.tenant_id AND o.id = r.anula_recibo_id
                 WHERE r.tenant_id = ?
                   AND r.medio = 'EFECTIVO'
                   AND COALESCE(o.liquidacion_id, r.liquidacion_id) IS NULL
                   AND r.registrado_en >= (?::timestamp AT TIME ZONE 'America/Bogota')
                   AND r.registrado_en <= (?::timestamp AT TIME ZONE 'America/Bogota')""",
                BigDecimal.class, negocio, Timestamp.valueOf(desde), Timestamp.valueOf(hasta));
    }

    /**
     * F4.12: de lo que {@link #efectivoEntre} suma, la parte que quedó como saldo a favor al registrar el recibo (su monto
     * menos lo que se aplicó a facturas en ese momento). YA está dentro del recaudo: es informativa, para su propia línea
     * del cierre. Con las mismas reglas: solo EFECTIVO, sin liquidación, y la anulación resta el excedente del original.
     */
    public BigDecimal recibidoComoSaldoAFavorEnEfectivoEntre(String negocio, LocalDateTime desde, LocalDateTime hasta) {
        if (negocio == null || desde == null || hasta == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN r.anula_recibo_id IS NULL THEN 1 ELSE -1 END
                                    * (COALESCE(o.monto, r.monto) - COALESCE(ap.aplicado, 0))), 0)
                  FROM recibos_de_caja r
                  LEFT JOIN recibos_de_caja o ON o.tenant_id = r.tenant_id AND o.id = r.anula_recibo_id
                  LEFT JOIN LATERAL (SELECT sum(a.monto) AS aplicado FROM cartera_aplicaciones a
                                      WHERE a.tenant_id = r.tenant_id AND a.recibo_id = COALESCE(o.id, r.id)
                                        AND a.regla IN ('MAS_ANTIGUA_PRIMERO', 'ELEGIDA_POR_USUARIO')) ap ON true
                 WHERE r.tenant_id = ?
                   AND r.medio = 'EFECTIVO'
                   AND COALESCE(o.liquidacion_id, r.liquidacion_id) IS NULL
                   AND r.registrado_en >= (?::timestamp AT TIME ZONE 'America/Bogota')
                   AND r.registrado_en <= (?::timestamp AT TIME ZONE 'America/Bogota')""",
                BigDecimal.class, negocio, Timestamp.valueOf(desde), Timestamp.valueOf(hasta));
    }

    public BigDecimal devolucionesDeSaldoAFavorEnEfectivoEntre(String negocio, LocalDateTime desde, LocalDateTime hasta) {
        if (negocio == null || desde == null || hasta == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(e.monto), 0)
                  FROM egresos_de_cartera e
                 WHERE e.tenant_id = ?
                   AND e.medio = 'EFECTIVO'
                   AND e.registrado_en >= (?::timestamp AT TIME ZONE 'America/Bogota')
                   AND e.registrado_en <= (?::timestamp AT TIME ZONE 'America/Bogota')""",
                BigDecimal.class, negocio, Timestamp.valueOf(desde), Timestamp.valueOf(hasta));
    }
}
