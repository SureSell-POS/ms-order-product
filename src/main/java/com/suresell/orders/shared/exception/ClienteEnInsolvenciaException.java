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

    public ClienteEnInsolvenciaException(String clienteDocumento, LocalDate desde) {
        super("El cliente " + clienteDocumento + " está en proceso de insolvencia"
                + (desde == null ? "" : " desde el " + String.format("%02d/%02d/%04d",
                        desde.getDayOfMonth(), desde.getMonthValue(), desde.getYear()))
                + ": no se le vende a crédito. La venta no se registró.");
        this.clienteDocumento = clienteDocumento;
    }

    public String clienteDocumento() {
        return clienteDocumento;
    }
}
