package com.suresell.orders.shared.exception;

/**
 * V55: dos cierres del mismo turno a la vez (misma fecha y turno). El primero
 * quedó guardado; este es un reintento o una segunda terminal. Sale como 409
 * con el texto del contrato, sin {@code alreadyClosed}.
 */
public class TurnoYaCerradoException extends RuntimeException {

    public static final String TEXTO = "Ese turno ya se cerró; recarga para ver el turno actual";

    public TurnoYaCerradoException() {
        super(TEXTO);
    }
}
