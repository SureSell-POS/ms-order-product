package com.suresell.orders.shared.exception;

/**
 * El JWT no es de un administrador (claim {@code role}). Sale como 403 con el
 * contrato {@code {error, mensaje}}; el texto dice qué se intentó, para que el
 * cajero sepa a quién pedírselo.
 */
public class SoloAdministradorException extends RuntimeException {

    public SoloAdministradorException(String queCosa) {
        super("Solo un administrador puede " + queCosa);
    }
}
