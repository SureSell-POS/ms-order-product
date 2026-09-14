package com.suresell.orders.mayorista;

/**
 * El NIT y su dígito de verificación (plan de mayoristas F4.10). El algoritmo es
 * el de la DIAN y la base tiene el mismo ({@code fn_dv_nit}, V67): este lado
 * responde con {@code campo}; la base es el suelo.
 */
public final class Nit {

    private static final int[] PESOS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    private Nit() {
    }

    /** El documento como llega, separado en número y DV (null si no venía). */
    public record Separado(String numero, Integer dv) {}

    /** DV del número (solo dígitos, 1 a 15), o null si no es un número válido. */
    public static Integer dv(String numero) {
        if (numero == null || !numero.matches("^[0-9]{1,15}$")) {
            return null;
        }
        int suma = 0;
        for (int i = 0; i < numero.length(); i++) {
            suma += (numero.charAt(numero.length() - 1 - i) - '0') * PESOS[i];
        }
        int residuo = suma % 11;
        return residuo <= 1 ? residuo : 11 - residuo;
    }

    /**
     * Separa el DV SOLO si viene con guion: «900123456-7» (también con puntos o
     * espacios). Sin guion nunca se separa: el NIT de una persona natural es su
     * cédula, muchas tienen 10 dígitos, y más o menos 1 de cada 11 terminaría en
     * un dígito que coincide por azar con el DV de los nueve primeros. Cortarla
     * dañaría la clave del cliente; un DV mal tecleado sin guion es un fallo menor
     * (ECM, 2026-09-14). Sin guion, el valor es el número entero y el DV se calcula.
     */
    public static Separado separar(String documento) {
        String limpio = documento == null ? "" : documento.replaceAll("[\\s.]", "");
        java.util.regex.Matcher conGuion = java.util.regex.Pattern.compile("^([0-9]{1,15})-([0-9])$").matcher(limpio);
        if (conGuion.matches()) {
            return new Separado(conGuion.group(1), Integer.parseInt(conGuion.group(2)));
        }
        return new Separado(limpio, null);
    }
}
