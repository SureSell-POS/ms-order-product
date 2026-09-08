package com.suresell.orders.multitenant;

import java.util.Locale;
import java.util.Set;

/**
 * Lo mínimo que se le exige a la clave de una cuenta de KAM, venga por
 * variable de entorno ({@link KamDeArranque}) o por {@code POST /admin/super-admins}.
 *
 * <p>Mismo criterio que {@code JwtSecretValidator}: una variable mal puesta no
 * puede producir un super-admin con clave débil en silencio. Se valida al
 * arrancar, antes de tocar la base.
 */
final class ClaveDeKam {

    static final int MINIMO = 12;

    /** Claves que ya han estado escritas en algún sitio de este repositorio o son de manual. */
    private static final Set<String> TRIVIALES = Set.of(
            "shark2026", "password", "contrasena", "contraseña", "123456789012", "qwertyuiop12",
            "suresell2026", "kam2026", "admin1234567");

    private ClaveDeKam() {}

    static void validar(String clave, String email) {
        if (clave == null || clave.isBlank()) {
            throw new IllegalArgumentException("La clave del KAM es obligatoria");
        }
        String c = clave.trim();
        if (c.length() < MINIMO) {
            throw new IllegalArgumentException("La clave del KAM debe tener al menos " + MINIMO + " caracteres");
        }
        String bajo = c.toLowerCase(Locale.ROOT);
        if (TRIVIALES.contains(bajo)) {
            throw new IllegalArgumentException("La clave del KAM es trivial; elige otra");
        }
        if (email != null && !email.isBlank() && bajo.contains(email.toLowerCase(Locale.ROOT).split("@")[0])) {
            throw new IllegalArgumentException("La clave del KAM no puede contener el email");
        }
        boolean letras = c.chars().anyMatch(Character::isLetter);
        boolean digitos = c.chars().anyMatch(Character::isDigit);
        if (!letras || !digitos) {
            throw new IllegalArgumentException("La clave del KAM debe mezclar letras y números");
        }
        long distintos = bajo.chars().distinct().count();
        if (distintos < 5) {
            throw new IllegalArgumentException("La clave del KAM repite demasiado; elige otra");
        }
    }
}
