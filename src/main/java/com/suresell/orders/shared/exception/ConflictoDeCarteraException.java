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
 */
public class ConflictoDeCarteraException extends RuntimeException {

    public static final String IDEMPOTENCIA_REUTILIZADA = "IDEMPOTENCIA_REUTILIZADA";
    public static final String RECIBO_YA_ANULADO = "RECIBO_YA_ANULADO";
    public static final String RECIBO_ES_ANULACION = "RECIBO_ES_ANULACION";
    public static final String VENTA_YA_RESUELTA = "VENTA_YA_RESUELTA";
    public static final String SALDO_A_FAVOR_YA_DEVUELTO = "SALDO_A_FAVOR_YA_DEVUELTO";

    private final String codigo;

    public ConflictoDeCarteraException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
