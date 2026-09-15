package com.suresell.orders.cartera;

import com.suresell.orders.cartera.Cartera.Quien;
import com.suresell.orders.shared.exception.ConflictoDeCarteraException;
import com.suresell.orders.shared.exception.DatoInvalidoException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * La insolvencia de un cliente como proceso (plan de mayoristas F4.13, corte (a); diseño en
 * docs/planes/DISENO-F4-13-INSOLVENCIA-COMO-PROCESO.md, V75).
 *
 * <ul>
 *   <li>Una etapa se informa SOLO con {@code fn_insolvencia_informar_etapa}: la función anexa, toma la foto de la deuda al
 *       informar INICIO y escribe la proyección {@code clientes.en_insolvencia_desde}. Aquí se valida antes para responder
 *       con el campo o el código; la función repite las reglas como suelo.</li>
 *   <li>El corte (§11.2, opción A, provisional hasta el abogado): INICIO del mismo día → el instante del registro; INICIO
 *       retroactivo → el final del día anterior a la fecha del auto. Foto y clasificación usan la misma línea.</li>
 *   <li>Nada se calcula por fila ni con temporizador: la etapa vigente y la clasificación son vistas.</li>
 * </ul>
 */
@Service
public class ProcesoDeInsolvencia {

    public static final List<String> ETAPAS = List.of("SOLICITUD", "SOLICITUD_NO_ADMITIDA", "INICIO", "ACUERDO_CONFIRMADO",
            "CUMPLIDO_TERMINADO", "LIQUIDACION", "CORRECCION_DE_ERROR");
    public static final Set<String> REGIMENES = Set.of("LEY_1116", "CGP");
    public static final String LIQUIDACION_NO_SE_LEVANTA = "LIQUIDACION_NO_SE_LEVANTA";
    public static final String ETAPA_NO_PERMITIDA = "ETAPA_NO_PERMITIDA";
    public static final String LEVANTAR_SIN_ETAPA = "LEVANTAR_SIN_ETAPA";
    public static final String CREDITO_POSTERIOR_EN_LIQUIDACION = "CREDITO_POSTERIOR_EN_LIQUIDACION";
    public static final String SIN_PROCESO_EN_CURSO = "SIN_PROCESO_EN_CURSO";
    /** TEXTOS §B10 (F4.13 c). */
    static final String NO_SE_HABILITA_EN_LIQUIDACION = "En liquidación no se habilita el crédito: véndele de contado. No se registró nada.";
    static final String SOLO_CON_EL_PROCESO_INICIADO = "Solo se habilita el crédito cuando el proceso ya inició; antes, se le vende "
            + "como a cualquier cliente. No se registró nada.";
    static final int PLAZO_MAXIMO_POR_DEFECTO = 8;

    /** Las dos lecturas de la deuda posterior (cifra de B6); CostoDeLaFotoDeInsolvenciaTest mide estas mismas. */
    static final String POSTERIORES_DEL_CLIENTE =
            "SELECT debito_tx_id FROM v_insolvencia_clasificacion WHERE tenant_id = ? AND cliente_documento = ? AND clasificacion = 'POSTERIOR'";
    static final String SALDOS_VIVOS_DEL_CLIENTE =
            "SELECT debito_tx_id, saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND cliente_documento = ? AND saldo > 0";
    /** TEXTOS §B8 (F4.13 b). */
    static final String SE_LEVANTA_CON_UNA_ETAPA = "La insolvencia se levanta informando que el acuerdo se cumplió y el proceso "
            + "terminó, o que se marcó por error. No se registró nada.";

    /** TEXTOS §B9, aprobado por ECM el 2026-09-15: la fecha del INICIO (y la que la corrige). */
    static final String FECHA_POSTERIOR_A_HOY =
            "La fecha de inicio del proceso es la que figura en el auto o el acta y no puede ser posterior a hoy. No se registró nada.";
    /** Las demás etapas (ECM, 2026-09-15). */
    static final String FECHA_DE_ETAPA_POSTERIOR_A_HOY =
            "La fecha de la etapa es la que figura en el auto o el acta y no puede ser posterior a hoy. No se registró nada.";
    static final String ETAPA_NO_VALIDA = "La etapa no es válida.";
    /** TEXTOS §B8. */
    static final String EN_LIQUIDACION_NO_SE_LEVANTA = "En liquidación la insolvencia no se levanta.";

    /** Lo que sigue a cada etapa vigente. Sin proceso abierto, solo SOLICITUD o INICIO. LIQUIDACION no se cierra. */
    static final Map<String, Set<String>> SIGUIENTES = Map.of(
            "SOLICITUD", Set.of("INICIO", "SOLICITUD_NO_ADMITIDA", "CORRECCION_DE_ERROR"),
            "INICIO", Set.of("ACUERDO_CONFIRMADO", "LIQUIDACION", "CORRECCION_DE_ERROR"),
            "ACUERDO_CONFIRMADO", Set.of("CUMPLIDO_TERMINADO", "LIQUIDACION", "CORRECCION_DE_ERROR"),
            "LIQUIDACION", Set.of());

    /** Etiquetas de TEXTOS §B7 y B7b (ECM, 2026-09-15). */
    static final Map<String, String> ETIQUETAS = Map.of(
            "SOLICITUD", "Solicitud presentada",
            "SOLICITUD_NO_ADMITIDA", "Solicitud no admitida",
            "INICIO", "Proceso iniciado",
            "ACUERDO_CONFIRMADO", "Acuerdo confirmado, en ejecución",
            "CUMPLIDO_TERMINADO", "Acuerdo cumplido, proceso terminado",
            "LIQUIDACION", "En liquidación",
            "CORRECCION_DE_ERROR", "Corrección de registro");

    static final String SIN_DOCUMENTO_DESDE_LA_MARCA = "Sin documento (registrado desde la marca anterior)";
    static final String FECHA_CORREGIDA_DESDE_LA_MARCA = "Fecha de inicio corregida desde la marca anterior";

    static final java.time.format.DateTimeFormatter FECHA_LARGA =
            java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CO"));

    private final JdbcTemplate jdbc;
    private final Cartera cartera;

    public ProcesoDeInsolvencia(JdbcTemplate jdbc, Cartera cartera) {
        this.jdbc = jdbc;
        this.cartera = cartera;
    }

    public record CreditoPosterior(Boolean habilitado, Integer plazoMaximoDias, String motivo) {}

    public record Reversion(String motivo, String referencia) {}

    /** El resultado de revertir: el cuerpo y si ya estaba revertida (reintento, 200). */
    public record Revertida(Map<String, Object> cuerpo, boolean repetida) {}

    /** F4.13f (aditivo): procedimiento, autoridad del catálogo y quién lleva el trámite. `autoridad` (texto) se queda (R10). */
    public record NuevaEtapa(String etapa, LocalDate fecha, String documento, String autoridad, String informadoPor,
                             String regimen, String numeroProceso, UUID corrigeEtapaId, String procedimiento,
                             String procedimientoOtro, String autoridadTipo, String autoridadOtra, Tramitador quienLlevaElTramite) {
        public NuevaEtapa(String etapa, LocalDate fecha, String documento, String autoridad, String informadoPor,
                          String regimen, String numeroProceso, UUID corrigeEtapaId) {
            this(etapa, fecha, documento, autoridad, informadoPor, regimen, numeroProceso, corrigeEtapaId, null, null, null, null, null);
        }
    }

    public record Tramitador(String nombre, String papel) {}

    /** TEXTOS §B18. */
    static final String FALTA_EL_CONCILIADOR = "Falta el nombre del conciliador que lleva el trámite.";
    /** TEXTOS §B18 (aprobado por ECM el 2026-09-15). */
    static final String CON_CENTRO_O_NOTARIA_ES_EL_CONCILIADOR =
            "Con un centro de conciliación o una notaría, quien lleva el trámite es el conciliador.";

    /** TEXTOS §B16 (F4.13f). */
    static final List<String> PROCEDIMIENTOS = List.of("REORGANIZACION", "REORGANIZACION_ABREVIADA", "LIQUIDACION_JUDICIAL",
            "LIQUIDACION_SIMPLIFICADA", "NEGOCIACION_DE_DEUDAS", "CONVALIDACION_DE_ACUERDO_PRIVADO", "LIQUIDACION_PATRIMONIAL",
            "OTRO_PROCEDIMIENTO");
    /** B18 (concepto V): entran juez civil municipal y notaría. */
    static final List<String> AUTORIDADES = List.of("SUPERINTENDENCIA_DE_SOCIEDADES", "JUEZ_CIVIL_DEL_CIRCUITO",
            "JUEZ_CIVIL_MUNICIPAL", "CENTRO_DE_CONCILIACION", "NOTARIA", "OTRA");
    /** B18: con estas lo lleva el conciliador inscrito, obligatorio desde «Proceso iniciado». */
    static final Set<String> CON_CONCILIADOR = Set.of("CENTRO_DE_CONCILIACION", "NOTARIA");
    static final Set<String> DESDE_EL_INICIO = Set.of("INICIO", "ACUERDO_CONFIRMADO", "CUMPLIDO_TERMINADO", "LIQUIDACION");
    /** B18: en el CGP la ayuda del giro ordinario tiene texto para estos dos procedimientos. */
    static final Set<String> CGP_CON_AYUDA = Set.of("NEGOCIACION_DE_DEUDAS", "CONVALIDACION_DE_ACUERDO_PRIVADO");
    static final Set<String> PAPELES = Set.of("PROMOTOR", "CONCILIADOR", "LIQUIDADOR");

    // ------------------------------------------------------------------ escribir

    /** POST /api/cartera/clientes/{documento}/insolvencia/etapas (admin): anexa una etapa y devuelve el proceso. */
    @Transactional
    public Map<String, Object> informarEtapa(Quien quien, String documento, NuevaEtapa e) {
        String doc = clienteBloqueado(quien, documento);
        if (e == null) {
            throw new DatoInvalidoException("etapa", ETAPA_NO_VALIDA);
        }
        String etapa = e.etapa() == null ? null : e.etapa().trim().toUpperCase(Locale.ROOT);
        if (etapa == null || !ETAPAS.contains(etapa)) {
            throw new DatoInvalidoException("etapa", ETAPA_NO_VALIDA);
        }
        boolean correccion = "CORRECCION_DE_ERROR".equals(etapa);
        String documentoDeLaEtapa = obligatorio(e.documento(), "documento",
                correccion ? "Falta el motivo de la corrección." : "Falta el documento (auto o acta).");
        String informadoPor = obligatorio(e.informadoPor(), "informadoPor", "Falta quién informó la etapa.");
        String regimen = e.regimen() == null || e.regimen().isBlank() ? null : e.regimen().trim().toUpperCase(Locale.ROOT);
        if (regimen != null && !REGIMENES.contains(regimen)) {
            throw new DatoInvalidoException("regimen", "El régimen es Ley 1116 o Código General del Proceso.");
        }
        if (e.corrigeEtapaId() != null && !correccion) {
            throw new DatoInvalidoException("corrigeEtapaId", "Solo una corrección de registro corrige otra etapa.");
        }
        // F4.13f (B16): el número del proceso es obligatorio en el cuerpo de INICIO; después vale el guardado.
        if ("INICIO".equals(etapa) && (e.numeroProceso() == null || e.numeroProceso().isBlank())) {
            throw new DatoInvalidoException("numeroProceso", "Falta el número del proceso o expediente.");
        }
        String procedimiento = mayusculasONulo(e.procedimiento());
        if (procedimiento != null && !PROCEDIMIENTOS.contains(procedimiento)) {
            throw new DatoInvalidoException("procedimiento", "El procedimiento es reorganización, reorganización abreviada, "
                    + "liquidación judicial o liquidación simplificada (Ley 1116); negociación de deudas, convalidación de acuerdo "
                    + "privado o liquidación patrimonial (Código General del Proceso); u otro procedimiento.");
        }
        String procedimientoOtro = "OTRO_PROCEDIMIENTO".equals(procedimiento)
                ? obligatorio(e.procedimientoOtro(), "procedimientoOtro", "Falta cuál es el otro procedimiento.") : null;
        String autoridadTipo = mayusculasONulo(e.autoridadTipo());
        if (autoridadTipo != null && !AUTORIDADES.contains(autoridadTipo)) {
            throw new DatoInvalidoException("autoridadTipo",
                    "La autoridad o entidad es la Superintendencia de Sociedades, un juez civil del circuito, un juez civil municipal, "
                            + "un centro de conciliación, una notaría u otra.");
        }
        String autoridadOtra = "OTRA".equals(autoridadTipo)
                ? obligatorio(e.autoridadOtra(), "autoridadOtra", "Falta cuál es la otra autoridad o entidad.") : null;
        String tramitadorNombre = null;
        String tramitadorPapel = null;
        if (e.quienLlevaElTramite() != null
                && (e.quienLlevaElTramite().papel() != null || e.quienLlevaElTramite().nombre() != null)) {
            tramitadorPapel = mayusculasONulo(e.quienLlevaElTramite().papel());
            if (tramitadorPapel == null || !PAPELES.contains(tramitadorPapel)) {
                throw new DatoInvalidoException("quienLlevaElTramite.papel", "Quien lleva el trámite es promotor, conciliador o liquidador.");
            }
            tramitadorNombre = obligatorio(e.quienLlevaElTramite().nombre(), "quienLlevaElTramite.nombre",
                    conConciliador(autoridadTipo) ? FALTA_EL_CONCILIADOR : "Falta el nombre de quien lleva el trámite.");
        }
        // B18: con centro de conciliación o notaría lo lleva el conciliador; desde el inicio hay que nombrarlo (la base lo repite).
        if (conConciliador(autoridadTipo)) {
            if (tramitadorPapel != null && !"CONCILIADOR".equals(tramitadorPapel)) {
                throw new DatoInvalidoException("quienLlevaElTramite.papel", CON_CENTRO_O_NOTARIA_ES_EL_CONCILIADOR);
            }
            if (tramitadorNombre == null && DESDE_EL_INICIO.contains(etapa)) {
                throw new DatoInvalidoException("quienLlevaElTramite.nombre", FALTA_EL_CONCILIADOR);
            }
        }
        Optional<Map<String, Object>> vigente = abierto(quien.negocio(), doc);
        if (e.corrigeEtapaId() != null) {
            String corregida = vigente.flatMap(v -> jdbc.queryForList("""
                    SELECT etapa FROM insolvencia_etapas
                     WHERE tenant_id = ? AND proceso_id = ? AND id = ? AND etapa <> 'CORRECCION_DE_ERROR'""",
                    String.class, quien.negocio(), v.get("proceso_id"), e.corrigeEtapaId()).stream().findFirst()).orElse(null);
            if (corregida == null) {
                throw new DatoInvalidoException("corrigeEtapaId", "Esa etapa no es del proceso abierto de este cliente.");
            }
            fechaNoFutura(e.fecha(), "INICIO".equals(corregida));
        } else {
            fechaNoFutura(e.fecha(), "INICIO".equals(etapa));
            permitida(vigente.map(v -> (String) v.get("etapa")).orElse(null), etapa);
        }
        cartera.fijarAutor(quien);
        jdbc.queryForObject("SELECT fn_insolvencia_informar_etapa(?, ?, ?::date, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?)", UUID.class,
                doc, etapa, java.sql.Date.valueOf(e.fecha()), documentoDeLaEtapa, recortarONulo(e.autoridad()), informadoPor, regimen,
                recortarONulo(e.numeroProceso()), e.corrigeEtapaId() == null ? null : e.corrigeEtapaId().toString(),
                procedimiento, procedimientoOtro, autoridadTipo, autoridadOtra, tramitadorNombre, tramitadorPapel);
        return proceso(quien, doc);
    }

    /**
     * POST /api/cartera/clientes/{documento}/insolvencia (admin), el endpoint de F4.4 que consume el panel (R10). Con fecha:
     * INICIO sin documento y con régimen pendiente (o corrige la fecha del INICIO si ya estaba en proceso). Con null y un
     * proceso abierto: 409 LEVANTAR_SIN_ETAPA (F4.13 b), o LIQUIDACION_NO_SE_LEVANTA en liquidación; sin proceso, nada.
     * Responde lo mismo que antes.
     */
    @Transactional
    public Map<String, Object> marcarDesdeLaMarcaAnterior(Quien quien, String documento, LocalDate desde) {
        String doc = clienteBloqueado(quien, documento);
        Optional<Map<String, Object>> vigente = abierto(quien.negocio(), doc);
        String etapa = vigente.map(v -> (String) v.get("etapa")).orElse(null);
        String autor = nombreDelUsuario(quien);
        if (desde != null) {
            fechaNoFutura(desde, true);
            if (etapa == null || "SOLICITUD".equals(etapa)) {
                informar(quien, doc, "INICIO", desde, SIN_DOCUMENTO_DESDE_LA_MARCA, null, autor, null, null, null);
            } else if (!desde.equals(fechaSql(vigente.get().get("inicio")))) {
                UUID inicio = jdbc.queryForObject("""
                        SELECT id FROM insolvencia_etapas WHERE tenant_id = ? AND proceso_id = ? AND etapa = 'INICIO'
                         ORDER BY secuencia DESC LIMIT 1""", UUID.class, quien.negocio(), vigente.get().get("proceso_id"));
                informar(quien, doc, "CORRECCION_DE_ERROR", desde, FECHA_CORREGIDA_DESDE_LA_MARCA, null, autor, null, null, inicio);
            }
        } else if ("LIQUIDACION".equals(etapa)) {
            throw new ConflictoDeCarteraException(LIQUIDACION_NO_SE_LEVANTA, EN_LIQUIDACION_NO_SE_LEVANTA);
        } else if (etapa != null) {
            // F4.13 (b): se levanta solo informando CUMPLIDO_TERMINADO o CORRECCION_DE_ERROR por …/insolvencia/etapas (B8).
            throw new ConflictoDeCarteraException(LEVANTAR_SIN_ETAPA, SE_LEVANTA_CON_UNA_ETAPA);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        r.put("enInsolvenciaDesde", jdbc.queryForList("SELECT en_insolvencia_desde FROM clientes WHERE tenant_id = ? AND documento = ?",
                java.sql.Date.class, quien.negocio(), doc).stream().filter(java.util.Objects::nonNull).findFirst()
                .map(d -> d.toLocalDate().toString()).orElse(null));
        return r;
    }

    /**
     * PUT /api/cartera/clientes/{documento}/insolvencia/credito-posterior (admin, F4.13 c): habilita o deshabilita la venta a
     * crédito después del inicio para el proceso en curso. Solo anexa (V77). Devuelve el proceso.
     */
    @Transactional
    public Map<String, Object> cambiarCreditoPosterior(Quien quien, String documento, CreditoPosterior c) {
        String doc = clienteBloqueado(quien, documento);
        if (c == null || c.habilitado() == null) {
            throw new DatoInvalidoException("habilitado", "Indica si se habilita o no.");
        }
        int plazo = c.plazoMaximoDias() == null ? PLAZO_MAXIMO_POR_DEFECTO : c.plazoMaximoDias();
        if (plazo < 0 || plazo > 30) {
            throw new DatoInvalidoException("plazoMaximoDias", "El plazo máximo es de 0 a 30 días.");
        }
        String motivo = obligatorio(c.motivo(), "motivo", "Falta el motivo.");
        Map<String, Object> vigente = abierto(quien.negocio(), doc).filter(v -> Boolean.TRUE.equals(v.get("en_proceso")))
                .orElseThrow(() -> new ConflictoDeCarteraException(SIN_PROCESO_EN_CURSO, SOLO_CON_EL_PROCESO_INICIADO));
        if ("LIQUIDACION".equals(vigente.get("etapa")) && c.habilitado()) {
            throw new ConflictoDeCarteraException(CREDITO_POSTERIOR_EN_LIQUIDACION, NO_SE_HABILITA_EN_LIQUIDACION);
        }
        cartera.fijarAutor(quien);
        jdbc.queryForObject("SELECT fn_insolvencia_credito_posterior(?, ?, ?, ?)", UUID.class, doc, c.habilitado(), plazo, motivo);
        return proceso(quien, doc);
    }

    /**
     * POST /api/cartera/aplicaciones/{id}/revertir (admin, F4.13b): revierte con rastro una aplicación automática del saldo a
     * favor ocurrida en o después del corte del proceso en curso (V80). Textos de TEXTOS §B13b. Idempotente: ya revertida → 200.
     */
    @Transactional
    public Revertida revertirAplicacion(Quien quien, UUID aplicacionId, Reversion r) {
        String negocio = quien.negocio();
        List<Map<String, Object>> filas = aplicacionId == null ? List.of() : jdbc.queryForList("""
                SELECT a.id, a.regla, a.ocurrido_en, r.numero AS recibo_numero, r.cliente_documento,
                       EXISTS (SELECT 1 FROM recibos_de_caja x WHERE x.tenant_id = r.tenant_id AND x.anula_recibo_id = r.id) AS anulado,
                       EXISTS (SELECT 1 FROM cartera_aplicaciones_revertidas rv WHERE rv.aplicacion_id = a.id) AS revertida
                  FROM cartera_aplicaciones a
                  JOIN recibos_de_caja r ON r.tenant_id = a.tenant_id AND r.id = a.recibo_id
                 WHERE a.tenant_id = ? AND a.id = ?""", negocio, aplicacionId);
        if (filas.isEmpty()) {
            throw new DatoInvalidoException("id", "Ese pago no existe en el negocio.");
        }
        Map<String, Object> ap = filas.get(0);
        String motivo = obligatorio(r == null ? null : r.motivo(), "motivo", "Falta el motivo: escribe por qué se revierte. No se revirtió nada.");
        if (Boolean.TRUE.equals(ap.get("revertida"))) {
            return new Revertida(revertidaPorId(negocio, aplicacionId), true);
        }
        if (!"SALDO_A_FAVOR_AUTOMATICO".equals(ap.get("regla"))) {
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.APLICACION_NO_REVERTIBLE,
                    "Aquí solo se revierten los pagos que el sistema aplicó solo desde el saldo a favor; un abono o una devolución "
                            + "no se revierten aquí. No se revirtió nada.");
        }
        String documento = (String) ap.get("cliente_documento");
        Map<String, Object> vigente = abierto(negocio, documento).filter(v -> Boolean.TRUE.equals(v.get("en_proceso")))
                .orElseThrow(() -> new ConflictoDeCarteraException(ConflictoDeCarteraException.APLICACION_NO_REVERTIBLE,
                        "El cliente no tiene un proceso de insolvencia en curso: no hay pagos que revertir por esa causa. No se revirtió nada."));
        if (Boolean.TRUE.equals(ap.get("anulado"))) {
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.APLICACION_NO_REVERTIBLE,
                    "El recibo N.º " + ap.get("recibo_numero") + " de ese saldo a favor está anulado: ese pago ya no cuenta y no hay "
                            + "nada que revertir. No se revirtió nada.");
        }
        Object corte = vigente.get("corte");
        if (corte == null || instanteDe(ap.get("ocurrido_en")).isBefore(instanteDe(corte))) {
            LocalDate inicio = vigente.get("inicio") == null ? null : ((java.sql.Date) vigente.get("inicio")).toLocalDate();
            throw new ConflictoDeCarteraException(ConflictoDeCarteraException.APLICACION_NO_REVERTIBLE,
                    "Ese pago se aplicó antes del inicio del proceso" + (inicio == null ? "" : " (" + inicio.format(FECHA_LARGA) + ")")
                            + ": ya está en la deuda al inicio, que se reclama dentro del proceso. No se revirtió nada.");
        }
        cartera.fijarAutor(quien);
        jdbc.queryForObject("SELECT fn_revertir_aplicacion(?, ?, ?)", Boolean.class, aplicacionId, motivo, recortarONulo(r.referencia()));
        return new Revertida(revertidaPorId(negocio, aplicacionId), false);
    }

    static final String COLUMNAS_DE_LA_REVERTIDA = """
            SELECT rv.aplicacion_id, r.numero AS recibo_numero, d.order_uuid, o.id_order, a.monto, a.ocurrido_en,
                   rv.motivo, rv.referencia, rv.revertida_por, u.nombre, rv.revertida_en, a.debito_tx_id, r.cliente_documento
              FROM cartera_aplicaciones_revertidas rv
              JOIN cartera_aplicaciones a ON a.tenant_id = rv.tenant_id AND a.id = rv.aplicacion_id
              JOIN recibos_de_caja r ON r.tenant_id = a.tenant_id AND r.id = a.recibo_id
              JOIN debt_transactions d ON d.tenant_id = a.tenant_id AND d.id = a.debito_tx_id
              LEFT JOIN orders o ON o.tenant_id = d.tenant_id AND o.uuid_id = d.order_uuid
              LEFT JOIN users u ON u.tenant_id = rv.tenant_id AND u.id = rv.revertida_por
            """;

    private Map<String, Object> revertidaPorId(String negocio, UUID aplicacionId) {
        Map<String, Object> f = jdbc.queryForMap(COLUMNAS_DE_LA_REVERTIDA + " WHERE rv.tenant_id = ? AND rv.aplicacion_id = ?", negocio, aplicacionId);
        Map<String, Object> m = revertida(f);
        m.put("saldoDeLaFactura", jdbc.queryForList("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND debito_tx_id = ?",
                BigDecimal.class, negocio, f.get("debito_tx_id")).stream().findFirst().orElse(BigDecimal.ZERO));
        m.put("saldoAFavor", cartera.saldoAFavor(negocio, (String) f.get("cliente_documento")));
        return m;
    }

    static Map<String, Object> revertida(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aplicacionId", f.get("aplicacion_id"));
        m.put("reciboNumero", f.get("recibo_numero"));
        m.put("orderUuid", f.get("order_uuid"));
        m.put("idOrder", f.get("id_order"));
        m.put("monto", f.get("monto"));
        m.put("aplicadaEn", instante(f.get("ocurrido_en")));
        m.put("motivo", f.get("motivo"));
        m.put("referencia", f.get("referencia"));
        m.put("revertidaPorId", f.get("revertida_por"));
        m.put("revertidaPor", f.get("nombre"));
        m.put("revertidaEn", instante(f.get("revertida_en")));
        return m;
    }

    private static java.time.Instant instanteDe(Object o) {
        return o instanceof OffsetDateTime odt ? odt.toInstant() : ((Timestamp) o).toInstant();
    }

    // ------------------------------------------------------------------ leer

    /**
     * GET /api/cartera/clientes/{documento}/insolvencia (roles que cobran): el proceso abierto o, si no hay, el último; sus
     * etapas; la foto vigente sin filas; y con el proceso en curso, las cifras de B6.
     */
    public Map<String, Object> proceso(Quien quien, String documento) {
        String doc = clienteVisible(quien, documento);
        Optional<Map<String, Object>> fila = ultimo(quien.negocio(), doc);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        if (fila.isEmpty()) {
            r.put("procesoId", null);
            r.put("abierto", false);
            r.put("enProceso", false);
            r.put("etapa", null);
            r.put("inicio", null);
            r.put("corte", null);
            r.put("regimen", null);
            r.put("regimenPendiente", false);
            r.put("numeroProceso", null);
            r.put("numeroProcesoPendiente", false);
            r.put("aplicaAyudaGiroOrdinario", false);
            r.put("procedimiento", null);
            r.put("procedimientoOtro", null);
            r.put("autoridadTipo", null);
            r.put("autoridadOtra", null);
            r.put("tipoDeSoporte", null);
            r.put("quienLlevaElTramite", null);
            r.put("foto", null);
            r.put("cifras", null);
            r.put("etapas", List.of());
            return r;
        }
        Map<String, Object> v = fila.get();
        boolean abierto = Boolean.TRUE.equals(v.get("abierto"));
        boolean enProceso = Boolean.TRUE.equals(v.get("en_proceso"));
        r.put("procesoId", v.get("proceso_id"));
        r.put("abierto", abierto);
        r.put("enProceso", enProceso);
        r.put("etapa", v.get("etapa"));
        r.put("inicio", fecha(v.get("inicio")));
        r.put("corte", instante(v.get("corte")));
        r.put("regimen", v.get("regimen"));
        r.put("regimenPendiente", abierto && v.get("regimen") == null);
        r.put("numeroProceso", v.get("numero_proceso"));
        // F4.13f: el número falta en lo migrado, lo del endpoint viejo y lo informado sin él.
        r.put("numeroProcesoPendiente", enProceso && v.get("numero_proceso") == null);
        if (v.get("foto_id") == null) {
            r.put("foto", null);
        } else {
            Map<String, Object> foto = new LinkedHashMap<>();
            foto.put("id", v.get("foto_id"));
            foto.put("corte", instante(v.get("corte")));
            foto.put("facturas", v.get("foto_facturas"));
            foto.put("total", v.get("foto_total"));
            foto.put("huella", v.get("foto_huella"));
            r.put("foto", foto);
        }
        if (enProceso) {
            Map<String, Object> cifras = new LinkedHashMap<>();
            cifras.put("deudaAnteriorAlInicio", v.get("foto_total") == null ? BigDecimal.ZERO : v.get("foto_total"));
            cifras.put("saldoAFavor", cartera.saldoAFavor(quien.negocio(), doc));
            // Dos lecturas planas y la suma aquí: unidas en SQL, el planificador evaluaba la vista (o la CTE) por factura.
            java.util.Set<String> posteriores = new java.util.HashSet<>(jdbc.queryForList(POSTERIORES_DEL_CLIENTE, String.class, quien.negocio(), doc));
            BigDecimal[] deudaPosterior = {BigDecimal.ZERO};
            if (!posteriores.isEmpty()) {
                jdbc.query(SALDOS_VIVOS_DEL_CLIENTE, rs -> {
                    if (posteriores.contains(rs.getString(1))) {
                        deudaPosterior[0] = deudaPosterior[0].add(rs.getBigDecimal(2));
                    }
                }, quien.negocio(), doc);
            }
            cifras.put("deudaPosteriorAlInicio", deudaPosterior[0]);
            r.put("cifras", cifras);
        } else {
            r.put("cifras", null);
        }
        r.put("creditoPosterior", enProceso ? creditoPosterior(quien.negocio(), v.get("proceso_id")) : null);
        r.put("etapas", jdbc.queryForList("""
                SELECT e.id, e.secuencia, e.etapa, e.fecha, e.documento, e.autoridad, e.informado_por, e.regimen,
                       e.numero_proceso, e.corrige_etapa_id, e.registrado_por, u.nombre, e.registrado_en,
                       e.procedimiento, e.procedimiento_otro, e.autoridad_tipo, e.autoridad_otra, e.tramitador_nombre, e.tramitador_papel
                  FROM insolvencia_etapas e LEFT JOIN users u ON u.tenant_id = e.tenant_id AND u.id = e.registrado_por
                 WHERE e.tenant_id = ? AND e.proceso_id = ?
                 ORDER BY e.secuencia""", quien.negocio(), v.get("proceso_id")).stream().map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.get("id"));
                    m.put("secuencia", f.get("secuencia"));
                    m.put("etapa", f.get("etapa"));
                    m.put("fecha", fecha(f.get("fecha")));
                    m.put("documento", f.get("documento"));
                    m.put("autoridad", f.get("autoridad"));
                    m.put("informadoPor", f.get("informado_por"));
                    m.put("regimen", f.get("regimen"));
                    m.put("numeroProceso", f.get("numero_proceso"));
                    m.put("corrigeEtapaId", f.get("corrige_etapa_id"));
                    m.put("registradoPorId", f.get("registrado_por"));
                    m.put("registradoPor", f.get("nombre"));
                    m.put("registradoEn", instante(f.get("registrado_en")));
                    m.put("procedimiento", f.get("procedimiento"));
                    m.put("procedimientoOtro", f.get("procedimiento_otro"));
                    m.put("autoridadTipo", f.get("autoridad_tipo"));
                    m.put("autoridadOtra", f.get("autoridad_otra"));
                    m.put("tipoDeSoporte", tipoDeSoporte((String) f.get("autoridad_tipo")));
                    m.put("quienLlevaElTramite", f.get("tramitador_papel") == null ? null
                            : Map.of("nombre", f.get("tramitador_nombre"), "papel", f.get("tramitador_papel")));
                    return m;
                }).toList());
        // F4.13f: lo vigente del proceso es lo último informado en sus etapas, como el régimen.
        List<Map<String, Object>> etapas = (List<Map<String, Object>>) r.get("etapas");
        Map<String, Object> conProcedimiento = ultimaCon(etapas, "procedimiento");
        r.put("procedimiento", conProcedimiento == null ? null : conProcedimiento.get("procedimiento"));
        r.put("procedimientoOtro", conProcedimiento == null ? null : conProcedimiento.get("procedimientoOtro"));
        // B16 y B18: la ayuda del giro ordinario con Ley 1116; en el CGP, con negociación de deudas o convalidación (cada una
        // con su texto en el panel). Con el régimen pendiente no sale.
        r.put("aplicaAyudaGiroOrdinario", "LEY_1116".equals(v.get("regimen"))
                || ("CGP".equals(v.get("regimen")) && r.get("procedimiento") != null && CGP_CON_AYUDA.contains((String) r.get("procedimiento"))));
        Map<String, Object> conAutoridad = ultimaCon(etapas, "autoridadTipo");
        r.put("autoridadTipo", conAutoridad == null ? null : conAutoridad.get("autoridadTipo"));
        r.put("autoridadOtra", conAutoridad == null ? null : conAutoridad.get("autoridadOtra"));
        r.put("tipoDeSoporte", conAutoridad == null ? null : conAutoridad.get("tipoDeSoporte"));
        Map<String, Object> conTramitador = ultimaCon(etapas, "quienLlevaElTramite");
        r.put("quienLlevaElTramite", conTramitador == null ? null : conTramitador.get("quienLlevaElTramite"));
        return r;
    }

    private static Map<String, Object> ultimaCon(List<Map<String, Object>> etapas, String campo) {
        for (int i = etapas.size() - 1; i >= 0; i--) {
            if (etapas.get(i).get(campo) != null) {
                return etapas.get(i);
            }
        }
        return null;
    }

    /** B16 y B18: «Auto N.º» con Superintendencia o cualquier juez; «Acta N.º» con centro de conciliación o notaría; con otra, no se sabe. */
    static String tipoDeSoporte(String autoridadTipo) {
        if (autoridadTipo == null) {
            return null;
        }
        if (Set.of("SUPERINTENDENCIA_DE_SOCIEDADES", "JUEZ_CIVIL_DEL_CIRCUITO", "JUEZ_CIVIL_MUNICIPAL").contains(autoridadTipo)) {
            return "AUTO";
        }
        return conConciliador(autoridadTipo) ? "ACTA" : null;
    }

    private static boolean conConciliador(String autoridadTipo) {
        return autoridadTipo != null && CON_CONCILIADOR.contains(autoridadTipo);
    }

    private static String mayusculasONulo(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /** GET /api/cartera/clientes/{documento}/insolvencia/foto (admin): la foto vigente factura por factura, con su huella. */
    public Map<String, Object> foto(Quien quien, String documento) {
        String doc = clienteVisible(quien, documento);
        Map<String, Object> v = ultimo(quien.negocio(), doc).filter(f -> f.get("foto_id") != null)
                .orElseThrow(() -> new DatoInvalidoException("documento", "Ese cliente no tiene un proceso de insolvencia iniciado."));
        Map<String, Object> foto = jdbc.queryForMap("""
                SELECT f.id, f.corte, f.facturas, f.total, f.huella, f.reemplaza_foto_id, f.tomada_en, f.tomada_por, u.nombre
                  FROM insolvencia_fotos f LEFT JOIN users u ON u.tenant_id = f.tenant_id AND u.id = f.tomada_por
                 WHERE f.tenant_id = ? AND f.id = ?""", quien.negocio(), v.get("foto_id"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        r.put("procesoId", v.get("proceso_id"));
        r.put("id", foto.get("id"));
        r.put("inicio", fecha(v.get("inicio")));
        r.put("corte", instante(foto.get("corte")));
        r.put("facturas", foto.get("facturas"));
        r.put("total", foto.get("total"));
        r.put("huella", foto.get("huella"));
        r.put("reemplazaFotoId", foto.get("reemplaza_foto_id"));
        r.put("tomadaEn", instante(foto.get("tomada_en")));
        r.put("tomadaPorId", foto.get("tomada_por"));
        r.put("tomadaPor", foto.get("nombre"));
        r.put("filas", jdbc.queryForList("""
                SELECT ff.debito_tx_id, ff.order_uuid, o.id_order, ff.fecha, ff.vence_el, ff.monto, ff.aplicado_al_corte, ff.saldo_al_corte
                  FROM insolvencia_foto_facturas ff
                  LEFT JOIN orders o ON o.tenant_id = ff.tenant_id AND o.uuid_id = ff.order_uuid
                 WHERE ff.tenant_id = ? AND ff.foto_id = ?
                 ORDER BY ff.fecha, ff.debito_tx_id""", quien.negocio(), foto.get("id")).stream().map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("debitoTxId", f.get("debito_tx_id"));
                    m.put("orderUuid", f.get("order_uuid"));
                    m.put("idOrder", f.get("id_order"));
                    m.put("fecha", fecha(f.get("fecha")));
                    m.put("venceEl", fecha(f.get("vence_el")));
                    m.put("monto", f.get("monto"));
                    m.put("aplicadoAlCorte", f.get("aplicado_al_corte"));
                    m.put("saldoAlCorte", f.get("saldo_al_corte"));
                    return m;
                }).toList());
        return r;
    }

    /** La foto en CSV para «Descargar para presentar al proceso»: separador «;» y BOM, como la abre Excel en español. */
    @SuppressWarnings("unchecked")
    public String fotoEnCsv(Quien quien, String documento) {
        Map<String, Object> f = foto(quien, documento);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Cliente;").append(f.get("clienteDocumento")).append('\n');
        csv.append("Inicio del proceso;").append(f.get("inicio")).append('\n');
        csv.append("Corte;").append(f.get("corte")).append('\n');
        csv.append("Huella;").append(f.get("huella")).append('\n');
        csv.append('\n');
        csv.append("Venta N.;Fecha;Vence;Monto;Abonado al corte;Saldo al corte\n");
        for (Map<String, Object> fila : (List<Map<String, Object>>) f.get("filas")) {
            csv.append(fila.get("idOrder") == null ? fila.get("debitoTxId") : fila.get("idOrder")).append(';')
                    .append(fila.get("fecha")).append(';')
                    .append(fila.get("venceEl") == null ? "" : fila.get("venceEl")).append(';')
                    .append(numero(fila.get("monto"))).append(';')
                    .append(numero(fila.get("aplicadoAlCorte"))).append(';')
                    .append(numero(fila.get("saldoAlCorte"))).append('\n');
        }
        csv.append("Total;;;;;").append(numero(f.get("total"))).append('\n');
        return csv.toString();
    }

    /** El crédito después del inicio del proceso en curso (V77): lo último registrado y si vale; null si nunca se registró. */
    Map<String, Object> creditoPosterior(String negocio, Object procesoId) {
        return jdbc.queryForList("""
                SELECT cp.vigente, cp.habilitado, cp.plazo_maximo_dias, cp.motivo, cp.registrado_por, u.nombre, cp.registrado_en
                  FROM v_insolvencia_credito_posterior cp
                  LEFT JOIN users u ON u.tenant_id = cp.tenant_id AND u.id = cp.registrado_por
                 WHERE cp.tenant_id = ? AND cp.proceso_id = ?""", negocio, procesoId).stream().findFirst().map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("habilitado", Boolean.TRUE.equals(f.get("vigente")));
                    m.put("plazoMaximoDias", f.get("plazo_maximo_dias"));
                    m.put("motivo", f.get("motivo"));
                    m.put("registradoPorId", f.get("registrado_por"));
                    m.put("registradoPor", f.get("nombre"));
                    m.put("registradoEn", instante(f.get("registrado_en")));
                    return m;
                }).orElse(null);
    }

    // ------------------------------------------------------------------ piezas

    private void informar(Quien quien, String doc, String etapa, LocalDate fecha, String documento, String autoridad,
                          String informadoPor, String regimen, String numeroProceso, UUID corrige) {
        cartera.fijarAutor(quien);
        jdbc.queryForObject("SELECT fn_insolvencia_informar_etapa(?, ?, ?::date, ?, ?, ?, ?, ?, ?::uuid)", UUID.class,
                doc, etapa, java.sql.Date.valueOf(fecha), documento, recortarONulo(autoridad), informadoPor, regimen,
                recortarONulo(numeroProceso), corrige == null ? null : corrige.toString());
    }

    private static void permitida(String vigente, String nueva) {
        if (vigente == null) {
            if (!Set.of("SOLICITUD", "INICIO").contains(nueva)) {
                throw new ConflictoDeCarteraException(ETAPA_NO_PERMITIDA,
                        "Un proceso de insolvencia empieza con «" + ETIQUETAS.get("SOLICITUD") + "» o con «" + ETIQUETAS.get("INICIO")
                                + "». No se registró nada.");
            }
            return;
        }
        if ("LIQUIDACION".equals(vigente) && Set.of("CORRECCION_DE_ERROR", "CUMPLIDO_TERMINADO").contains(nueva)) {
            throw new ConflictoDeCarteraException(LIQUIDACION_NO_SE_LEVANTA, EN_LIQUIDACION_NO_SE_LEVANTA);
        }
        if (!SIGUIENTES.getOrDefault(vigente, Set.of()).contains(nueva)) {
            throw new ConflictoDeCarteraException(ETAPA_NO_PERMITIDA,
                    "Un proceso en «" + ETIQUETAS.get(vigente) + "» no pasa a «" + ETIQUETAS.get(nueva) + "». No se registró nada.");
        }
    }

    private static void fechaNoFutura(LocalDate fecha, boolean deInicio) {
        if (fecha == null) {
            throw new DatoInvalidoException("fecha", "Falta la fecha que figura en el auto o el acta.");
        }
        if (fecha.isAfter(LocalDate.now(Cartera.BOGOTA))) {
            throw new DatoInvalidoException("fecha", deInicio ? FECHA_POSTERIOR_A_HOY : FECHA_DE_ETAPA_POSTERIOR_A_HOY);
        }
    }

    /** «Informado por» desde el endpoint viejo: el nombre del usuario; sin nombre, su correo (sale en pantalla, ECM). */
    private String nombreDelUsuario(Quien quien) {
        return quien.usuarioId() == null ? "Usuario sin identificar" : jdbc.queryForList(
                "SELECT COALESCE(NULLIF(btrim(nombre), ''), email) FROM users WHERE tenant_id = ? AND id = ?",
                String.class, quien.negocio(), quien.usuarioId()).stream().findFirst().orElse("Usuario sin identificar");
    }

    /** El cliente existe y quien pide lo ve; bloqueado para que dos etapas del mismo cliente no se crucen. */
    private String clienteBloqueado(Quien quien, String documento) {
        String doc = clienteVisible(quien, documento);
        jdbc.query("SELECT 1 FROM clientes WHERE tenant_id = ? AND documento = ? FOR UPDATE", rs -> null, quien.negocio(), doc);
        return doc;
    }

    private String clienteVisible(Quien quien, String documento) {
        String doc = obligatorio(documento, "documento", Cartera.NO_EXISTE);
        if (cartera.fichaVisible(quien, doc) == null) {
            throw new DatoInvalidoException("documento", Cartera.NO_EXISTE);
        }
        return doc;
    }

    private Optional<Map<String, Object>> abierto(String negocio, String doc) {
        return jdbc.queryForList("""
                SELECT * FROM v_insolvencia_vigente WHERE tenant_id = ? AND cliente_documento = ? AND abierto
                 ORDER BY abierto_en DESC LIMIT 1""", negocio, doc).stream().findFirst();
    }

    private Optional<Map<String, Object>> ultimo(String negocio, String doc) {
        return jdbc.queryForList("""
                SELECT * FROM v_insolvencia_vigente WHERE tenant_id = ? AND cliente_documento = ?
                 ORDER BY abierto DESC, abierto_en DESC LIMIT 1""", negocio, doc).stream().findFirst();
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

    private static LocalDate fechaSql(Object o) {
        return o == null ? null : ((java.sql.Date) o).toLocalDate();
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

    private static String numero(Object o) {
        return o == null ? "" : new BigDecimal(o.toString()).stripTrailingZeros().toPlainString();
    }
}
