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
    static final String LEVANTADA_DESDE_LA_MARCA = "Levantada desde la marca anterior";
    static final String FECHA_CORREGIDA_DESDE_LA_MARCA = "Fecha de inicio corregida desde la marca anterior";

    private final JdbcTemplate jdbc;
    private final Cartera cartera;

    public ProcesoDeInsolvencia(JdbcTemplate jdbc, Cartera cartera) {
        this.jdbc = jdbc;
        this.cartera = cartera;
    }

    public record NuevaEtapa(String etapa, LocalDate fecha, String documento, String autoridad, String informadoPor,
                             String regimen, String numeroProceso, UUID corrigeEtapaId) {}

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
        informar(quien, doc, etapa, e.fecha(), documentoDeLaEtapa, e.autoridad(), informadoPor, regimen, e.numeroProceso(),
                e.corrigeEtapaId());
        return proceso(quien, doc);
    }

    /**
     * POST /api/cartera/clientes/{documento}/insolvencia (admin), el endpoint de F4.4 que consume el panel (R10). Con fecha:
     * INICIO sin documento y con régimen pendiente (o corrige la fecha del INICIO si ya estaba en proceso). Con null: se
     * levanta como corrección de error, salvo en LIQUIDACION (409). Responde lo mismo que antes.
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
            informar(quien, doc, "CORRECCION_DE_ERROR", LocalDate.now(Cartera.BOGOTA), LEVANTADA_DESDE_LA_MARCA, null, autor,
                    null, null, null);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clienteDocumento", doc);
        r.put("enInsolvenciaDesde", jdbc.queryForList("SELECT en_insolvencia_desde FROM clientes WHERE tenant_id = ? AND documento = ?",
                java.sql.Date.class, quien.negocio(), doc).stream().filter(java.util.Objects::nonNull).findFirst()
                .map(d -> d.toLocalDate().toString()).orElse(null));
        return r;
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
            cifras.put("deudaPosteriorAlInicio", jdbc.queryForObject("""
                    SELECT COALESCE(sum(d.saldo), 0) FROM v_insolvencia_clasificacion k
                      JOIN v_cartera_por_documento d ON d.tenant_id = k.tenant_id AND d.debito_tx_id = k.debito_tx_id
                     WHERE k.tenant_id = ? AND k.cliente_documento = ? AND k.clasificacion = 'POSTERIOR'""",
                    BigDecimal.class, quien.negocio(), doc));
            r.put("cifras", cifras);
        } else {
            r.put("cifras", null);
        }
        r.put("etapas", jdbc.queryForList("""
                SELECT e.id, e.secuencia, e.etapa, e.fecha, e.documento, e.autoridad, e.informado_por, e.regimen,
                       e.numero_proceso, e.corrige_etapa_id, e.registrado_por, u.nombre, e.registrado_en
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
                    return m;
                }).toList());
        return r;
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
