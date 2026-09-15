package com.suresell.orders.application.usecase;

import java.util.Locale;

/**
 * F5.8e: cómo se compara un texto de búsqueda sin tildes ni mayúsculas, en UN solo sitio para Java y para SQL.
 *
 * <p>Sin la extensión {@code unaccent} (en staging está disponible pero no instalada; decisión de ECM, 2026-09-15): se
 * traducen las vocales con tilde, diéresis o grave y la ñ con {@code translate}, que es del núcleo e IMMUTABLE (sirve
 * para un índice de expresión), y DESPUÉS {@code lower}. El orden importa: con collation C, {@code lower('Á')} no toca
 * la letra, así que las mayúsculas con tilde se traducen antes. {@code NormalizacionDeBusquedaTest} compara el
 * resultado de Java con el de la base para que no diverjan.
 *
 * <p>Límite aceptado: otras marcas (ç, ã…) no se normalizan; para catálogos en español basta.
 */
public final class NormalizacionDeBusqueda {

    public static final String CON_MARCA = "ÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑáàäéèëíìïóòöúùüñ";
    public static final String SIN_MARCA = "AAAEEEIIIOOOUUUNaaaeeeiiiooouuun";

    private NormalizacionDeBusqueda() {
    }

    /** La misma transformación que {@link #sql(String)} sobre una columna. */
    public static String normalizar(String texto) {
        if (texto == null) {
            return null;
        }
        StringBuilder r = new StringBuilder(texto.length());
        for (char ch : texto.toCharArray()) {
            int i = CON_MARCA.indexOf(ch);
            r.append(i >= 0 ? SIN_MARCA.charAt(i) : ch);
        }
        return r.toString().toLowerCase(Locale.ROOT);
    }

    /** La expresión SQL que normaliza {@code columna}. */
    public static String sql(String columna) {
        return "lower(translate(" + columna + ", '" + CON_MARCA + "', '" + SIN_MARCA + "'))";
    }
}
