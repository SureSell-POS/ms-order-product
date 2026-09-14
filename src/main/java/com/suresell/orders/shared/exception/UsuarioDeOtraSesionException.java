package com.suresell.orders.shared.exception;

/**
 * La venta dice que la vendió un vendedor y el token es de OTRO vendedor.
 *
 * <p>Pasa sin que nadie haga trampa: Ana vende sin conexión en un equipo
 * compartido, Pedro entra después y el POS sincroniza la venta de Ana con la
 * sesión de Pedro. Aceptarla la atribuiría mal; rechazarla con 400 la dejaría
 * FAILED y reintentándose en bucle. Sale como 409 con código propio para que el
 * POS la aparte sin gastar intentos hasta que entre su dueña (mismo patrón que
 * {@link NegocioDeOtraSesionException}; decisión de ECM, 2026-09-13).
 *
 * <p>Integridad de datos, no control de acceso: con token de cajero o admin el
 * {@code vendedorId} explícito del negocio se sigue aceptando.
 */
public class UsuarioDeOtraSesionException extends RuntimeException {

    public static final String CODIGO = "USUARIO_DE_OTRA_SESION";

    private final Long vendedorDeLaVenta;

    public UsuarioDeOtraSesionException(Long vendedorDeLaVenta) {
        super("Esta venta es de otro vendedor. Se enviará cuando entre con su cuenta; no se ha registrado a tu nombre.");
        this.vendedorDeLaVenta = vendedorDeLaVenta;
    }

    public Long vendedorDeLaVenta() {
        return vendedorDeLaVenta;
    }
}
