package com.suresell.orders.shared.exception;

/**
 * Un dato de la petición no vale, y se sabe cuál. Sale como 400
 * {@code DATOS_INVALIDOS} con {@code campo}, el contrato de error de la ola 4,
 * para que la pantalla lo pinte junto al campo.
 *
 * <p>Extiende {@link IllegalArgumentException} a propósito: quien ya capturaba
 * el genérico sigue capturándolo.
 */
public class DatoInvalidoException extends IllegalArgumentException {

    private final String campo;

    public DatoInvalidoException(String campo, String mensaje) {
        super(mensaje);
        this.campo = campo;
    }

    public String campo() {
        return campo;
    }
}
