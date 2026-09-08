package com.suresell.orders.multitenant;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * El perfil vertical de un negocio, visto desde el KAM.
 *
 * <p>El catálogo de perfiles y el libro de asignaciones viven en el esquema
 * {@code inventario} (V50 de ms-smart-inventory), en la MISMA base y con el
 * mismo usuario de aplicación. Este servicio no cambia ese esquema: lo lee y
 * le anota una fila en {@code perfil_asignado}, que es exactamente lo que V50
 * previó para el KAM ({@code fuente = 'asignado_por_suresell'}).
 *
 * <p><b>Por qué desde aquí y no por HTTP al inventario.</b> El token del KAM no
 * identifica un negocio y el filtro del inventario lo rechaza (403). Y el alta
 * tiene que ser todo o nada: negocio, admin, sede y perfil en una transacción.
 * Una llamada máquina a máquina no participa de la transacción y ya hay dos
 * en el sistema con fallos silenciosos documentados.
 *
 * <p><b>Cuando el esquema no existe.</b> En un Postgres con solo la cadena
 * {@code public} (la suite de este servicio) no hay perfiles. Se pregunta con
 * {@code to_regclass}, nunca capturando la excepción: «no está» es un dato,
 * «falló» es otro. Sin esquema, {@link #catalogo()} devuelve solo los códigos
 * que {@code perfil_flujos_de_venta} conoce, y asignar no escribe nada.
 */
@Service
public class PerfilDelNegocio {

    /** Un perfil del catálogo, con los flujos que admite. */
    public record Perfil(String codigo, String nombre, String descripcion,
                         List<String> capacidades, List<FlujoAdmitido> flujos) {}

    public record FlujoAdmitido(String codigo, String nombre, boolean usaMesas,
                                boolean usaRastreador, boolean esDefecto) {}

    /** El perfil vigente de un negocio, tal como lo cuenta el libro. */
    public record Vigente(String codigo, String nombre, String fuente, int confianza,
                          String asignadoPor, java.time.OffsetDateTime asignadoEn) {}

    static final String FUENTE_KAM = "asignado_por_suresell";
    static final int CONFIANZA_KAM = 3;

    private final JdbcTemplate jdbc;

    public PerfilDelNegocio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ¿Existe el esquema del inventario en esta base? Se mide, no se supone. */
    public boolean hayCatalogo() {
        Boolean hay = jdbc.queryForObject(
                "SELECT to_regclass('inventario.perfiles_verticales') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(hay);
    }

    /**
     * Los perfiles canónicos (sin negocio dueño) con sus flujos. El orden y los
     * nombres salen de la base. Un perfil del catálogo sin flujo declarado
     * aparece igual, con la lista vacía: es visible que le falta la fila.
     */
    public List<Perfil> catalogo() {
        List<Map<String, Object>> filas;
        if (hayCatalogo()) {
            filas = jdbc.queryForList(
                    "SELECT codigo, nombre, descripcion, capacidades "
                            + "FROM inventario.perfiles_verticales WHERE tenant_id IS NULL ORDER BY codigo");
        } else {
            filas = jdbc.queryForList(
                    "SELECT DISTINCT perfil_codigo AS codigo, perfil_codigo AS nombre, "
                            + "'' AS descripcion, NULL AS capacidades "
                            + "FROM perfil_flujos_de_venta ORDER BY perfil_codigo");
        }
        return filas.stream().map(f -> new Perfil(
                (String) f.get("codigo"), (String) f.get("nombre"),
                f.get("descripcion") == null ? "" : (String) f.get("descripcion"),
                capacidades(f.get("capacidades")),
                flujosDe((String) f.get("codigo")))).toList();
    }

    public Optional<Perfil> porCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        String c = codigo.trim().toLowerCase(Locale.ROOT);
        return catalogo().stream().filter(p -> p.codigo().equals(c)).findFirst();
    }

    /**
     * El perfil vigente de un negocio. El negocio va EXPLÍCITO en la consulta y
     * además tiene que estar fijado en la transacción ({@code app.tenant_id}):
     * la vista respeta RLS, así que con el usuario de aplicación solo se ve lo
     * del negocio en sesión. Explícito, porque «lo que diga la sesión» sin más
     * es cómo un dato acaba en el negocio equivocado sin que nadie lo note.
     */
    public Optional<Vigente> vigente(String tenantId) {
        if (!hayCatalogo()) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT codigo, nombre, fuente, confianza, asignado_por, asignado_en "
                        + "FROM inventario.v_perfil_vigente WHERE tenant_id = ?",
                (rs, i) -> new Vigente(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("fuente"), rs.getInt("confianza"), rs.getString("asignado_por"),
                        rs.getObject("asignado_en", java.time.OffsetDateTime.class)),
                tenantId)
                .stream().findFirst();
    }

    /**
     * Anota la asignación del KAM. Es una fila nueva en un libro append-only:
     * la anterior se queda como lo que se sabía entonces. Con el usuario de
     * aplicación requiere además el negocio fijado en la transacción
     * ({@code set_config('app.tenant_id', …, true)}): {@code perfil_asignado}
     * está en FORCE RLS y sin negocio en sesión la política rechaza la fila.
     *
     * @return true si escribió; false si no hay esquema de inventario en esta base.
     */
    public boolean asignarPorElKam(String tenantId, String perfilCodigo, String kamEmail, String referencia) {
        if (!hayCatalogo()) {
            return false;
        }
        if (kamEmail == null || kamEmail.isBlank()) {
            throw new IllegalArgumentException("Falta quién asigna el perfil (el KAM)");
        }
        // `tenant_id` va explícito y no por el DEFAULT de la columna: el DEFAULT
        // lee la sesión, y fuera de una transacción con el negocio fijado
        // quedaría NULL. La política RLS (WITH CHECK) sigue exigiendo que
        // coincida con el negocio en sesión cuando corre el usuario de aplicación.
        jdbc.update(
                "INSERT INTO inventario.perfil_asignado "
                        + "(tenant_id, perfil_codigo, usuario_id, fuente, referencia, confianza, ocurrido_en) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now())",
                tenantId, perfilCodigo, kamEmail, FUENTE_KAM, referencia, CONFIANZA_KAM);
        return true;
    }

    private List<FlujoAdmitido> flujosDe(String perfilCodigo) {
        return jdbc.query(
                "SELECT f.codigo, f.nombre, f.usa_mesas, f.usa_rastreador, p.es_defecto "
                        + "FROM perfil_flujos_de_venta p JOIN flujos_de_venta f ON f.codigo = p.flujo "
                        + "WHERE p.perfil_codigo = ? ORDER BY p.es_defecto DESC, f.orden",
                (rs, i) -> new FlujoAdmitido(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getBoolean("usa_mesas"), rs.getBoolean("usa_rastreador"),
                        rs.getBoolean("es_defecto")),
                perfilCodigo);
    }

    @SuppressWarnings("unchecked")
    private static List<String> capacidades(Object valor) {
        if (valor == null) {
            return List.of();
        }
        try {
            if (valor instanceof java.sql.Array a) {
                Object[] arr = (Object[]) a.getArray();
                return java.util.Arrays.stream(arr).map(String::valueOf).toList();
            }
        } catch (java.sql.SQLException e) {
            return List.of();
        }
        if (valor instanceof List<?> l) {
            return (List<String>) l;
        }
        return List.of();
    }
}
