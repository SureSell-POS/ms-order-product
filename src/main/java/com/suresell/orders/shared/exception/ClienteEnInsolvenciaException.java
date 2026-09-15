package com.suresell.orders.shared.exception;

import java.time.LocalDate;

/**
 * Venta a crédito a un cliente con {@code clientes.en_insolvencia_desde} ya
 * cumplida (Ley 2445 de 2025 / Ley 1116 de 2006): no se le da nuevo crédito.
 *
 * <p>Sale como 409 con {@code codigo = CLIENTE_EN_INSOLVENCIA} y
 * {@code clienteDocumento}. Quien más la va a recibir es el outbox del POS: una
 * venta hecha sin red a un cliente que entró en insolvencia después de la
 * última caché. Es una venta física ya entregada; el POS la aparta con este
 * motivo, no la pierde ni la reintenta. No se escribe nada: ni orden ni débito.
 *
 * <p>La regla vive en la base (V65, {@code fn_venta_a_credito}); esto la
 * adelanta para responder con código, y el manejador traduce también el P0001
 * del disparador si la carrera lo deja pasar.
 */
public class ClienteEnInsolvenciaException extends RuntimeException {

    public static final String CODIGO = "CLIENTE_EN_INSOLVENCIA";

    private final String clienteDocumento;

    /** TEXTOS §B9 (F4.13 c): la venta a crédito sin el crédito después del inicio habilitado. */
    static final String VENTA_SIN_CREDITO_HABILITADO =
            "Cliente en proceso de insolvencia: véndele de contado, o habilita el crédito para este cliente.";
    /** TEXTOS §B10 (F4.13 c): en liquidación el crédito no se habilita. */
    static final String VENTA_EN_LIQUIDACION = "Cliente en liquidación: véndele de contado.";

    public ClienteEnInsolvenciaException(String clienteDocumento, LocalDate desde) {
        this(clienteDocumento, VENTA_SIN_CREDITO_HABILITADO);
    }

    /** F4.13 (c): el cliente está en liquidación; ahí no se habilita el crédito. */
    public static ClienteEnInsolvenciaException enLiquidacion(String clienteDocumento) {
        return new ClienteEnInsolvenciaException(clienteDocumento, VENTA_EN_LIQUIDACION);
    }

    private ClienteEnInsolvenciaException(String clienteDocumento, String mensaje) {
        super(mensaje);
        this.clienteDocumento = clienteDocumento;
    }

    /** F4.4: la insolvencia suspende también los cobros. El recibo no se registra. */
    public static ClienteEnInsolvenciaException alCobrar(String clienteDocumento, LocalDate desde) {
        return new ClienteEnInsolvenciaException(clienteDocumento, "El cliente " + clienteDocumento
                + " está en proceso de insolvencia" + desdeEl(desde)
                + ": los cobros están suspendidos. El recibo no se registró.");
    }

    private static String desdeEl(LocalDate desde) {
        return desde == null ? "" : " desde el " + String.format("%02d/%02d/%04d",
                desde.getDayOfMonth(), desde.getMonthValue(), desde.getYear());
    }

    public String clienteDocumento() {
        return clienteDocumento;
    }
}
