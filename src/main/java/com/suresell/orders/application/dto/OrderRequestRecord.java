package com.suresell.orders.application.dto;
import com.suresell.orders.application.dto.OrderItemRequestRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
/**
 * Solicitud de creación de orden.
 *
 * <h3>Por qué hay una lista explícita de campos ignorados</h3>
 *
 * Al activar {@code FAIL_ON_UNKNOWN_PROPERTIES} (V36) apareció que el POS manda
 * <b>ocho campos que este DTO no declara</b>. Estaban siendo descartados en
 * silencio desde siempre — el mismo defecto que dejó la fecha del dispositivo
 * perdiéndose durante meses.
 *
 * <p>Sin esta anotación, activar la validación estricta devolvería <b>400 en
 * todas las ventas del POS</b>. Con ella, el descarte pasa de accidental a
 * deliberado: estos siete se toleran por compatibilidad y cualquier campo
 * <i>nuevo</i> que no esté declarado falla ruidosamente.
 *
 * <p>Los siete, y por qué no se aceptan:
 *
 * <ul>
 *   <li>{@code subtotal}, {@code discountAmount} — los calcula el servidor
 *       ({@code OrderHandler.java:198-204}). Aceptar los del cliente permitiría
 *       a un POS manipulado fijar el importe de su propia venta.</li>
 *   <li>{@code status}, {@code synced}, {@code idOrder} — estado del servidor. El
 *       consecutivo lo asigna un trigger de la base (V28:226).</li>
 *   <li>{@code tenantId} — sale del JWT, nunca del cuerpo. Aceptarlo del cliente
 *       sería dejar que una petición eligiera en qué negocio escribe.</li>
 * </ul>
 *
 * <p>Dos de los ocho <b>sí se aprovechan</b>, y ninguno de los dos manda:
 * {@code createdAt} (ver {@link #ocurridoEn}) y {@code total} (ver
 * {@link #totalDeclaradoPorElCliente}).
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({
        "subtotal", "discountAmount", "status", "synced", "idOrder", "tenantId"
})
@Schema(description = "Solicitud para crear o actualizar una orden")
public record OrderRequestRecord(
    @NotBlank(message="El nombre/color es obligatorio") 
    @Schema(description = "Color del pager asignado", example = "AMARILLO")
    String pagerColor, 
    @NotBlank(message="El número es obligatorio") 
    @Schema(description = "Número del pager asignado", example = "15")
    String pagerNumber, 
    @Schema(description = "Lista de productos incluidos en la orden")
    List<OrderItemRequestRecord> items, 
    @Schema(description = "Código de descuento opcional", example = "DESC10")
    String discountCode, 
    @NotBlank(message="El método de pago es obligatorio") 
    @Schema(description = "Método de pago", example = "CASH", allowableValues = {"CASH", "CARD", "NEQUI", "QR", "MIXED"})
    String paymentMethod,
    @Schema(description = "Multipago (F5): splits por medio; su suma debe igualar el total. Null/vacío = pago simple.")
    List<PaymentSplitRecord> payments,
    @Schema(description = "Clave de idempotencia generada por el cliente (N2/D1). Si llega una "
            + "orden con una clave ya registrada, se devuelve la existente en vez de crear otra. "
            + "Protege contra el doble POST del outbox del POS y contra reintentos por timeout.",
            example = "0f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10")
    String idempotencyKey,
    @Schema(description = "N2 — omite la validación de disponibilidad del rastreador. "
            + "Lo usa la app de MESEROS: el mesero lleva el pedido a la mesa, no entrega con "
            + "rastreador, así que varias órdenes suyas pueden convivir sin ocupar uno. "
            + "El POS de plazoleta NO lo envía y sigue validando.", example = "false")
    Boolean skipPagerCheck,
    @Schema(description = "N3 — Cuenta de mesa (modo Restaurante). Si viene, la orden nace en "
            + "estado `abierta` (consumo sin cobrar) y no ocupa rastreador. El cobro se hace "
            + "después sobre la SESIÓN completa, no sobre cada orden.",
            example = "3f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10")
    String tableSessionId,
    @Schema(description = "La comanda se IMPRIMIÓ en papel y la cocina ya preparó el pedido con "
            + "ese papel. La orden se registra para que quede la venta, pero NO entra a la cola "
            + "de cocina —ya se preparó— ni deja ocupado el rastreador o la mesa. Es lo que pasa "
            + "cuando el POS trabaja sin internet: se imprime la comanda y se cocina con ella; al "
            + "volver la conexión, mandarla otra vez a cocina duplicaría el plato y dejaría el "
            + "rastreador bloqueado sin que nadie pueda liberarlo.",
            example = "false")
    Boolean preparadoEnComanda,

    // ------------------------------------------------------------------
    // V36 — Procedencia temporal. TODOS OPCIONALES, y no es negociable:
    // hay terminales que pueden llevar semanas sin actualizar y tienen que
    // seguir vendiendo contra el contrato viejo. Un campo obligatorio aquí
    // dejaría sin vender a cualquier POS que no se haya actualizado el día
    // del despliegue.
    // ------------------------------------------------------------------

    /**
     * Cuándo ocurrió la venta según el reloj del DISPOSITIVO.
     *
     * <p><b>El alias {@code createdAt} es la pieza que hace que esto sirva desde
     * el primer día.</b> El POS lleva meses enviando ese campo
     * ({@code offline-order.service.ts:90}, {@code new Date().toISOString()} —
     * reloj del dispositivo) y el servidor lo descartaba en silencio.
     *
     * <p>Con el alias, <b>desplegar el backend basta</b>: todos los POS ya
     * instalados empiezan a poblar {@code ocurrido_en} sin actualizarse. No hace
     * falta esperar a T4 para dejar de perder la hora real de las ventas — y
     * como la historia es lo único que no se puede retrofitear, cada día que se
     * adelanta es un día de serie buena que no se pierde.
     *
     * <p>Si no viene, queda NULO. No se rellena con la hora del servidor: un
     * nulo honesto vale más que un dato inventado que después nadie puede
     * distinguir de uno real.
     */
    @com.fasterxml.jackson.annotation.JsonAlias("createdAt")
    @Schema(description = "V36 — Cuándo ocurrió la venta según el reloj del DISPOSITIVO, en ISO-8601 "
            + "con zona. Acepta también el nombre `createdAt`, que es como lo envía el POS actual.",
            example = "2026-08-20T13:05:11-05:00")
    java.time.OffsetDateTime ocurridoEn,

    @Schema(description = "V35 — UUID del terminal, generado por el cliente en su primer arranque. "
            + "Un terminal desconocido se da de alta, nunca se rechaza: rechazarlo rompería la "
            + "venta offline.",
            example = "3f2b8c4e-2f1a-4e2a-9d3b-6d5f1c9a7e10")
    String terminalId,

    @Schema(description = "V35 — Vida del terminal. Sube cuando el cliente detecta que perdió su "
            + "estado local y reinicia `seq`. Sin esto, ver `seq` volver a 1 sería indistinguible "
            + "de un ataque de repetición.",
            example = "1")
    Integer epoch,

    @Schema(description = "V36 — Secuencia monotónica del EVENTO del outbox dentro de "
            + "(terminal, epoch). Es del evento, no de la orden.",
            example = "1435")
    Long seq,

    @Schema(description = "V36 — SHA-256 hex (64 caracteres, minúsculas) del evento anterior de "
            + "ese terminal en ese epoch. Nulo en el primero de cada epoch. Definición del hash "
            + "en la cabecera de V36.",
            example = "9f2c...")
    String hashAnterior,

    /**
     * El total que el cliente dice que cobró. <b>SEÑAL, NO AUTORIDAD.</b>
     *
     * <p>El servidor calcula siempre el suyo ({@code OrderHandler.java:198-204})
     * y es el que se guarda. Este valor <b>no participa en ningún cálculo</b>:
     * solo se compara, y la diferencia va a {@code orders.total_discrepancia}.
     *
     * <p>Descartarlo sin compararlo, que es lo que se hacía, desperdiciaba una
     * señal que ya llegaba gratis. Un POS con el código alterado para inflar o
     * desinflar totales aparece en la primera consulta; un desfase de catálogo
     * entre terminal y servidor, también.
     *
     * <p>Se mapea desde {@code total}, que es como lo envía el POS
     * ({@code db.ts:31}).
     */
    @com.fasterxml.jackson.annotation.JsonProperty("total")
    @Schema(description = "V36 — Total que declara el cliente. NO se usa para calcular: el servidor "
            + "usa siempre el suyo. Solo se compara, y la diferencia se registra.",
            example = "23000.00")
    java.math.BigDecimal totalDeclaradoPorElCliente,
    /**
     * Ola 2 (mayorista). El documento del cliente al que se le vende. OPCIONAL:
     * el POS de plazoleta no lo manda y sigue igual. Con cliente, el precio de
     * cada línea lo resuelve el servidor con su lista (V45) y el del POS se
     * descarta; y si el medio de pago es CREDITO, la venta entra a su cartera.
     */
    String clienteDocumento
) {

    /**
     * Construye una solicitud SIN procedencia temporal — la forma que tenía este
     * record antes de V36.
     *
     * <p>Existe porque el constructor canónico es posicional y estaba invocado
     * en trece sitios: cada campo nuevo los rompía todos a la vez. Con esta
     * fábrica, un llamador que no tiene procedencia que declarar no se entera de
     * que el contrato creció.
     *
     * <p>Lo usan la app de meseros ({@code WaiterService}) y los tests. Que
     * {@code ocurridoEn} quede nulo es correcto y deliberado: esos llamadores no
     * declaran cuándo ocurrió la venta, y un nulo honesto vale más que la hora
     * del servidor disfrazada de hora del dispositivo.
     */
    public static OrderRequestRecord sinProcedencia(
            String pagerColor, String pagerNumber, List<OrderItemRequestRecord> items,
            String discountCode, String paymentMethod, List<PaymentSplitRecord> payments,
            String idempotencyKey, Boolean skipPagerCheck, String tableSessionId,
            Boolean preparadoEnComanda) {
        return new OrderRequestRecord(
                pagerColor, pagerNumber, items, discountCode, paymentMethod, payments,
                idempotencyKey, skipPagerCheck, tableSessionId, preparadoEnComanda,
                null, null, null, null, null, null, null);
    }

    /** Split de multipago: método + monto. */
    public record PaymentSplitRecord(String method, java.math.BigDecimal amount) {
    }
}
