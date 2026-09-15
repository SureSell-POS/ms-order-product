package com.suresell.orders.shared.exception;

/**
 * Un recibo o una anulación que choca con lo que ya está escrito en la cartera
 * (F4.4). Sale como 409 con su {@code codigo} estable:
 *
 * <ul>
 *   <li>{@code IDEMPOTENCIA_REUTILIZADA}: la clave de un recibo ya se usó con otro
 *       cliente, monto o medio. No se escribe nada; el cliente tiene un defecto.</li>
 *   <li>{@code RECIBO_YA_ANULADO}: ese recibo ya tiene su anulación.</li>
 *   <li>{@code RECIBO_ES_ANULACION}: una anulación no se anula; se registra otro recibo.</li>
 *   <li>{@code VENTA_YA_RESUELTA}: esa venta a un insolvente ya tiene su decisión (F4.11).</li>
 * </ul>
 *
 * <p>Siempre con texto: el POS y el panel pintan {@code message} (ConflictosDeCarteraConMensajeTest).
 */
public class ConflictoDeCarteraException extends RuntimeException {

    public static final String IDEMPOTENCIA_REUTILIZADA = "IDEMPOTENCIA_REUTILIZADA";
    public static final String RECIBO_YA_ANULADO = "RECIBO_YA_ANULADO";
    public static final String RECIBO_ES_ANULACION = "RECIBO_ES_ANULACION";
    public static final String VENTA_YA_RESUELTA = "VENTA_YA_RESUELTA";
    public static final String SALDO_A_FAVOR_YA_DEVUELTO = "SALDO_A_FAVOR_YA_DEVUELTO";
    /** F4.13 (d): en liquidación el saldo a favor se entrega al liquidador. */
    public static final String BENEFICIARIO_DEBE_SER_EL_LIQUIDADOR = "BENEFICIARIO_DEBE_SER_EL_LIQUIDADOR";

    private final String codigo;

    public ConflictoDeCarteraException(String codigo, String mensaje) {
        super(conTexto(mensaje));
        this.codigo = codigo;
    }

    /** Quien pinta el 409 muestra este texto: sin él, el POS cae en un genérico que engaña. */
    private static String conTexto(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            throw new IllegalArgumentException("Un conflicto de cartera lleva texto para quien lo pinta.");
        }
        return mensaje;
    }

    public String codigo() {
        return codigo;
    }
}
