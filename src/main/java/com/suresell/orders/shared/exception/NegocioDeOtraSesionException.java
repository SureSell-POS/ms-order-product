package com.suresell.orders.shared.exception;

/**
 * La venta que llega dice ser de un negocio y el token es de otro.
 *
 * <h3>Es integridad de datos, NO control de acceso</h3>
 *
 * El negocio en el que se escribe lo decide siempre el token: nadie puede
 * escribir con esto en un negocio al que no tiene acceso, con o sin esta
 * comprobación. Lo que impide es el MAL ETIQUETADO. Una misma persona entra al
 * POS con dos negocios en el mismo navegador; una venta guardada sin conexión
 * con el negocio A se sincroniza cuando ya hay sesión del B y quedaría
 * registrada como venta de B (bloqueo #4 de la fase 0). No confiar en esta
 * excepción como barrera de seguridad para nada más.
 *
 * <p>Sale como 409 con {@code codigo = NEGOCIO_DE_OTRA_SESION}. El POS nuevo
 * (feat/mayoristas-pos a5ce18a, e322540) aparta esas ventas antes de enviarlas
 * y, si le llega este código, devuelve la venta a pendiente sin gastar intento.
 * El POS viejo la reintentará con cada vuelta de la red hasta que se entre con
 * el negocio correcto: seguro, porque no se escribe nada, pero ruidoso.
 */
public class NegocioDeOtraSesionException extends RuntimeException {

    public static final String CODIGO = "NEGOCIO_DE_OTRA_SESION";

    private final String negocioDeLaVenta;

    public NegocioDeOtraSesionException(String negocioDeLaVenta) {
        super("Esta venta es del negocio «" + negocioDeLaVenta + "» y la caja tiene abierta otra cuenta. "
                + "Entra con la cuenta de ese negocio para enviarla: no se ha registrado en ningún otro.");
        this.negocioDeLaVenta = negocioDeLaVenta;
    }

    public String negocioDeLaVenta() {
        return negocioDeLaVenta;
    }
}
