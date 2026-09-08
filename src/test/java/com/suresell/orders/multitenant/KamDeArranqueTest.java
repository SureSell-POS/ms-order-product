package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** El primer KAM por variable de entorno: las tres reglas del fundador, una por una. */
class KamDeArranqueTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    @DisplayName("sin variables no hace nada")
    void sinVariablesNoHaceNada() {
        SuperAdminRepository repo = mock(SuperAdminRepository.class);
        var k = new KamDeArranque(repo, encoder, "", "");
        assertThat(k.activo()).isFalse();
        assertThat(k.crearSiNoExiste()).isFalse();
        verify(repo, never()).insert(anyString(), anyString());
    }

    @Test
    @DisplayName("con las dos variables y la cuenta ausente, la crea con el hash de la clave")
    void creaSiNoExiste() {
        SuperAdminRepository repo = mock(SuperAdminRepository.class);
        when(repo.findByEmail("kam@staging.invalid")).thenReturn(Optional.empty());

        var k = new KamDeArranque(repo, encoder, "KAM@staging.invalid", "Clave-de-staging-2026");
        assertThat(k.crearSiNoExiste()).isTrue();

        verify(repo).insert(eq("kam@staging.invalid"), org.mockito.ArgumentMatchers.argThat(
                hash -> encoder.matches("Clave-de-staging-2026", hash)));
    }

    @Test
    @DisplayName("🔴 regla 1: si la cuenta ya existe, NO la toca (ni el hash, ni nada)")
    void siExisteNoLaToca() {
        SuperAdminRepository repo = mock(SuperAdminRepository.class);
        when(repo.findByEmail("kam@staging.invalid")).thenReturn(Optional.of(
                new SuperAdminRepository.SuperAdminRow(1, "kam@staging.invalid", "$2a$hash-anterior")));

        var k = new KamDeArranque(repo, encoder, "kam@staging.invalid", "Otra-clave-nueva-2026");
        assertThat(k.crearSiNoExiste()).isFalse();
        verify(repo, never()).insert(anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 regla 3: una clave débil o una variable a medias tumban el arranque, no crean nada")
    void claveDebilOVariableAMediasTumbanElArranque() {
        SuperAdminRepository repo = mock(SuperAdminRepository.class);

        assertThatThrownBy(() -> new KamDeArranque(repo, encoder, "kam@staging.invalid", "shark2026"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KAM_BOOTSTRAP_PASSWORD");
        assertThatThrownBy(() -> new KamDeArranque(repo, encoder, "kam@staging.invalid", "corta1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("12");
        assertThatThrownBy(() -> new KamDeArranque(repo, encoder, "kam@staging.invalid", "solo-letras-sin-numeros"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("letras y números");
        assertThatThrownBy(() -> new KamDeArranque(repo, encoder, "kam@staging.invalid", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("van juntas");
        assertThatThrownBy(() -> new KamDeArranque(repo, encoder, "sin-arroba", "Clave-valida-2026"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("correo");

        verify(repo, never()).insert(anyString(), anyString());
    }
}
