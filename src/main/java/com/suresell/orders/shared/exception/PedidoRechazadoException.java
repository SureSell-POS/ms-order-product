package com.suresell.orders.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Un pedido que no se crea o no se mueve (plan de mayoristas F5.3). Lleva su
 * estado HTTP, su {@code codigo} estable y, cuando el rechazo es de un campo, el
 * {@code campo}:
 *
 * <ul>
 *   <li>404 {@code NO_EXISTE}: el pedido no es de este negocio, o un vendedor pide uno que no es suyo.</li>
 *   <li>409 {@code TRANSICION_NO_PERMITIDA}: el catálogo no deja pasar del estado actual a ese.</li>
 *   <li>409 {@code REVERSA_PENDIENTE}: cancelar lo ya despachado exige la reversa de la venta (D7).</li>
 *   <li>409 {@code IDEMPOTENCIA_REUTILIZADA}: la clave ya se usó con otro pedido u otro evento.</li>
 *   <li>409 {@code AUTOVENTA_SIN_DESPACHO}: la autoventa despacha en la captura, y el despacho llega con F5.5.</li>
 *   <li>403 {@code SIN_USUARIO}: el token no corresponde a un usuario del negocio; el rastro no admite autor desconocido.</li>
 *   <li>400 {@code SIN_PRECIO} (F5.3f): una línea con cantidad tiene precio 0; lleva {@code productoId}.</li>
 *   <li>409 {@code VENTA_EN_CERO} (F5.3f): el despacho daría una venta de $0; no se crea venta ni deuda.</li>
 * </ul>
 */
public class PedidoRechazadoException extends RuntimeException {

    public static final String NO_EXISTE = "NO_EXISTE";
    public static final String TRANSICION_NO_PERMITIDA = "TRANSICION_NO_PERMITIDA";
    public static final String REVERSA_PENDIENTE = "REVERSA_PENDIENTE";
    public static final String IDEMPOTENCIA_REUTILIZADA = "IDEMPOTENCIA_REUTILIZADA";
    public static final String AUTOVENTA_SIN_DESPACHO = "AUTOVENTA_SIN_DESPACHO";
    public static final String SIN_USUARIO = "SIN_USUARIO";
    public static final String SIN_PRECIO = "SIN_PRECIO";
    public static final String VENTA_EN_CERO = "VENTA_EN_CERO";

    private final HttpStatus estado;
    private final String codigo;
    private final String productoId;

    public PedidoRechazadoException(HttpStatus estado, String codigo, String mensaje) {
        this(estado, codigo, mensaje, null);
    }

    public PedidoRechazadoException(HttpStatus estado, String codigo, String mensaje, String productoId) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
        this.productoId = productoId;
    }

    /** El producto sin precio (solo en {@code SIN_PRECIO}); null en los demás. */
    public String productoId() {
        return productoId;
    }

    public HttpStatus estado() {
        return estado;
    }

    public String codigo() {
        return codigo;
    }
}
