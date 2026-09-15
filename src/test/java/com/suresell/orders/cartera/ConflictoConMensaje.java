package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Un 409 de /api/cartera como lo lee quien lo pinta: el código y un {@code message} con texto (igual en {@code mensaje}).
 * El POS pinta el {@code message} del servidor; un 409 sin él cae en un texto genérico que engaña («Ya existe uno igual…»).
 */
final class ConflictoConMensaje {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConflictoConMensaje() {}

    static ResultMatcher de(String codigo) {
        return r -> {
            assertThat(r.getResponse().getStatus()).as("409 de " + codigo).isEqualTo(409);
            JsonNode cuerpo = JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(cuerpo.path("codigo").asText()).isEqualTo(codigo);
            assertThat(cuerpo.path("message").asText()).as("message de " + codigo).isNotBlank();
            assertThat(cuerpo.path("mensaje").asText()).as("mensaje de " + codigo).isEqualTo(cuerpo.path("message").asText());
        };
    }
}
