package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** F4.10: el DV de la DIAN, con NIT públicos; el mismo que calcula la base (fn_dv_nit, V67). */
class NitTest {

    @ParameterizedTest(name = "{0} → DV {1}")
    @CsvSource({"800197268,4", "890903938,8", "899999068,1", "860034313,7"})
    @DisplayName("🔴 el DV de NIT públicos (DIAN, Bancolombia, Ecopetrol, Bavaria)")
    void dvDeNitPublicos(String numero, int dv) {
        assertThat(Nit.dv(numero)).isEqualTo(dv);
    }

    @Test
    @DisplayName("separa el DV con guion, con puntos, o pegado en diez dígitos si cuadra; si no cuadra, es el número entero")
    void separar() {
        assertThat(Nit.separar("800197268-4")).isEqualTo(new Nit.Separado("800197268", 4));
        assertThat(Nit.separar("800.197.268-4")).isEqualTo(new Nit.Separado("800197268", 4));
        assertThat(Nit.separar("8001972684")).isEqualTo(new Nit.Separado("800197268", 4));
        assertThat(Nit.separar("8001972685")).isEqualTo(new Nit.Separado("8001972685", null));
        assertThat(Nit.separar("890903938-5")).isEqualTo(new Nit.Separado("890903938", 5));
        assertThat(Nit.dv("80019726A")).isNull();
    }
}
