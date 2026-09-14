package com.suresell.orders.application.usecase;

import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderItem;
import com.suresell.orders.domain.service.GramaticaCanonica;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Encadena, del lado del servidor, las órdenes que llegan de la app de mesero.
 *
 * <h3>Por qué esta cadena la calcula el servidor y la del POS no</h3>
 *
 * La del POS existe porque sus eventos duermen en un almacenamiento local antes
 * de sincronizar: el encadenamiento demuestra que ese dato en reposo no se
 * alteró. La app de mesero <b>exige internet siempre y no persiste nada</b>. Sin
 * dato en reposo no hay nada que manipular, y el servidor ve cada orden en
 * tiempo real, así que puede encadenarla él.
 *
 * <p>Replicar el outbox del POS en Flutter sería trabajo grande y sin objeto.
 *
 * <h3>🔴 Y por eso las dos cadenas NO prueban lo mismo</h3>
 *
 * Esta prueba que <b>el registro del servidor es internamente consistente</b>.
 * No prueba que el dispositivo no alterara nada antes de enviar — no puede, el
 * dispositivo no guarda nada que alterar. Es una garantía más débil que la del
 * POS, y por eso cada fila lleva {@code cadena_origen} diciendo cuál es.
 *
 * <p>Colapsar las dos en las mismas columnas sin distinguirlas haría que la
 * garantía débil contaminara a la fuerte, y las dos valdrían lo que vale la
 * débil. Ver la cabecera de {@code V41}.
 *
 * <h3>De dónde sale el `seq`</h3>
 *
 * De {@code orders}, no de un contador. {@code V35:68-71} decidió explícitamente
 * que {@code terminals} no llevara {@code ultimo_seq} para no crear una fila
 * caliente en el camino del cobro, y ese argumento sigue valiendo. El índice
 * único {@code ux_orders_terminal_epoch_seq} (V36:364) cubre la consulta y es
 * además la garantía dura de que no haya dos eventos en la misma posición: un
 * chequeo aquí sería check-then-act, el índice no.
 *
 * <h3>Falla sin romper la venta</h3>
 *
 * Si algo va mal calculando la cadena, la orden se registra <b>sin</b> ella y se
 * deja constancia en el log. Perder una venta por no poder firmarla sería el
 * peor intercambio posible — mismo criterio que {@code UsuarioDeSistema} y que
 * el {@code catch} de {@code offline-order.repository.ts}.
 *
 * <p>La diferencia con lo de antes es que ahora <b>queda rastro</b>: una orden
 * sin cadena por avería y una sin cadena por cliente viejo se distinguen en el
 * log, aunque en la fila se vean igual.
 */
@Log4j2
@Component
@Profile("cloud")
public class CadenaDelServidor {

    /** Valor de {@code orders.cadena_origen} para lo que firma este servicio. */
    public static final String ORIGEN_SERVIDOR = "servidor";

    /** El epoch de una cadena del servidor es siempre 1: no hay estado que perder. */
    static final int EPOCH_DEL_SERVIDOR = 1;

    private final JdbcTemplate jdbc;

    public CadenaDelServidor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lo que hay que escribir en la fila. */
    public record Eslabon(long seq, String hashAnterior, String hashPropio) {}

    /**
     * Calcula la posición de esta orden en la cadena del terminal y la firma.
     *
     * @param terminalId UUID del dispositivo
     * @param ocurrido   instante declarado por el dispositivo
     * @return el eslabón, o {@code null} si no se pudo calcular (y entonces la
     *         orden se registra sin cadena)
     */
    public Eslabon encadenar(UUID terminalId, Instant ocurrido, Order orden, String tenantId) {
        try {
            // El último de ESTE terminal en ESTE epoch. Se piden las dos cosas a
            // la vez para no abrir dos ventanas donde otra orden pueda colarse:
            // la garantía dura contra eso es el índice único, no esta consulta.
            Map<String, Object> ultimo = jdbc.query(
                    "SELECT seq, hash_propio FROM orders "
                            + "WHERE tenant_id = ? AND terminal_id = ? AND epoch = ? "
                            + "  AND seq IS NOT NULL "
                            + "ORDER BY seq DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        return Map.of("seq", rs.getLong("seq"),
                                "hash", rs.getString("hash_propio") == null
                                        ? "" : rs.getString("hash_propio"));
                    },
                    tenantId, terminalId, EPOCH_DEL_SERVIDOR);

            long seq = ultimo == null ? 1L : ((Long) ultimo.get("seq")) + 1L;
            String hashAnterior = ultimo == null ? null : (String) ultimo.get("hash");
            if (hashAnterior != null && hashAnterior.isEmpty()) {
                // El anterior no tiene hash propio: es una orden del POS, que lo
                // guarda en el terminal. No se inventa un enlace que no existe.
                hashAnterior = null;
            }

            String hashPropio = GramaticaCanonica.hash(
                    new GramaticaCanonica.Identidad(
                            terminalId.toString(), EPOCH_DEL_SERVIDOR, seq,
                            "order_created", orden.getIdempotencyKey(), hashAnterior),
                    hechosDe(orden, ocurrido));

            return new Eslabon(seq, hashAnterior, hashPropio);
        } catch (RuntimeException e) {
            // Una venta no se pierde por no poder firmarla. Pero queda rastro:
            // sin este log, una orden sin cadena por avería y una de un cliente
            // viejo son el mismo dato.
            log.error("[cadena] no se pudo encadenar la orden {} del terminal {}; "
                    + "se registra SIN cadena:", orden.getIdempotencyKey(), terminalId, e);
            return null;
        }
    }

    /**
     * Proyecta la orden a los hechos económicos que cubre el hash.
     *
     * <p>Mismo subconjunto que {@code formaCanonica} del POS, y por las mismas
     * razones: entra lo que constituye el hecho económico y no entra lo
     * operativo. Ver la tabla de exclusiones en {@code hash-del-evento.ts}.
     *
     * <p><b>Fuera del hash por construcción</b> (plan de mayoristas F1.4, pareja
     * de F1.7 en el POS): {@code clienteDocumento}, {@code vendedorId} y
     * {@code condicionPago}, igual que {@code origen} y {@code pedidoId}. A quién
     * se le vendió, quién lo vendió y con qué condición no cambian el hecho
     * económico; {@code CadenaDelServidorExclusionesTest} lo fija.
     * {@code paymentMethod = CREDITO} sí entra, por {@code medioDePago}.
     *
     * <p>Visibilidad de paquete para esa prueba.
     */
    static GramaticaCanonica.Hechos hechosDe(Order orden, Instant ocurrido) {
        List<GramaticaCanonica.Linea> lineas = new ArrayList<>();
        if (orden.getItems() != null) {
            for (OrderItem it : orden.getItems()) {
                lineas.add(new GramaticaCanonica.Linea(
                        it.getProductId(), it.getQuantity(),
                        it.getUnitPrice(), it.getTotalPrice()));
            }
        }
        // Multipago: el servidor no conserva los splits en la entidad `Order`,
        // así que van vacíos. Es coherente con la gramática —`pagos:0`— y con lo
        // que la app de mesero manda hoy; si algún día manda splits, se añaden
        // aquí Y se sube VERSION_CANONICA en las dos implementaciones.
        return new GramaticaCanonica.Hechos(
                ocurrido,
                orden.getPaymentMethod(),
                orden.getSubtotal(),
                orden.getDiscountAmount(),
                orden.getTotal(),
                lineas,
                List.of());
    }

    /**
     * Escribe el eslabón en la fila. Se hace aparte del INSERT porque el hash
     * necesita el {@code idempotencyKey} y los importes ya calculados.
     *
     * <p><b>Actualiza también {@code reloj_veredicto}, y no es opcional.</b> La
     * orden se insertó sin fecha del dispositivo, así que nació con
     * {@code sin_fecha}. Al poner {@code ocurrido_en} aquí, ese veredicto pasa a
     * ser falso, y {@code ck_orders_reloj_coherente} (V36:315) lo rechaza:
     *
     * <pre>
     *   CHECK (reloj_veredicto IS NULL
     *          OR (ocurrido_en IS NULL     AND reloj_veredicto =  'sin_fecha')
     *          OR (ocurrido_en IS NOT NULL AND reloj_veredicto &lt;&gt; 'sin_fecha'))
     * </pre>
     *
     * <p>La primera versión de este método no lo actualizaba y la base tumbó
     * TODAS las órdenes con terminal con un 500. Fue la restricción haciendo
     * exactamente su trabajo: la incoherencia se detectó al escribir, no meses
     * después leyendo.
     *
     * @param veredicto qué dice el reloj del dispositivo, ya evaluado por
     *                  {@code CorduraDelRelojDelDispositivo}. Nunca
     *                  {@code sin_fecha} si {@code ocurrido} no es nulo
     */
    public void sellar(UUID uuidOrden, String tenantId, Eslabon eslabon, UUID terminalId,
                       Instant ocurrido, String veredicto) {
        jdbc.update(
                "UPDATE orders SET terminal_id = ?, epoch = ?, seq = ?, "
                        + "hash_anterior = ?, hash_propio = ?, cadena_origen = ?, "
                        + "ocurrido_en = ?, reloj_veredicto = ? "
                        + "WHERE uuid_id = ? AND tenant_id = ?",
                terminalId, EPOCH_DEL_SERVIDOR, eslabon.seq(),
                eslabon.hashAnterior(), eslabon.hashPropio(), ORIGEN_SERVIDOR,
                ocurrido == null ? null : java.sql.Timestamp.from(ocurrido),
                veredicto,
                uuidOrden, tenantId);
    }

    /** Convierte el texto que manda la app, sin romper la venta si viene mal. */
    public static UUID terminalDe(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(texto.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Idem para la fecha del dispositivo. */
    public static Instant ocurridoDe(String iso) {
        try {
            return GramaticaCanonica.instanteDe(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Para los tests: expone el importe tal como entra al hash. */
    static BigDecimal comoEntraAlHash(BigDecimal v) {
        return v;
    }
}
