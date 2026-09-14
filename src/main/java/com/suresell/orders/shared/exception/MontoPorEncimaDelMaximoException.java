package com.suresell.orders.shared.exception;

import java.math.BigDecimal;

/**
 * Un monto que supera lo permitido, con el máximo como número para que la
 * pantalla no tenga que leerlo del texto. Sale como 400 {@code DATOS_INVALIDOS}
 * con {@code campo} y {@code maximo} (F4.4: un abono mayor que la deuda).
 */
public class MontoPorEncimaDelMaximoException extends DatoInvalidoException {

    private final BigDecimal maximo;

    public MontoPorEncimaDelMaximoException(String campo, BigDecimal maximo, String mensaje) {
        super(campo, mensaje);
        this.maximo = maximo;
    }

    public BigDecimal maximo() {
        return maximo;
    }
}
