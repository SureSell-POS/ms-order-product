package com.suresell.orders.flujo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * El catálogo de flujos de venta, leído de la base (V48).
 *
 * <p><b>Aquí no hay ningún nombre de flujo escrito.</b> Ni {@code PLAZOLETA},
 * ni {@code RESTAURANTE}, ni {@code DIRECTO}: lo que un flujo significa
 * ({@code usaMesas}, {@code usaRastreador}) y a qué valor viejo equivale son
 * columnas de {@code flujos_de_venta}. Si mañana hace falta un flujo nuevo o
 * cambia una equivalencia, cambia una fila; esta clase no se recompila. Es la
 * misma regla que el perfil vertical (V50 del inventario) para las verticales.
 *
 * <p>Las tres validaciones a mano que había (alta, KAM, sedes) pasan a
 * {@link #resolver(String)}: acepta un código de flujo <i>o</i> un
 * {@code pos_mode} viejo, y lo resuelve contra el catálogo.
 *
 * <p>Sin caché: el catálogo son tres filas y se lee con una consulta por
 * petición que lo necesite. Cachearlo ahorraría nada y añadiría una forma de
 * servir un dato viejo.
 */
@Service
public class FlujosDeVenta {

    /** Una fila del catálogo. */
    public record Flujo(String codigo, String nombre, String descripcion,
                        boolean usaMesas, boolean usaRastreador,
                        String posModeLegado, String heredaDePosMode, boolean esDefecto, int orden) {}

    /** Un flujo admitido por un perfil, y si es el que trae por defecto. */
    public record FlujoDePerfil(Flujo flujo, boolean esDefecto) {}

    private static final String COLUMNAS =
            "codigo, nombre, descripcion, usa_mesas, usa_rastreador, pos_mode_legado, hereda_de_pos_mode, es_defecto, orden";

    private final JdbcTemplate jdbc;

    public FlujosDeVenta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Flujo> todos() {
        return jdbc.query("SELECT " + COLUMNAS + " FROM flujos_de_venta ORDER BY orden", this::fila);
    }

    public Optional<Flujo> porCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNAS + " FROM flujos_de_venta WHERE codigo = ?",
                this::fila, codigo.trim().toUpperCase(Locale.ROOT)).stream().findFirst();
    }

    /**
     * Resuelve lo que mande un cliente: un código de flujo nuevo, o un
     * {@code pos_mode} de los de antes (columna {@code hereda_de_pos_mode}).
     * Vacío si no es ninguna de las dos cosas.
     */
    public Optional<Flujo> resolver(String codigoOPosMode) {
        if (codigoOPosMode == null || codigoOPosMode.isBlank()) {
            return Optional.empty();
        }
        String v = codigoOPosMode.trim().toUpperCase(Locale.ROOT);
        Optional<Flujo> directo = porCodigo(v);
        if (directo.isPresent()) {
            return directo;
        }
        return jdbc.query("SELECT " + COLUMNAS + " FROM flujos_de_venta WHERE hereda_de_pos_mode = ?",
                this::fila, v).stream().findFirst();
    }

    /**
     * Como {@link #resolver(String)} pero falla con el mensaje que lista lo que
     * sí existe, para que el error diga qué mandar.
     */
    public Flujo exigir(String codigoOPosMode) {
        return resolver(codigoOPosMode).orElseThrow(() -> new IllegalArgumentException(
                "Flujo de venta inválido: '" + codigoOPosMode + "'. Válidos: " + nombresValidos()));
    }

    /** Los flujos que admite un perfil vertical, con su defecto, en orden. */
    public List<FlujoDePerfil> admitidosPor(String perfilCodigo) {
        if (perfilCodigo == null || perfilCodigo.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT f.codigo, f.nombre, f.descripcion, f.usa_mesas, f.usa_rastreador, "
                        + "f.pos_mode_legado, f.hereda_de_pos_mode, f.es_defecto, f.orden, p.es_defecto AS defecto_del_perfil "
                        + "FROM perfil_flujos_de_venta p JOIN flujos_de_venta f ON f.codigo = p.flujo "
                        + "WHERE p.perfil_codigo = ? ORDER BY p.es_defecto DESC, f.orden",
                (rs, i) -> new FlujoDePerfil(fila(rs, i), rs.getBoolean("defecto_del_perfil")),
                perfilCodigo.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * El flujo con el que nace una sede cuando nadie dice nada (columna
     * {@code es_defecto}; exactamente una fila). Es lo que PLAZOLETA siempre
     * fue, y su nombre no está aquí.
     */
    public Flujo defectoGlobal() {
        return jdbc.query("SELECT " + COLUMNAS + " FROM flujos_de_venta WHERE es_defecto", this::fila)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "El catálogo flujos_de_venta no tiene flujo por defecto (V48)"));
    }

    public Optional<Flujo> defectoDe(String perfilCodigo) {
        return admitidosPor(perfilCodigo).stream()
                .filter(FlujoDePerfil::esDefecto).map(FlujoDePerfil::flujo).findFirst();
    }

    /** Los perfiles que tienen algún flujo declarado. */
    public List<String> perfilesConFlujo() {
        return jdbc.queryForList(
                "SELECT DISTINCT perfil_codigo FROM perfil_flujos_de_venta ORDER BY perfil_codigo",
                String.class);
    }

    public String nombresValidos() {
        return String.join(" | ", todos().stream().map(Flujo::codigo).toList());
    }

    private Flujo fila(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Flujo(rs.getString("codigo"), rs.getString("nombre"), rs.getString("descripcion"),
                rs.getBoolean("usa_mesas"), rs.getBoolean("usa_rastreador"),
                rs.getString("pos_mode_legado"), rs.getString("hereda_de_pos_mode"),
                rs.getBoolean("es_defecto"), rs.getInt("orden"));
    }
}
