package com.suresell.orders.shared.exception;

/**
 * Una operación que no se hace sobre un cliente desactivado (se reactiva antes).
 * Sale como 409 {@code CLIENTE_INACTIVO}: el dato está bien; lo que no encaja es
 * el estado del cliente.
 */
public class ClienteInactivoException extends RuntimeException {

    public static final String CODIGO = "CLIENTE_INACTIVO";

    public ClienteInactivoException(String documento) {
        super("El cliente " + documento + " está desactivado: reactívalo antes de asignarle una lista.");
    }

    /** {@code queCosa}: lo que no se puede hacer, en infinitivo («tomarle un pedido»). */
    public ClienteInactivoException(String documento, String queCosa) {
        super("El cliente " + documento + " está desactivado: reactívalo antes de " + queCosa + ".");
    }
}
