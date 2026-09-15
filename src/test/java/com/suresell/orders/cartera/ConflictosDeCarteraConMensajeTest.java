package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.suresell.orders.shared.exception.ClienteEnInsolvenciaException;
import com.suresell.orders.shared.exception.ConflictoDeCarteraException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pedido de C (F4.13 b): ningún 409 de /api/cartera sale sin {@code message}. Cada código de conflicto de la cartera tiene
 * que estar comprobado por la API con {@link ConflictoConMensaje#de(String)} en alguna prueba de este paquete, y un conflicto
 * sin texto no se puede ni construir.
 */
class ConflictosDeCarteraConMensajeTest {

    @Test
    @DisplayName("🔴 todo código 409 de la cartera se comprueba por la API con message (ConflictoConMensaje.de)")
    void todoCodigoComprobado() throws Exception {
        List<String> codigos = new ArrayList<>();
        for (Field f : ConflictoDeCarteraException.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                codigos.add((String) f.get(null));
            }
        }
        codigos.addAll(List.of(ClienteEnInsolvenciaException.CODIGO, Cartera.ABONO_A_DEUDA_ANTERIOR,
                ProcesoDeInsolvencia.LEVANTAR_SIN_ETAPA, ProcesoDeInsolvencia.LIQUIDACION_NO_SE_LEVANTA,
                ProcesoDeInsolvencia.ETAPA_NO_PERMITIDA, ProcesoDeInsolvencia.SIN_PROCESO_EN_CURSO,
                ProcesoDeInsolvencia.CREDITO_POSTERIOR_EN_LIQUIDACION));
        String pruebas;
        try (Stream<Path> ficheros = Files.list(Path.of("src/test/java/com/suresell/orders/cartera"))) {
            pruebas = String.join("\n", ficheros.filter(p -> p.toString().endsWith("Test.java")).map(p -> {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList());
        }
        assertThat(codigos).hasSizeGreaterThanOrEqualTo(10)
                .allSatisfy(c -> assertThat(pruebas).as("sin prueba de message para " + c).contains("ConflictoConMensaje.de(\"" + c + "\")"));
    }

    @Test
    @DisplayName("🔴 un ConflictoDeCarteraException sin texto no se construye")
    void sinTextoNoSeConstruye() {
        assertThatThrownBy(() -> new ConflictoDeCarteraException("X", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConflictoDeCarteraException("X", "  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
