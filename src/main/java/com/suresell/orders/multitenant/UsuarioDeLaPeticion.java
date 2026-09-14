package com.suresell.orders.multitenant;

import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Quién está haciendo la petición en curso, como identidad real ({@code users.id}).
 *
 * <h3>Por qué un id y no un nombre</h3>
 *
 * Toda la autoría que existía en este esquema es {@code TEXT} con el nombre de
 * la persona: {@code created_by}, {@code deleted_by}, {@code user_name}. Dos
 * empleados homónimos, o un cambio de nombre, y la trazabilidad se rompe sin
 * que nada avise. V37 lo cambia por una FK a {@code users}.
 *
 * <h3>Sin petición en curso devuelve vacío, no inventa</h3>
 *
 * Un proceso automático no tiene usuario de petición. El llamador debe firmar
 * entonces con {@link UsuarioDeSistema}, que es la convención de la regla 4 —
 * no dejar el campo vacío ni atribuirle la acción a la última persona que pasó
 * por ahí.
 */
@Log4j2
@Component
@Profile("cloud")
public class UsuarioDeLaPeticion {

    private final JwtTenantResolver resolver;
    private final AuthRepository usuarios;

    public UsuarioDeLaPeticion(JwtTenantResolver resolver, AuthRepository usuarios) {
        this.resolver = resolver;
        this.usuarios = usuarios;
    }

    /** Rol del token de la petición actual (claim {@code role}), si lo hay. */
    public Optional<String> rol() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return Optional.empty();
        }
        return resolver.resolveRole(sra.getRequest().getHeader("Authorization"));
    }

    /** Id de {@code users} del autor de la petición actual, si lo hay. */
    public Optional<Long> id() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes sra)) {
                return Optional.empty();   // tarea de fondo: firma el sistema
            }
            String cabecera = sra.getRequest().getHeader("Authorization");
            return resolver.resolveSubject(cabecera)
                    .flatMap(usuarios::findUserByEmail)
                    .map(AuthRepository.UserRow::id);
        } catch (Exception e) {
            // Esto corre en el camino de una venta. Un fallo resolviendo el
            // autor no puede tumbarla: se registra sin firma, que es peor que
            // firmada pero infinitamente mejor que no vender.
            log.warn("No se pudo resolver el autor de la peticion ({}). "
                    + "La operacion sigue sin firma.", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
