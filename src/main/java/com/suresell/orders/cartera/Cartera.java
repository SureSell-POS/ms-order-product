package com.suresell.orders.cartera;

import com.suresell.orders.shared.exception.ClienteEnInsolvenciaException;
import com.suresell.orders.shared.exception.ConflictoDeCarteraException;
import com.suresell.orders.shared.exception.DatoInvalidoException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cuentas por cobrar por documento (plan de mayoristas F4.4, contrato §7.4).
 *
 * <h3>Las reglas que no se negocian</h3>
 * <ul>
 *   <li><b>El saldo sale del libro</b> ({@code v_cartera_por_documento}, V64), nunca
 *       de {@code total_debt}. {@code total_debt} se sigue moviendo porque lo lee el
 *       panel viejo de core.</li>
 *   <li><b>R16:</b> el abono es {@code CREDIT} con {@code payment_method} NULL y la
 *       anulación un {@code DEBIT} con el recibo de anulación; el medio real vive en
 *       {@code recibos_de_caja.medio}. La base lo impone con dos CHECK (V64).</li>
 *   <li>Anular es otro recibo, nunca UPDATE ni DELETE (la base no da esos permisos).</li>
 *   <li>La edad y la mora se calculan al leer; no hay planificador.</li>
 *   <li>El negocio va escrito en cada consulta: RLS es el suelo, no la regla.</li>
 * </ul>
 */
@Service
public class Cartera {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    public static final Set<String> MEDIOS = Set.of("EFECTIVO", "TRANSFERENCIA", "BRE_B", "QR", "TARJETA", "CHEQUE");
    public static final Set<String> MOTIVOS_DE_ANULACION =
            Set.of("ERROR_DE_MONTO", "CHEQUE_DEVUELTO", "DUPLICADO", "APLICADO_A_OTRO_CLIENTE");
    public static final List<String> EDADES = List.of(
            "SIN_PLAZO_PACTADO", "CORRIENTE", "1_30", "31_60", "61_90", "91_180", "181_360", "MAS_360");
    static final String MAS_ANTIGUA_PRIMERO = "MAS_ANTIGUA_PRIMERO";
    static final String ELEGIDA_POR_USUARIO = "ELEGIDA_POR_USUARIO";
    /** `debt_transactions.amount` es NUMERIC(10,2) (V28). */
    static final BigDecimal MONTO_MAXIMO = new BigDecimal("99999999.99");
    static final String NO_EXISTE = "Ese cliente no existe en el negocio.";

    private final JdbcTemplate jdbc;

    public Cartera(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Quién pide: su negocio, su rol y su id de {@code users}. */
    public record Quien(String negocio, String rol, Long usuarioId) {
        boolean esVendedor() {
            return "vendedor".equals(rol);
        }
    }

    // ------------------------------------------------------------------ lectura

    private static final String RESUMEN_DEL_CLIENTE = """
            SELECT v.cliente_documento, v.nombre, v.cupo, v.saldo, v.vencido, v.factura_mas_vieja_vence_el,
                   v.dias_vencido_max, v.excede_cupo, v.en_insolvencia_desde, v.vendedor_id, u.nombre AS vendedor
              FROM v_cartera_por_cliente v
              LEFT JOIN users u ON u.tenant_id = v.tenant_id AND u.id = v.vendedor_id
             WHERE v.tenant_id = ?""";

    /** GET /api/cartera/clientes: resumen por cliente; un vendedor ve solo los suyos. */
    public List<Map<String, Object>> clientes(Quien quien, String edad, Long vendedorId, String q) {
        StringBuilder sql = new StringBuilder(RESUMEN_DEL_CLIENTE);
        List<Object> args = new ArrayList<>(List.of(quien.negocio()));
        // Long y no long: con un primitivo en el ternario, un vendedorId nulo se desempaqueta y revienta.
        Long vendedor = quien.esVendedor() ? Long.valueOf(idDelVendedor(quien)) : vendedorId;
        if (vendedor != null) {
            sql.append(" AND v.vendedor_id = ?");
            args.add(vendedor);
        }
        if (edad != null && !edad.isBlank()) {
            String tramo = edad.trim().toUpperCase(Locale.ROOT);
            if (!EDADES.contains(tramo)) {
                throw new DatoInvalidoException("edad", "La edad es una de: " + String.join(", ", EDADES) + ".");
            }
            sql.append(" AND EXISTS (SELECT 1 FROM v_cartera_por_documento d"
                    + " WHERE d.tenant_id = v.tenant_id AND d.cliente_documento = v.cliente_documento"
                    + " AND d.saldo > 0 AND d.edad = ?)");
            args.add(tramo);
        }
        if (q != null && !q.isBlank()) {
            String patron = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (v.nombre ILIKE ? OR v.cliente_documento ILIKE ?)");
            args.add(patron);
            args.add(patron);
        }
        sql.append(" ORDER BY v.vencido DESC, v.saldo DESC, v.nombre");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(Cartera::resumen).toList();
    }

    private static Map<String, Object> resumen(Map<String, Object> f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", f.get("cliente_documento"));
        r.put("nombre", f.get("nombre"));
        r.put("cupo", f.get("cupo"));
        r.put("saldo", f.get("saldo"));
        r.put("vencido", f.get("vencido"));
        r.put("facturaMasViejaVenceEl", fecha(f.get("factura_mas_vieja_vence_el")));
        r.put("diasVencidoMax", f.get("dias_vencido_max"));
        r.put("excedeCupo", f.get("excede_cupo"));
        r.put("enInsolvenciaDesde", fecha(f.get("en_insolvencia_desde")));
        r.put("vendedorId", f.get("vendedor_id"));
        r.put("vendedor", f.get("vendedor"));
        return r;
    }

    /**
     * GET /api/cartera/clientes/{documento}/estado-de-cuenta: las facturas con saldo
     * (todas, sin importar la fecha) más las pagadas del periodo, los recibos del
     * periodo con sus aplicaciones, y la frase para mandar por WhatsApp.
     */
    public Map<String, Object> estadoDeCuenta(Quien quien, String documento, LocalDate desde, LocalDate hasta) {
        LocalDate hoy = LocalDate.now(BOGOTA);
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? fin.minusDays(90) : desde;
        if (inicio.isAfter(fin)) {
            throw new DatoInvalidoException("desde", "«desde» no puede ser posterior a «hasta».");
        }
        Map<String, Object> cliente = resumenVisible(quien, documento);
        // Solo aquí y no en la lista: es dato personal, y el panel lo necesita para el enlace de WhatsApp.
        cliente.put("whatsapp", jdbc.queryForList("SELECT whatsapp FROM clientes WHERE tenant_id = ? AND documento = ?",
                String.class, quien.negocio(), cliente.get("clienteDocumento")).stream().findFirst().orElse(null));

        List<Map<String, Object>> documentos = jdbc.queryForList("""
                SELECT d.debito_tx_id, d.order_uuid, d.fecha, d.vence_el, d.monto, d.aplicado, d.saldo,
                       d.dias_vencido, d.edad
                  FROM v_cartera_por_documento d
                 WHERE d.tenant_id = ? AND d.cliente_documento = ?
                   AND (d.saldo > 0 OR d.fecha BETWEEN ? AND ?)
                 ORDER BY d.fecha, d.debito_tx_id""",
                quien.negocio(), cliente.get("clienteDocumento"), java.sql.Date.valueOf(inicio), java.sql.Date.valueOf(fin))
                .stream().map(f -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("debitoTxId", f.get("debito_tx_id"));
                    r.put("orderUuid", f.get("order_uuid"));
                    r.put("fecha", fecha(f.get("fecha")));
                    r.put("venceEl", fecha(f.get("vence_el")));
                    r.put("monto", f.get("monto"));
                    r.put("aplicado", f.get("aplicado"));
                    r.put("saldo", f.get("saldo"));
                    r.put("diasVencido", f.get("dias_vencido"));
                    r.put("edad", f.get("edad"));
                    return r;
                }).toList();

        List<UUID> ids = jdbc.queryForList("""
                SELECT r.id FROM recibos_de_caja r
                 WHERE r.tenant_id = ? AND r.cliente_documento = ?
                   AND (r.ocurrido_en AT TIME ZONE 'America/Bogota')::date BETWEEN ? AND ?
                 ORDER BY r.numero""",
                UUID.class, quien.negocio(), cliente.get("clienteDocumento"),
                java.sql.Date.valueOf(inicio), java.sql.Date.valueOf(fin));
        List<Map<String, Object>> recibos = new ArrayList<>();
        for (UUID id : ids) {
            recibos.add(recibo(quien.negocio(), id, false).orElseThrow());
        }

        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("cliente", cliente);
        estado.put("desde", inicio.toString());
        estado.put("hasta", fin.toString());
        estado.put("documentos", documentos);
        estado.put("recibos", recibos);
        estado.put("frase", frase(quien.negocio(), cliente, hoy));
        return estado;
    }

    /** «En una frase», para WhatsApp. Sin enlaces ni datos internos. */
    String frase(String negocio, Map<String, Object> cliente, LocalDate hoy) {
        String nombreNegocio = jdbc.queryForList("SELECT name FROM tenants WHERE id = ?", String.class, negocio)
                .stream().findFirst().orElse("nosotros");
        BigDecimal saldo = decimal(cliente.get("saldo"));
        BigDecimal vencido = decimal(cliente.get("vencido"));
        String fechaHoy = hoy.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String saludo = "Hola " + cliente.get("nombre") + ", a hoy " + fechaHoy;
        if (saldo.signum() <= 0) {
            return saludo + " no tiene saldo pendiente con " + nombreNegocio + ". ¡Gracias!";
        }
        StringBuilder f = new StringBuilder(saludo).append(" su saldo con ").append(nombreNegocio)
                .append(" es de ").append(pesos(saldo));
        if (vencido.signum() > 0) {
            f.append(", de los cuales ").append(pesos(vencido)).append(" están vencidos");
            Object dias = cliente.get("diasVencidoMax");
            if (dias != null) {
                f.append(" (la factura más atrasada lleva ").append(dias).append(dias.toString().equals("1") ? " día)" : " días)");
            }
        } else {
            f.append(", al día");
        }
        return f.append(". ¡Gracias!").toString();
    }

    /** GET /api/cartera/resumen: por cobrar, vencido, por edad y los que más deben. */
    public Map<String, Object> resumenDelNegocio(Quien quien) {
        Map<String, Object> porEdad = new LinkedHashMap<>();
        for (String e : EDADES) {
            porEdad.put(e, BigDecimal.ZERO);
        }
        for (Map<String, Object> f : jdbc.queryForList("""
                SELECT d.edad, sum(d.saldo) AS saldo FROM v_cartera_por_documento d
                 WHERE d.tenant_id = ? AND d.saldo > 0 GROUP BY d.edad""", quien.negocio())) {
            porEdad.put((String) f.get("edad"), f.get("saldo"));
        }
        Map<String, Object> totales = jdbc.queryForMap("""
                SELECT COALESCE(sum(v.saldo), 0) AS por_cobrar, COALESCE(sum(v.vencido), 0) AS vencido,
                       count(*) FILTER (WHERE v.saldo > 0) AS clientes_con_saldo
                  FROM v_cartera_por_cliente v WHERE v.tenant_id = ?""", quien.negocio());
        List<Map<String, Object>> top = jdbc.queryForList(RESUMEN_DEL_CLIENTE
                        + " AND v.saldo > 0 ORDER BY v.saldo DESC, v.nombre LIMIT 10", quien.negocio())
                .stream().map(Cartera::resumen).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hoy", LocalDate.now(BOGOTA).toString());
        r.put("porCobrar", totales.get("por_cobrar"));
        r.put("vencido", totales.get("vencido"));
        r.put("clientesConSaldo", totales.get("clientes_con_saldo"));
        r.put("porEdad", porEdad);
        r.put("topDeudores", top);
        // F4.11: el aviso del tablero. Ventas de caja a clientes en insolvencia sin decisión.
        r.put("ventasAInsolventePorRevisar", jdbc.queryForObject("""
                SELECT count(*) FROM ventas_a_insolvente m
                 WHERE m.tenant_id = ?
                   AND NOT EXISTS (SELECT 1 FROM ventas_a_insolvente_resoluciones r
                                    WHERE r.tenant_id = m.tenant_id AND r.venta_a_insolvente_id = m.id)""",
                Long.class, quien.negocio()));
        return r;
    }

    /**
     * GET /api/cartera/clientes/{documento}/comportamiento-de-pago (plan de mayoristas F10.3):
     * cómo paga este cliente sus facturas, para el propio mayorista. Solo lectura, calculado
     * al leer desde el mismo libro que el estado de cuenta.
     *
     * <ul>
     *   <li>Una factura queda <b>pagada</b> el día (Bogotá) del recibo con el que sus
     *       aplicaciones llegan a su monto. Lo de un recibo anulado no cuenta.</li>
     *   <li><b>A tiempo</b>: pagada hasta su vencimiento. <b>Tarde</b>: pagada después. Una
     *       vencida sin pagar también cuenta en contra. Las que no vencen todavía y las que
     *       no tienen plazo pactado no entran en el porcentaje.</li>
     *   <li>Sin facturas que midan, el porcentaje es {@code null} («sin dato»), nunca 0.</li>
     *   <li>Un abono del panel viejo no dice qué factura pagó: se informa aparte como
     *       {@code abonosSinFacturaAsignada}, porque con él las facturas parecen menos pagadas.</li>
     * </ul>
     */
    public Map<String, Object> comportamientoDePago(Quien quien, String documento, LocalDate desde, LocalDate hasta) {
        LocalDate hoy = LocalDate.now(BOGOTA);
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? fin.minusDays(365) : desde;
        if (inicio.isAfter(fin)) {
            throw new DatoInvalidoException("desde", "«desde» no puede ser posterior a «hasta».");
        }
        Map<String, Object> cliente = resumenVisible(quien, documento);
        String doc = (String) cliente.get("clienteDocumento");
        List<Map<String, Object>> filas = jdbc.queryForList("""
                WITH facturas AS (
                    SELECT d.id, d.order_uuid, d.transaction_date AS fecha, d.vence_el, d.amount AS monto
                      FROM debt_transactions d
                      JOIN accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
                     WHERE d.tenant_id = ? AND ar.customer_document = ? AND d.type = 'DEBIT' AND d.recibo_id IS NULL
                       AND d.transaction_date BETWEEN ? AND ?
                ), abonos AS (
                    SELECT f.id, f.monto, (r.ocurrido_en AT TIME ZONE 'America/Bogota')::date AS dia,
                           sum(a.monto) OVER (PARTITION BY f.id ORDER BY r.ocurrido_en, r.numero, a.id) AS acumulado
                      FROM facturas f
                      JOIN cartera_aplicaciones a ON a.tenant_id = ? AND a.debito_tx_id = f.id
                      JOIN recibos_de_caja r ON r.tenant_id = a.tenant_id AND r.id = a.recibo_id
                     WHERE NOT EXISTS (SELECT 1 FROM recibos_de_caja x WHERE x.tenant_id = a.tenant_id AND x.anula_recibo_id = a.recibo_id)
                ), pagadas AS (
                    SELECT id, min(dia) AS pagada_el FROM abonos WHERE acumulado >= monto GROUP BY id
                )
                SELECT f.id, f.order_uuid, f.fecha, f.vence_el, f.monto, p.pagada_el
                  FROM facturas f LEFT JOIN pagadas p ON p.id = f.id
                 ORDER BY f.fecha DESC, f.id""", quien.negocio(), doc, java.sql.Date.valueOf(inicio), java.sql.Date.valueOf(fin),
                quien.negocio());

        int pagadas = 0;
        int aTiempo = 0;
        int tarde = 0;
        int vencidasSinPagar = 0;
        long diasDePago = 0;
        long diasDeAtraso = 0;
        Map<String, Integer> porEstado = new LinkedHashMap<>();
        for (String e : List.of("PAGADA_A_TIEMPO", "PAGADA_TARDE", "PAGADA_SIN_PLAZO", "VENCIDA_SIN_PAGAR", "POR_VENCER", "SIN_PLAZO_PENDIENTE")) {
            porEstado.put(e, 0);
        }
        List<Map<String, Object>> facturas = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            LocalDate fecha = ((java.sql.Date) f.get("fecha")).toLocalDate();
            LocalDate vence = f.get("vence_el") == null ? null : ((java.sql.Date) f.get("vence_el")).toLocalDate();
            LocalDate pagada = f.get("pagada_el") == null ? null : ((java.sql.Date) f.get("pagada_el")).toLocalDate();
            String estado;
            Long diasParaPagar = null;
            Long atraso = null;
            if (pagada != null) {
                pagadas++;
                diasParaPagar = java.time.temporal.ChronoUnit.DAYS.between(fecha, pagada);
                diasDePago += diasParaPagar;
                if (vence == null) {
                    estado = "PAGADA_SIN_PLAZO";
                } else if (!pagada.isAfter(vence)) {
                    estado = "PAGADA_A_TIEMPO";
                    aTiempo++;
                    atraso = 0L;
                } else {
                    estado = "PAGADA_TARDE";
                    tarde++;
                    atraso = java.time.temporal.ChronoUnit.DAYS.between(vence, pagada);
                    diasDeAtraso += atraso;
                }
            } else if (vence == null) {
                estado = "SIN_PLAZO_PENDIENTE";
            } else if (hoy.isAfter(vence)) {
                estado = "VENCIDA_SIN_PAGAR";
                vencidasSinPagar++;
                atraso = java.time.temporal.ChronoUnit.DAYS.between(vence, hoy);
            } else {
                estado = "POR_VENCER";
            }
            porEstado.merge(estado, 1, Integer::sum);
            if (facturas.size() < MAX_FACTURAS_DEL_COMPORTAMIENTO) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("orderUuid", f.get("order_uuid"));
                r.put("fecha", fecha.toString());
                r.put("venceEl", vence == null ? null : vence.toString());
                r.put("monto", f.get("monto"));
                r.put("pagadaEl", pagada == null ? null : pagada.toString());
                r.put("diasParaPagar", diasParaPagar);
                r.put("diasDeAtraso", atraso);
                r.put("estado", estado);
                facturas.add(r);
            }
        }
        int medibles = aTiempo + tarde + vencidasSinPagar;
        BigDecimal abonosSinAsignar = jdbc.queryForObject("""
                SELECT COALESCE(sum(d.amount), 0)
                  FROM debt_transactions d
                  JOIN accounts_receivable ar ON ar.tenant_id = d.tenant_id AND ar.id = d.account_id
                 WHERE d.tenant_id = ? AND ar.customer_document = ? AND d.type = 'CREDIT' AND d.recibo_id IS NULL
                   AND d.transaction_date BETWEEN ? AND ?""", BigDecimal.class,
                quien.negocio(), doc, java.sql.Date.valueOf(inicio), java.sql.Date.valueOf(fin));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        r.put("nombre", cliente.get("nombre"));
        r.put("desde", inicio.toString());
        r.put("hasta", fin.toString());
        r.put("facturas", filas.size());
        r.put("pagadas", pagadas);
        r.put("diasPromedioDePago", pagadas == 0 ? null
                : BigDecimal.valueOf(diasDePago).divide(BigDecimal.valueOf(pagadas), 1, RoundingMode.HALF_UP));
        r.put("porcentajeATiempo", medibles == 0 ? null
                : BigDecimal.valueOf(aTiempo * 100L).divide(BigDecimal.valueOf(medibles), 1, RoundingMode.HALF_UP));
        r.put("diasPromedioDeAtraso", tarde == 0 ? null
                : BigDecimal.valueOf(diasDeAtraso).divide(BigDecimal.valueOf(tarde), 1, RoundingMode.HALF_UP));
        r.put("porEstado", porEstado);
        r.put("abonosSinFacturaAsignada", abonosSinAsignar);
        r.put("detalle", facturas);
        r.put("detalleCompleto", filas.size() <= MAX_FACTURAS_DEL_COMPORTAMIENTO);
        return r;
    }

    /** Las más recientes primero; con más, el resumen sigue contando todas. */
    static final int MAX_FACTURAS_DEL_COMPORTAMIENTO = 200;

    // ------------------------------------------------------------------ recibos

    public record Aplicacion(UUID orderUuid, BigDecimal monto) {}

    public record NuevoRecibo(String clienteDocumento, BigDecimal monto, String medio, String referenciaMedio,
                              List<Aplicacion> aplicaciones, OffsetDateTime ocurridoEn, String idempotencyKey,
                              UUID liquidacionId, Long siteId) {}

    /** Resultado de registrar: el recibo y si ya existía (reintento). */
    public record Registro(Map<String, Object> recibo, boolean repetido) {}

    /**
     * POST /api/cartera/recibos. Todo en una transacción: el recibo con su número,
     * el CREDIT del libro, las aplicaciones y {@code total_debt}. Si algo falla, el
     * contador de recibos tampoco se movió (V64): sin huecos.
     *
     * <p>Idempotente por {@code (negocio, idempotencyKey)}: el reintento devuelve el
     * mismo recibo; la misma clave con otro cliente, monto o medio es un 409.
     */
    @Transactional
    public Registro registrarRecibo(Quien quien, NuevoRecibo r) {
        String negocio = quien.negocio();
        String documento = obligatorio(r.clienteDocumento(), "clienteDocumento", "Falta el documento del cliente.");
        String clave = obligatorio(r.idempotencyKey(), "idempotencyKey", "Falta la clave de idempotencia.");
        if (clave.length() > 100) {
            throw new DatoInvalidoException("idempotencyKey", "La clave de idempotencia tiene máximo 100 caracteres.");
        }
        BigDecimal monto = montoValido(r.monto(), "monto");
        String medio = r.medio() == null ? null : r.medio().trim().toUpperCase(Locale.ROOT);
        if (medio == null || !MEDIOS.contains(medio)) {
            throw new DatoInvalidoException("medio", "El medio es uno de: EFECTIVO, TRANSFERENCIA, BRE_B, QR, TARJETA, CHEQUE.");
        }
        OffsetDateTime ocurrido = r.ocurridoEn() == null ? OffsetDateTime.now(BOGOTA) : r.ocurridoEn();
        if (ocurrido.toInstant().isAfter(Instant.now().plusSeconds(300))) {
            throw new DatoInvalidoException("ocurridoEn", "El recibo no puede ser del futuro.");
        }

        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "recibo:" + negocio + ":" + clave);
        List<Map<String, Object>> previo = jdbc.queryForList("""
                SELECT id, cliente_documento, monto, medio FROM recibos_de_caja
                 WHERE tenant_id = ? AND idempotency_key = ?""", negocio, clave);
        if (!previo.isEmpty()) {
            Map<String, Object> p = previo.get(0);
            if (documento.equals(p.get("cliente_documento")) && decimal(p.get("monto")).compareTo(monto) == 0
                    && medio.equals(p.get("medio"))) {
                return new Registro(recibo(negocio, (UUID) p.get("id"), true).orElseThrow(), true);
            }
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.IDEMPOTENCIA_REUTILIZADA,
                    "Esa clave de idempotencia ya se usó para otro recibo (cliente, monto o medio distintos). "
                            + "No se registró nada.");
        }

        Map<String, Object> ficha = fichaVisible(quien, documento);
        LocalDate hoy = LocalDate.now(BOGOTA);
        // Insolvencia: se suspenden los cobros. Ley 1116 de 2006 (y Ley 2445 de 2025): los pagos
        // por fuera del proceso a deudas anteriores a su inicio son ineficaces. Anular sí se permite.
        Object insolvente = ficha == null ? null : ficha.get("en_insolvencia_desde");
        if (insolvente != null && !((java.sql.Date) insolvente).toLocalDate().isAfter(hoy)) {
            throw ClienteEnInsolvenciaException.alCobrar(documento, ((java.sql.Date) insolvente).toLocalDate());
        }

        String cuenta = cuentaBloqueada(negocio, documento)
                .orElseThrow(() -> new DatoInvalidoException("clienteDocumento", "Ese cliente no tiene cuenta por cobrar."));
        List<Map<String, Object>> vivas = facturasVivas(negocio, cuenta);
        BigDecimal deudaFacturas = vivas.stream().map(f -> decimal(f.get("saldo"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deudaLibro = saldoDelLibro(negocio, cuenta);
        BigDecimal debe = deudaFacturas.min(deudaLibro);
        if (monto.compareTo(debe) > 0) {
            // Sin anticipos ni saldo a favor en esta fase (ECM, 2026-09-14): el libro dejaría de cuadrar con la vista.
            BigDecimal maximo = debe.max(BigDecimal.ZERO);
            throw new com.suresell.orders.shared.exception.MontoPorEncimaDelMaximoException("monto", maximo,
                    "El cliente debe " + pesos(maximo) + "; no se puede abonar más.");
        }

        List<Object[]> reparto = repartir(vivas, monto, r.aplicaciones());
        String regla = r.aplicaciones() == null || r.aplicaciones().isEmpty() ? MAS_ANTIGUA_PRIMERO : ELEGIDA_POR_USUARIO;

        Map<String, Object> nuevo = jdbc.queryForMap("""
                INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, referencia_medio,
                                             cobrado_por, site_id, liquidacion_id, ocurrido_en, idempotency_key)
                VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, numero""",
                negocio, documento, monto, medio, recortarONulo(r.referenciaMedio()), quien.usuarioId(), r.siteId(),
                r.liquidacionId(), Timestamp.from(ocurrido.toInstant()), clave);
        UUID reciboId = (UUID) nuevo.get("id");
        long numero = ((Number) nuevo.get("numero")).longValue();

        String credito = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, payment_method,
                                               reference, transaction_date, type, recibo_id, registrado_por)
                VALUES (?, ?, ?, ?, now(), ?, NULL, ?, ?, 'CREDIT', ?, ?)""",
                credito, negocio, cuenta, monto, "Abono, recibo " + numero, "RC-" + numero,
                java.sql.Date.valueOf(ocurrido.atZoneSameInstant(BOGOTA).toLocalDate()), reciboId,
                autorDelLibro(quien));
        for (Object[] a : reparto) {
            jdbc.update("""
                    INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla)
                    VALUES (?, ?, ?, ?, ?, ?)""", negocio, reciboId, credito, a[0], a[1], regla);
        }
        jdbc.update("""
                UPDATE accounts_receivable SET total_debt = total_debt - ?, last_transaction_date = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ?""", monto, java.sql.Date.valueOf(hoy), negocio, cuenta);
        return new Registro(recibo(negocio, reciboId, false).orElseThrow(), false);
    }

    /** Reparte el abono: las elegidas por quien cobra (deben sumar el monto) o la más antigua primero. */
    private List<Object[]> repartir(List<Map<String, Object>> vivas, BigDecimal monto, List<Aplicacion> elegidas) {
        List<Object[]> reparto = new ArrayList<>();
        if (elegidas == null || elegidas.isEmpty()) {
            BigDecimal falta = monto;
            for (Map<String, Object> f : vivas) {
                if (falta.signum() <= 0) {
                    break;
                }
                BigDecimal parte = falta.min(decimal(f.get("saldo")));
                reparto.add(new Object[] {f.get("debito_tx_id"), parte});
                falta = falta.subtract(parte);
            }
            return reparto;
        }
        Set<UUID> vistas = new HashSet<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (int i = 0; i < elegidas.size(); i++) {
            Aplicacion a = elegidas.get(i);
            String campo = "aplicaciones[" + i + "]";
            if (a == null || a.orderUuid() == null) {
                throw new DatoInvalidoException(campo + ".orderUuid", "Cada aplicación dice a qué venta va.");
            }
            if (!vistas.add(a.orderUuid())) {
                throw new DatoInvalidoException(campo + ".orderUuid", "Esa venta está dos veces en las aplicaciones.");
            }
            BigDecimal parte = montoValido(a.monto(), campo + ".monto");
            Map<String, Object> factura = vivas.stream().filter(f -> a.orderUuid().equals(f.get("order_uuid")))
                    .findFirst().orElseThrow(() -> new DatoInvalidoException(campo + ".orderUuid",
                            "Esa venta no es una factura con saldo de este cliente."));
            if (parte.compareTo(decimal(factura.get("saldo"))) > 0) {
                throw new DatoInvalidoException(campo + ".monto", "Se aplica más (" + pesos(parte)
                        + ") de lo que debe esa factura (" + pesos(decimal(factura.get("saldo"))) + ").");
            }
            reparto.add(new Object[] {factura.get("debito_tx_id"), parte});
            suma = suma.add(parte);
        }
        if (suma.compareTo(monto) != 0) {
            throw new DatoInvalidoException("aplicaciones", "Las aplicaciones suman " + pesos(suma)
                    + " y el recibo es de " + pesos(monto) + ": tienen que ser iguales.");
        }
        return reparto;
    }

    /**
     * POST /api/cartera/recibos/{id}/anular (admin). Otro recibo con el motivo, un
     * DEBIT que devuelve la deuda y {@code total_debt} de vuelta. Las aplicaciones
     * del recibo anulado dejan de contar en la vista (V64).
     */
    @Transactional
    public Map<String, Object> anular(Quien quien, UUID reciboId, String motivo) {
        String negocio = quien.negocio();
        String elMotivo = motivo == null ? null : motivo.trim().toUpperCase(Locale.ROOT);
        if (elMotivo == null || !MOTIVOS_DE_ANULACION.contains(elMotivo)) {
            throw new DatoInvalidoException("motivo",
                    "El motivo es uno de: ERROR_DE_MONTO, CHEQUE_DEVUELTO, DUPLICADO, APLICADO_A_OTRO_CLIENTE.");
        }
        if (reciboId == null) {
            throw new DatoInvalidoException("id", "Ese recibo no existe en el negocio.");
        }
        // Sin FOR UPDATE: la tabla no da UPDATE a la aplicación (solo-anexar). El candado es de consejo.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "anular:" + negocio + ":" + reciboId);
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT r.numero, r.cliente_documento, r.monto, r.medio, r.site_id, r.anula_recibo_id,
                       (SELECT x.numero FROM recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id) AS anulado_por
                  FROM recibos_de_caja r WHERE r.tenant_id = ? AND r.id = ?""", negocio, reciboId);
        if (filas.isEmpty()) {
            throw new DatoInvalidoException("id", "Ese recibo no existe en el negocio.");
        }
        Map<String, Object> original = filas.get(0);
        if (original.get("anula_recibo_id") != null) {
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.RECIBO_ES_ANULACION,
                    "El recibo " + original.get("numero") + " es una anulación: no se anula. Si hubo un error, registra otro recibo.");
        }
        if (original.get("anulado_por") != null) {
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.RECIBO_YA_ANULADO,
                    "El recibo " + original.get("numero") + " ya está anulado por el recibo " + original.get("anulado_por") + ".");
        }
        String documento = (String) original.get("cliente_documento");
        BigDecimal monto = decimal(original.get("monto"));
        String cuenta = cuentaBloqueada(negocio, documento).orElseThrow();
        LocalDate hoy = LocalDate.now(BOGOTA);

        Map<String, Object> anulacion = jdbc.queryForMap("""
                INSERT INTO recibos_de_caja (tenant_id, numero, cliente_documento, monto, medio, cobrado_por, site_id,
                                             ocurrido_en, idempotency_key, anula_recibo_id, motivo_anulacion)
                VALUES (?, 0, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                RETURNING id, numero""",
                negocio, documento, monto, original.get("medio"), quien.usuarioId(), original.get("site_id"),
                "anulacion:" + reciboId, reciboId, elMotivo);
        UUID anulacionId = (UUID) anulacion.get("id");
        long numero = ((Number) anulacion.get("numero")).longValue();
        jdbc.update("""
                INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, payment_method,
                                               reference, transaction_date, type, recibo_id, registrado_por)
                VALUES (?, ?, ?, ?, now(), ?, NULL, ?, ?, 'DEBIT', ?, ?)""",
                UUID.randomUUID().toString(), negocio, cuenta, monto,
                "Anulacion del recibo " + original.get("numero") + " (" + elMotivo + "), recibo " + numero,
                "RC-" + numero, java.sql.Date.valueOf(hoy), anulacionId, autorDelLibro(quien));
        jdbc.update("""
                UPDATE accounts_receivable SET total_debt = total_debt + ?, last_transaction_date = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ?""", monto, java.sql.Date.valueOf(hoy), negocio, cuenta);
        return recibo(negocio, anulacionId, false).orElseThrow();
    }

    /** Un recibo con sus aplicaciones, su anulación si la tiene y el saldo del cliente al leer. */
    public Optional<Map<String, Object>> recibo(String negocio, UUID id, boolean repetido) {
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT r.id, r.numero, r.cliente_documento, ar.customer_name AS cliente_nombre, r.monto, r.medio,
                       r.referencia_medio, r.cobrado_por, u.nombre AS cobrado_por_nombre, r.site_id, r.liquidacion_id,
                       r.ocurrido_en, r.registrado_en, r.anula_recibo_id, r.motivo_anulacion,
                       x.id AS anulado_por_id, x.numero AS anulado_por_numero, x.motivo_anulacion AS anulado_por_motivo
                  FROM recibos_de_caja r
                  LEFT JOIN accounts_receivable ar ON ar.tenant_id = r.tenant_id AND ar.customer_document = r.cliente_documento
                  LEFT JOIN users u ON u.tenant_id = r.tenant_id AND u.id = r.cobrado_por
                  LEFT JOIN recibos_de_caja x ON x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id
                 WHERE r.tenant_id = ? AND r.id = ?""", negocio, id);
        if (filas.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> f = filas.get(0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", f.get("id"));
        r.put("numero", f.get("numero"));
        r.put("clienteDocumento", f.get("cliente_documento"));
        r.put("clienteNombre", f.get("cliente_nombre"));
        r.put("monto", f.get("monto"));
        r.put("medio", f.get("medio"));
        r.put("referenciaMedio", f.get("referencia_medio"));
        r.put("cobradoPor", f.get("cobrado_por"));
        r.put("cobradoPorNombre", f.get("cobrado_por_nombre"));
        r.put("siteId", f.get("site_id"));
        r.put("liquidacionId", f.get("liquidacion_id"));
        r.put("ocurridoEn", instante(f.get("ocurrido_en")));
        r.put("registradoEn", instante(f.get("registrado_en")));
        r.put("anulaReciboId", f.get("anula_recibo_id"));
        r.put("motivoAnulacion", f.get("motivo_anulacion"));
        if (f.get("anulado_por_id") != null) {
            Map<String, Object> anulado = new LinkedHashMap<>();
            anulado.put("id", f.get("anulado_por_id"));
            anulado.put("numero", f.get("anulado_por_numero"));
            anulado.put("motivo", f.get("anulado_por_motivo"));
            r.put("anuladoPor", anulado);
        } else {
            r.put("anuladoPor", null);
        }
        r.put("aplicaciones", jdbc.queryForList("""
                SELECT d.order_uuid, a.debito_tx_id, a.monto, a.regla
                  FROM cartera_aplicaciones a
                  JOIN debt_transactions d ON d.tenant_id = a.tenant_id AND d.id = a.debito_tx_id
                 WHERE a.tenant_id = ? AND a.recibo_id = ?
                 ORDER BY d.transaction_date, d.created_at""", negocio, id).stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderUuid", a.get("order_uuid"));
                    m.put("debitoTxId", a.get("debito_tx_id"));
                    m.put("monto", a.get("monto"));
                    m.put("regla", a.get("regla"));
                    return m;
                }).toList());
        r.put("saldoCliente", jdbc.queryForList(
                "SELECT saldo FROM v_cartera_por_cliente WHERE tenant_id = ? AND cliente_documento = ?",
                BigDecimal.class, negocio, f.get("cliente_documento")).stream().findFirst().orElse(BigDecimal.ZERO));
        r.put("repetido", repetido);
        return Optional.of(r);
    }

    // ------------------------------------------------------------ cupo e insolvencia

    /**
     * PUT /api/cartera/clientes/{documento}/cupo (admin). El rastro lo escribe la
     * base (V66) con el autor de {@code app.user_id}. Sin cuenta, se abre si el
     * cliente tiene ficha.
     */
    @Transactional
    public Map<String, Object> cambiarCupo(Quien quien, String documento, BigDecimal cupo) {
        String negocio = quien.negocio();
        String doc = obligatorio(documento, "documento", NO_EXISTE);
        if (cupo == null || cupo.signum() < 0 || cupo.scale() > 2 || cupo.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new DatoInvalidoException("cupo", "El cupo es un valor en pesos, de 0 en adelante, con máximo dos decimales.");
        }
        fijarAutor(quien);
        int n = jdbc.update("UPDATE accounts_receivable SET credit_limit = ?, updated_at = now() WHERE tenant_id = ? AND customer_document = ?",
                cupo, negocio, doc);
        if (n == 0) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "cuenta-de-credito:" + negocio + ":" + doc);
            n = jdbc.update("""
                    INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document,
                                                     customer_name, customer_phone, status, total_debt, updated_at)
                    SELECT gen_random_uuid()::text, c.tenant_id, now(), ?, c.documento, left(c.nombre, 100),
                           left(c.telefono, 20), 'ACTIVE', 0, now()
                      FROM clientes c
                     WHERE c.tenant_id = ? AND c.documento = ?
                       AND NOT EXISTS (SELECT 1 FROM accounts_receivable a
                                        WHERE a.tenant_id = c.tenant_id AND a.customer_document = c.documento)""",
                    cupo, negocio, doc);
            if (n == 0) {
                n = jdbc.update("UPDATE accounts_receivable SET credit_limit = ?, updated_at = now() WHERE tenant_id = ? AND customer_document = ?",
                        cupo, negocio, doc);
            }
        }
        if (n == 0) {
            throw new DatoInvalidoException("documento", NO_EXISTE);
        }
        return resumenVisible(quien, doc);
    }

    /**
     * POST /api/cartera/clientes/{documento}/insolvencia (admin). {@code desde} null
     * levanta la marca. El rastro lo escribe el disparador de {@code clientes} (V64).
     */
    @Transactional
    public Map<String, Object> marcarInsolvencia(Quien quien, String documento, LocalDate desde) {
        String doc = obligatorio(documento, "documento", NO_EXISTE);
        fijarAutor(quien);
        int n = jdbc.update("UPDATE clientes SET en_insolvencia_desde = ?, actualizado_en = now() WHERE tenant_id = ? AND documento = ?",
                desde == null ? null : java.sql.Date.valueOf(desde), quien.negocio(), doc);
        if (n == 0) {
            throw new DatoInvalidoException("documento", NO_EXISTE);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        r.put("enInsolvenciaDesde", desde == null ? null : desde.toString());
        return r;
    }

    // ------------------------------------------------------------ ventas a insolventes (F4.11)

    public static final String POR_REVISAR = "VENTA_A_INSOLVENTE_POR_REVISAR";
    static final Set<String> DECISIONES_SOBRE_VENTA_A_INSOLVENTE = Set.of("DEJAR_COMO_DEUDA", "COBRAR_DE_CONTADO");
    private static final String NO_EXISTE_LA_MARCA = "Esa venta por revisar no existe en el negocio.";

    private static final String VENTAS_A_INSOLVENTE = """
            SELECT m.id, m.order_uuid, o.id_order, m.cliente_documento, c.nombre AS cliente_nombre,
                   m.en_insolvencia_desde, m.total, m.terminal_id, t.codigo AS terminal_codigo,
                   m.operado_por, op.nombre AS operado_por_nombre, m.vendedor_id, u.nombre AS vendedor,
                   m.ocurrido_en, m.registrado_en,
                   r.decision, r.nota, r.usuario_id AS resuelto_por, ru.nombre AS resuelto_por_nombre, r.resuelto_en
              FROM ventas_a_insolvente m
              LEFT JOIN ventas_a_insolvente_resoluciones r ON r.tenant_id = m.tenant_id AND r.venta_a_insolvente_id = m.id
              LEFT JOIN orders o     ON o.uuid_id = m.order_uuid AND o.tenant_id = m.tenant_id
              LEFT JOIN clientes c   ON c.tenant_id = m.tenant_id AND c.documento = m.cliente_documento
              LEFT JOIN terminals t  ON t.tenant_id = m.tenant_id AND t.id = m.terminal_id
              LEFT JOIN users op     ON op.tenant_id = m.tenant_id AND op.id = m.operado_por
              LEFT JOIN users u      ON u.tenant_id = m.tenant_id AND u.id = m.vendedor_id
              LEFT JOIN users ru     ON ru.tenant_id = r.tenant_id AND ru.id = r.usuario_id
             WHERE m.tenant_id = ?""";

    /**
     * GET /api/cartera/ventas-a-insolvente (admin, F4.11): las ventas a crédito que una caja
     * le hizo a un cliente ya en insolvencia. Entraron con su deuda (V72); cada una espera la
     * decisión del admin. {@code pendientes} true (por defecto) = solo las que no tienen
     * resolución; false = todas. La más reciente primero, máximo 200.
     */
    public List<Map<String, Object>> ventasAInsolvente(Quien quien, Boolean pendientes) {
        String filtro = Boolean.FALSE.equals(pendientes) ? "" : " AND r.id IS NULL";
        return jdbc.queryForList(VENTAS_A_INSOLVENTE + filtro + " ORDER BY m.registrado_en DESC, m.id LIMIT 200",
                quien.negocio()).stream().map(Cartera::ventaAInsolvente).toList();
    }

    /**
     * POST /api/cartera/ventas-a-insolvente/{id}/resolucion (admin, F4.11). Anexa la
     * decisión sobre la marca, que no cambia: DEJAR_COMO_DEUDA o COBRAR_DE_CONTADO. No
     * mueve la deuda (el cobro de contado es un recibo como cualquier otro; anular la venta
     * espera a D7). Una sola resolución por marca: la segunda es 409 VENTA_YA_RESUELTA.
     */
    @Transactional
    public Map<String, Object> resolverVentaAInsolvente(Quien quien, UUID marca, String decision, String nota) {
        String laDecision = decision == null ? null : decision.trim().toUpperCase(Locale.ROOT);
        if (laDecision == null || !DECISIONES_SOBRE_VENTA_A_INSOLVENTE.contains(laDecision)) {
            throw new DatoInvalidoException("decision", "La decisión es una de: DEJAR_COMO_DEUDA, COBRAR_DE_CONTADO.");
        }
        String laNota = recortarONulo(nota);
        if (laNota != null && laNota.length() > 500) {
            throw new DatoInvalidoException("nota", "La nota tiene máximo 500 caracteres.");
        }
        if (quien.usuarioId() == null) {
            throw new DatoInvalidoException("usuario", "La sesión no tiene un usuario: la decisión necesita autor.");
        }
        if (marca == null) {
            throw new DatoInvalidoException("id", NO_EXISTE_LA_MARCA);
        }
        String negocio = quien.negocio();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "venta-a-insolvente:" + negocio + ":" + marca);
        List<Map<String, Object>> filas = jdbc.queryForList(VENTAS_A_INSOLVENTE + " AND m.id = ?", negocio, marca);
        if (filas.isEmpty()) {
            throw new DatoInvalidoException("id", NO_EXISTE_LA_MARCA);
        }
        if (filas.get(0).get("decision") != null) {
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.VENTA_YA_RESUELTA,
                    "Esa venta ya se resolvió como " + filas.get(0).get("decision") + ".");
        }
        jdbc.update("""
                INSERT INTO ventas_a_insolvente_resoluciones (tenant_id, venta_a_insolvente_id, decision, nota, usuario_id)
                VALUES (?, ?, ?, ?, ?)""", negocio, marca, laDecision, laNota, quien.usuarioId());
        return ventaAInsolvente(jdbc.queryForList(VENTAS_A_INSOLVENTE + " AND m.id = ?", negocio, marca).get(0));
    }

    private static Map<String, Object> ventaAInsolvente(Map<String, Object> f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", f.get("id"));
        r.put("tipo", POR_REVISAR);
        r.put("orderUuid", f.get("order_uuid"));
        r.put("idOrder", f.get("id_order"));
        r.put("clienteDocumento", f.get("cliente_documento"));
        r.put("clienteNombre", f.get("cliente_nombre"));
        r.put("enInsolvenciaDesde", fecha(f.get("en_insolvencia_desde")));
        r.put("total", f.get("total"));
        r.put("terminalId", f.get("terminal_id"));
        r.put("terminalCodigo", f.get("terminal_codigo"));
        r.put("operadoPorId", f.get("operado_por"));
        r.put("operadoPor", f.get("operado_por_nombre"));
        r.put("vendedorId", f.get("vendedor_id"));
        r.put("vendedor", f.get("vendedor"));
        r.put("ocurridoEn", instante(f.get("ocurrido_en")));
        r.put("registradoEn", instante(f.get("registrado_en")));
        if (f.get("decision") == null) {
            r.put("resolucion", null);
        } else {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("decision", f.get("decision"));
            res.put("nota", f.get("nota"));
            res.put("usuarioId", f.get("resuelto_por"));
            res.put("usuario", f.get("resuelto_por_nombre"));
            res.put("resueltoEn", instante(f.get("resuelto_en")));
            r.put("resolucion", res);
        }
        return r;
    }

    // ------------------------------------------------------------------ piezas

    private Map<String, Object> resumenVisible(Quien quien, String documento) {
        String doc = obligatorio(documento, "documento", NO_EXISTE);
        fichaVisible(quien, doc);
        List<Map<String, Object>> filas = jdbc.queryForList(RESUMEN_DEL_CLIENTE + " AND v.cliente_documento = ?",
                quien.negocio(), doc);
        if (filas.isEmpty()) {
            throw new DatoInvalidoException("documento", "Ese cliente no tiene cuenta por cobrar.");
        }
        return resumen(filas.get(0));
    }

    /**
     * La ficha del cliente si quien pide puede verla. A un vendedor, el cliente de
     * otro no existe (mismo texto, no se confirma nada). Una cuenta antigua sin
     * ficha solo la ve quien no es vendedor: devuelve null.
     */
    private Map<String, Object> fichaVisible(Quien quien, String documento) {
        List<Map<String, Object>> fichas = jdbc.queryForList(
                "SELECT vendedor_id, en_insolvencia_desde FROM clientes WHERE tenant_id = ? AND documento = ?",
                quien.negocio(), documento);
        Map<String, Object> ficha = fichas.isEmpty() ? null : fichas.get(0);
        if (quien.esVendedor()) {
            Object suyo = ficha == null ? null : ficha.get("vendedor_id");
            if (suyo == null || ((Number) suyo).longValue() != idDelVendedor(quien)) {
                throw new DatoInvalidoException("clienteDocumento", NO_EXISTE);
            }
        }
        return ficha;
    }

    private long idDelVendedor(Quien quien) {
        // Sin id resuelto, un vendedor no ve nada: -1 no es ningún usuario.
        return quien.usuarioId() == null ? -1L : quien.usuarioId();
    }

    private Optional<String> cuentaBloqueada(String negocio, String documento) {
        return jdbc.queryForList("""
                SELECT id FROM accounts_receivable WHERE tenant_id = ? AND customer_document = ? FOR UPDATE""",
                String.class, negocio, documento).stream().findFirst();
    }

    /** Facturas con saldo, la más antigua primero (fecha de la venta y, a igual fecha, la que se escribió antes). */
    private List<Map<String, Object>> facturasVivas(String negocio, String cuenta) {
        return jdbc.queryForList("""
                SELECT v.debito_tx_id, v.order_uuid, v.saldo
                  FROM v_cartera_por_documento v
                  JOIN debt_transactions d ON d.tenant_id = v.tenant_id AND d.id = v.debito_tx_id
                 WHERE v.tenant_id = ? AND v.account_id = ? AND v.saldo > 0
                 ORDER BY v.fecha, d.created_at, v.debito_tx_id""", negocio, cuenta);
    }

    /** DEBIT − CREDIT de la cuenta. Descuenta también los abonos del panel viejo, que no tienen aplicaciones. */
    private BigDecimal saldoDelLibro(String negocio, String cuenta) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN type = 'DEBIT' THEN amount ELSE -amount END), 0)
                  FROM debt_transactions WHERE tenant_id = ? AND account_id = ?""", BigDecimal.class, negocio, cuenta);
    }

    private void fijarAutor(Quien quien) {
        jdbc.query("SELECT set_config('app.user_id', ?, true)", rs -> null,
                quien.usuarioId() == null ? "" : String.valueOf(quien.usuarioId()));
    }

    private static String autorDelLibro(Quien quien) {
        return quien.usuarioId() == null ? "usuario:desconocido" : "usuario:" + quien.usuarioId();
    }

    private static BigDecimal montoValido(BigDecimal monto, String campo) {
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2 || monto.compareTo(MONTO_MAXIMO) > 0) {
            throw new DatoInvalidoException(campo, "El monto es mayor que 0, con máximo dos decimales.");
        }
        return monto.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String obligatorio(String valor, String campo, String mensaje) {
        if (valor == null || valor.isBlank()) {
            throw new DatoInvalidoException(campo, mensaje);
        }
        return valor.trim();
    }

    private static String recortarONulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal decimal(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }

    private static String fecha(Object o) {
        return o == null ? null : o.toString();
    }

    private static String instante(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        return ((Timestamp) o).toInstant().toString();
    }

    /** $1.234.567 — pesos colombianos, sin decimales si no los hay. */
    public static String pesos(BigDecimal valor) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        DecimalFormat formato = new DecimalFormat(valor.stripTrailingZeros().scale() > 0 ? "#,##0.00" : "#,##0", simbolos);
        return "$" + formato.format(valor);
    }
}
