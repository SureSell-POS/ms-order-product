package com.suresell.orders.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Entity
@Table(name = "daily_closures")
@Data
@NoArgsConstructor
@AllArgsConstructor
@jakarta.persistence.EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
public class DailyClosure implements Persistable<UUID>, com.suresell.orders.multitenant.TenantOwned {

    @Transient
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");

    @Column(name = "tenant_id")
    private String tenantId;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    @Column(name = "opening_time", nullable = false)
    private LocalDateTime openingTime;

    @Column(name = "closing_time")
    private LocalDateTime closingTime;

    /**
     * V55: ya no es único. Un cierre es un turno y un día tiene los que haga
     * falta; la unicidad es (negocio, fecha, turno) y la pone la base.
     */
    @Column(name = "closure_date")
    private LocalDate closureDate;

    /**
     * V55: número del turno dentro del día (1, 2, 3…). Lo calcula el servidor
     * al cerrar: el último turno de hoy + 1. Las filas anteriores a V55 son
     * turno 1. El {@code ColumnDefault} es para el perfil local (SQLite con
     * {@code ddl-auto: update}), que no puede añadir una columna NOT NULL sin
     * valor por defecto a una tabla con filas; en Postgres la pone V55.
     */
    @org.hibernate.annotations.ColumnDefault("1")
    @Column(name = "turno", nullable = false)
    private Integer turno = 1;

    @Column(name = "total_expected_cash", precision = 15, scale = 2)
    private BigDecimal totalExpectedCash;

    @Column(name = "total_expected_card", precision = 15, scale = 2)
    private BigDecimal totalExpectedCard;


    @Column(name = "total_expected_qr", precision = 15, scale = 2)
    private BigDecimal totalExpectedQr;

    @Column(name = "total_counted_cash", precision = 15, scale = 2)
    private BigDecimal totalCountedCash;

    @Column(name = "total_counted_card", precision = 15, scale = 2)
    private BigDecimal totalCountedCard;


    @Column(name = "total_counted_qr", precision = 15, scale = 2)
    private BigDecimal totalCountedQr;

    @Column(name = "total_expected", precision = 15, scale = 2)
    private BigDecimal totalExpected;

    @Column(name = "total_counted", precision = 15, scale = 2)
    private BigDecimal totalCounted;

    @Column(name = "total_difference", precision = 15, scale = 2)
    private BigDecimal totalDifference;

    @Column(name = "difference_cash", precision = 15, scale = 2)
    private BigDecimal differenceCash;

    @Column(name = "difference_card", precision = 15, scale = 2)
    private BigDecimal differenceCard;


    @Column(name = "difference_qr", precision = 15, scale = 2)
    private BigDecimal differenceQr;

    @Column(name = "difference_amount", precision = 15, scale = 2)
    private BigDecimal differenceAmount;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "status_message", columnDefinition = "TEXT")
    private String statusMessage;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "base_balance_for_next_day", precision = 15, scale = 2)
    private BigDecimal baseBalanceForNextDay;

    @Column(name = "petty_cash_expenses", precision = 15, scale = 2)
    private BigDecimal pettyCashExpenses = BigDecimal.ZERO;

    @Column(name = "petty_cash_expenses_audit", columnDefinition = "TEXT")
    private String pettyCashExpensesAudit;

    @Column(name = "cash_count_audit", columnDefinition = "TEXT")
    private String cashCountAudit;

    @Column(name = "sales_of_day", precision = 15, scale = 2)
    private BigDecimal totalSales;

    // El ajuste por redondeo de las cuentas divididas NO se guarda acá: el
    // cierre lo SUMA AL VUELO desde `table_session_splits`, que es su única
    // fuente de verdad. Es determinista —una división ya cobrada no cambia—,
    // así que reabrir un cierre viejo da siempre el mismo número, y no hay un
    // total copiado que pueda quedar desincronizado del detalle que lo explica.

    // ------------------------------------------------------------------
    // V34 — Procedencia y confianza del monto de QR (reglas 5 y 6 de
    // LINEAMIENTOS_DESARROLLO_DATA_FIRST).
    //
    // `totalCountedQr` es un número; estos cuatro campos dicen de dónde salió y
    // qué tan fiable es. Sin ellos, un QR conciliado contra el registro del
    // administrador y uno cuadrado a mano tras un 401 se veían idénticos en la
    // base — que es como el fallo del 2026-07-30 pasó tres semanas inadvertido.
    //
    // NULL en los cierres anteriores a V34: no son reclasificables y no se les
    // inventa una fuente.
    // ------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "qr_fuente")
    private FuenteQr qrFuente;

    @Column(name = "qr_confianza")
    private Short qrConfianza;

    @Column(name = "qr_capturado_en")
    private java.time.OffsetDateTime qrCapturadoEn;

    /** Mensaje técnico del fallo. Campo de diagnóstico, NO analizable. */
    @Column(name = "qr_detalle", columnDefinition = "TEXT")
    private String qrDetalle;

    // Los TRES hechos del QR, cada uno en su columna. Ninguno se destruye para
    // producir otro; el que manda en el cuadre sigue siendo `totalCountedQr`.

    /** Suma de las ventas del día por QR. El único que existe siempre. */
    @Column(name = "qr_pos", precision = 15, scale = 2)
    private BigDecimal qrPos;

    /** Lo que tecleó el cajero. */
    @Column(name = "qr_manual_cajero", precision = 15, scale = 2)
    private BigDecimal qrManualCajero;

    /** Lo que devolvió `ms-core-app`, si devolvió algo. */
    @Column(name = "qr_conciliado_core", precision = 15, scale = 2)
    private BigDecimal qrConciliadoCore;

    /** Copia al cierre la procedencia del monto de QR, en un solo sitio. */
    public void registrarProcedenciaDelQr(ResultadoQr resultado) {
        this.qrFuente = resultado.fuente();
        this.qrConfianza = resultado.confianza();
        this.qrDetalle = resultado.detalle();
        this.qrCapturadoEn = java.time.OffsetDateTime.now(BOGOTA_ZONE);
        this.qrPos = resultado.qrPos();
        this.qrManualCajero = resultado.qrManual();
        this.qrConciliadoCore = resultado.qrConciliado();
    }

    //Campos para manter guardado offline

    @Transient
    private boolean isNewRecord = true;

    @Override
    public boolean isNew() {
        return isNewRecord;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNewRecord = false;
    }
}