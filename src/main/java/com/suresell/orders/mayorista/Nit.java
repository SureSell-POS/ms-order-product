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
     * Separa el DV si viene: «900123456-7» (con guion; también con puntos o
     * espacios) o «9001234567» (diez dígitos cuyo último es el DV de los nueve
     * primeros). Sin guion no hay forma de saber si el décimo dígito es el DV o
     * parte de un NIT de persona natural: solo se separa si cuadra, y si no
     * cuadra se toma como número entero (el DV se calcula).
     */
    public static Separado separar(String documento) {
        String limpio = documento == null ? "" : documento.replaceAll("[\\s.]", "");
        java.util.regex.Matcher conGuion = java.util.regex.Pattern.compile("^([0-9]{1,15})-([0-9])$").matcher(limpio);
        if (conGuion.matches()) {
            return new Separado(conGuion.group(1), Integer.parseInt(conGuion.group(2)));
        }
        if (limpio.matches("^[0-9]{10}$")) {
            String nueve = limpio.substring(0, 9);
            int ultimo = limpio.charAt(9) - '0';
            if (Integer.valueOf(ultimo).equals(dv(nueve))) {
                return new Separado(nueve, ultimo);
            }
        }
        return new Separado(limpio, null);
    }
}
