package com.suresell.orders.domain.model;

import java.util.List;

/**
 * Estado de la TIRILLA de una venta (V52, «cobrado, no impreso»).
 *
 * <p>Es un documento distinto de la comanda: {@code Order.isPrinted} dice que
 * la comanda salió de la cola de cocina, y no se reutiliza. Aquí se habla del
 * recibo que se le entrega al cliente.
 *
 * <p>Los valores son los del CHECK de la base ({@code ck_orders_recibo_estado});
 * si uno cambia, cambia en los dos sitios. Los motivos no tienen CHECK: son el
 * vocabulario acordado con el POS, y se validan aquí para que un motivo mal
 * escrito no se cuele como si fuera uno real.
 */
public final class ReciboDeVenta {

    public static final String NO_SOLICITADO = "no_solicitado";
    public static final String ENVIADO = "enviado";
    public static final String CONFIRMADO = "confirmado";
    public static final String NO_IMPRESO = "no_impreso";
    public static final String DESCARTADO = "descartado";

    public static final List<String> ESTADOS = List.of(
            NO_SOLICITADO, ENVIADO, CONFIRMADO, NO_IMPRESO, DESCARTADO);

    public static final List<String> MOTIVOS = List.of(
            "agente_apagado", "navegador_pide_permiso", "agente_no_responde",
            "impresora_sin_conexion", "en_cola", "error_agente", "dialogo_cancelado");

    private ReciboDeVenta() {
    }

    /** Un estado que no está en la lista es un error del cliente (400), no un 500 del CHECK. */
    public static String exigirEstado(String estado) {
        if (estado == null || !ESTADOS.contains(estado)) {
            throw new IllegalArgumentException(
                    "Estado de recibo inválido: '" + estado + "'. Use " + String.join(" | ", ESTADOS));
        }
        return estado;
    }

    /** Nulo o vacío es «sin motivo»; cualquier otra cosa tiene que ser del vocabulario. */
    public static String normalizarMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return null;
        }
        if (!MOTIVOS.contains(motivo)) {
            throw new IllegalArgumentException(
                    "Motivo de recibo inválido: '" + motivo + "'. Use " + String.join(" | ", MOTIVOS));
        }
        return motivo;
    }
}
