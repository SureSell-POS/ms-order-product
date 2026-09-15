package com.suresell.orders.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderItem;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Escribe la intención de descontar inventario, en la MISMA transacción que la
 * venta.
 *
 * <h2>Por qué no es una llamada al servicio de inventario</h2>
 *
 * Porque una llamada entre servicios dentro de una venta solo tiene dos
 * finales, y los dos son malos: o se cae la venta cuando el otro servicio no
 * responde, o se traga el error. Este proyecto ya pagó el segundo:
 * {@code ExecuteDailyClosureUseCase} llamaba a {@code /qr-payments} sin JWT,
 * recibió 401 durante <b>tres semanas</b> y los cierres se cuadraron con el
 * valor manual del cajero sin que quedara rastro en ninguna parte.
 *
 * <p>Aquí el fallo tiene cuerpo: una fila {@code PENDIENTE} que envejece, que
 * se puede consultar y alarmar. Esa es la diferencia, no la latencia.
 *
 * <h2>La decisión incómoda, y cómo se revirtió</h2>
 *
 * Este INSERT va dentro de la transacción de la venta. La primera versión
 * decía: si falla, <b>la venta falla</b>, para que una venta sin intención no
 * dejara el inventario mintiendo en silencio.
 *
 * <p>El 2026-09-03 esa elección costó un día entero de órdenes de mesero: un
 * detalle de relojes (ver {@link #registrar}) hacía rebotar el INSERT y, con
 * él, la venta. Desde entonces la regla es la contraria: <b>la venta nunca se
 * pierde por el inventario</b>. Un fallo aquí sale como {@code ERROR} con la
 * etiqueta {@code [intencion-perdida]} y sin intención en la bandeja; lo que
 * no puede es convertirse en un 500 para quien está en la mesa.
 *
 * <p>El INSERT sigue siendo lo más simple que puede ser: una fila, con
 * valores en memoria, sin llamadas a nada. Y el interruptor
 * {@code inventario.intenciones.enabled} sigue naciendo <b>apagado</b>.
 */
@Service
public class RegistroDeIntencionDeInventario {

    private static final Logger log =
            LoggerFactory.getLogger(RegistroDeIntencionDeInventario.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean habilitado;

    public RegistroDeIntencionDeInventario(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${inventario.intenciones.enabled:false}") boolean habilitado) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.habilitado = habilitado;
    }

    /**
     * Registra la intención correspondiente a una venta ya guardada.
     *
     * @param orden la orden persistida, con su {@code idOrder} numérico ya asignado
     * @param items las líneas vendidas
     */
    public void registrar(Order orden, List<OrderItem> items) {
        if (!habilitado) {
            return;
        }
        if (items == null || items.isEmpty()) {
            // Una venta sin líneas no descuenta nada. No es un error: es una
            // orden vacía, y ya hay otras validaciones para eso.
            return;
        }

        String lineas;
        try {
            lineas = objectMapper.writeValueAsString(items.stream()
                    .map(i -> Map.of(
                            "producto_id", String.valueOf(i.getProductId()),
                            "cantidad", i.getQuantity()))
                    .toList());
        } catch (JsonProcessingException e) {
            // Serializar un mapa de dos claves no puede fallar por datos; si
            // falla, es un defecto del código y hay que verlo, no tragarlo.
            throw new IllegalStateException(
                    "no se pudo serializar las líneas de la orden " + orden.getIdOrder(), e);
        }

        // La clave de idempotencia lleva la orden y nada más: una venta produce
        // UNA intención. Si el POS reenvía la orden, la orden ya rebota antes
        // por su propia idempotencia; si algo llegara hasta aquí dos veces, el
        // índice único lo para (`ux_int_idempotencia`, y desde V70 también
        // `ux_int_orden_uuid`: una intención por venta, sea cual sea la clave).
        //
        // 🔴 POR UUID, NO POR `id_order` (plan de mayoristas F5.5a). El número de
        // orden vuelve a empezar en cada sede (`uq_orders_sede_consecutivo`, V28):
        // en un negocio con dos sedes, «orden-7» de la segunda chocaba con la de la
        // primera y se tomaba por repetida, y esa venta no descontaba inventario,
        // sin aviso. Las intenciones ya escritas conservan su clave «orden-N».
        if (orden.getUuidId() == null) {
            // No pasa: `orders.uuid_id` es NOT NULL. Si pasara, la venta no cae por
            // esto; queda a la vista como intención perdida, nunca con una clave
            // inventada que pudiera chocar con otra venta.
            log.error("[intencion-perdida] orden {} sin uuid: no se registra su intención de inventario", orden.getIdOrder());
            return;
        }
        String clave = "venta-" + orden.getUuidId();

        // `ocurrido_en` de la orden si existe -- una venta tomada sin cobertura
        // trae la hora del dispositivo. Que el movimiento nazca con la fecha
        // del HECHO y no con la del procesamiento es lo que evita descontar
        // inventario en el día equivocado.
        //
        // 🔴 EL RELOJ QUE FALTA LO PONE LA BASE, NO LA JVM. Y NUNCA EL FUTURO.
        //
        // El 2026-09-03, al encender el interruptor, la app de meseros no pudo
        // crear NINGUNA orden y el POS perdió cerca de la mitad. La causa:
        // `registrado_en` es `now()` de Postgres, que es el INICIO de la
        // transacción de la venta; aquí se ponía `OffsetDateTime.now()` de la
        // JVM, tomado después de guardar orden, ítems y seguimiento, o sea
        // siempre DESPUÉS de ese inicio. `ck_int_reloj` (ocurrido <= registrado)
        // rebotaba el cien por cien de las órdenes de mesero, que llegan sin
        // hora del dispositivo, y las del POS cada vez que el reloj del
        // terminal iba unos segundos por delante.
        //
        // Un solo reloj: si no hay hora del dispositivo, `now()` de la base. Y
        // si la hay pero está en el futuro para la base, se recorta a `now()`:
        // un hecho no puede haber ocurrido después de registrarse. Cuánto se
        // adelantaba ese terminal ya queda dicho en `orders.reloj_veredicto`.
        java.sql.Timestamp ocurrido = orden.getOcurridoEn() == null
                ? null
                : java.sql.Timestamp.from(orden.getOcurridoEn().toInstant());

        try {
            jdbc.update("""
                    INSERT INTO public.inventario_intenciones
                        (orden_id, orden_uuid, ocurrido_en, usuario_id, terminal_id,
                         lineas, idempotency_key)
                    VALUES (?, ?, LEAST(COALESCE(?, now()), now()), ?, ?, ?::jsonb, ?)""",
                    orden.getIdOrder(),
                    orden.getUuidId(),
                    ocurrido,
                    orden.getCreatedBy() == null ? null : String.valueOf(orden.getCreatedBy()),
                    orden.getTerminalId() == null ? null : orden.getTerminalId().toString(),
                    lineas,
                    clave);
        } catch (DuplicateKeyException e) {
            // Ya estaba registrada. Es el resultado que se buscaba, no un
            // fallo: reprocesar no debe duplicar (regla 12) y tampoco debe
            // romper la venta.
            log.info("La intención de inventario de la orden {} ya estaba registrada", clave);
        } catch (DataAccessException e) {
            // 🔴 LA VENTA NO SE PIERDE POR EL INVENTARIO.
            //
            // La decisión original era la contraria: «si falla, la venta
            // falla», para que el inventario nunca mintiera en silencio. El
            // 2026-09-03 costó un día entero de órdenes de mesero. Un cliente
            // en la mesa esperando vale más que una fila de la bandeja.
            //
            // Pero el fallo NO se traga: sale como ERROR con una etiqueta fija
            // para buscarlo, y `v_salud_de_intenciones` cuenta las ventas sin
            // intención. Lo que no se puede es convertirlo en un 500 al mesero.
            log.error("[intencion-perdida] La venta {} se registró SIN su intención de "
                    + "inventario; el stock no la descontará hasta que alguien la "
                    + "reponga. Causa:", clave, e);
        }
    }
}
