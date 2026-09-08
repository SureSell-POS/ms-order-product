package com.suresell.orders.multitenant;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * El primer KAM de un entorno, por variable de entorno.
 *
 * <p><b>Por qué existe.</b> No había forma de crear un super-admin salvo un
 * {@code INSERT} a mano en {@code super_admins} con un hash BCrypt, y V8 lo
 * resolvió sembrando {@code kam@suresell.co} con una clave escrita en un
 * comentario del repositorio: acabó como la deuda C9 de la rotación de
 * credenciales. Esto lo sustituye por el mismo mecanismo que
 * {@code AUTH_REGISTER_KEY}: el secreto vive en la variable del entorno,
 * distinto en cada uno, y rotarlo es cambiar la variable.
 *
 * <p><b>Reglas (fundador, 2026-09-08):</b>
 * <ol>
 *   <li>Si la cuenta ya existe, <b>no la toca</b>: ni el hash, ni el correo, ni
 *       nada. Si pisara, la variable sería una vía para cambiar la clave del
 *       super-admin desde Railway sin dejar rastro.</li>
 *   <li>Deja huella al crearla: una línea de log con el correo y sin la clave.
 *       Si aparece en producción y nadie la puso, hay que enterarse.</li>
 *   <li>Valida la clave al arrancar ({@link ClaveDeKam}); una variable mal
 *       puesta tumba el arranque, no crea un KAM débil en silencio.</li>
 * </ol>
 *
 * <p><b>En producción la variable NO se define.</b> El KAM de producción ya
 * existe. Esto es para staging y para entornos nuevos. Una variable «por si
 * acaso» acaba definida en todas partes; por eso consta aquí y en el
 * procedimiento (docs/operacion).
 *
 * <p>Sin las dos variables no hace nada. Con una sola, falla el arranque: a
 * medio configurar es un error, no un «casi».
 */
@Component
@Profile("cloud")
public class KamDeArranque {

    private static final Logger log = LoggerFactory.getLogger(KamDeArranque.class);

    private final SuperAdminRepository repo;
    private final BCryptPasswordEncoder encoder;
    private final String email;
    private final String clave;

    // Con dos constructores Spring no sabe cuál usar (mismo caso que
    // AltaDeNegocioService): sin el @Autowired busca el vacío y no arranca.
    @org.springframework.beans.factory.annotation.Autowired
    public KamDeArranque(SuperAdminRepository repo,
                         @Value("${kam.bootstrap.email:}") String email,
                         @Value("${kam.bootstrap.password:}") String clave) {
        this(repo, new BCryptPasswordEncoder(), email, clave);
    }

    KamDeArranque(SuperAdminRepository repo, BCryptPasswordEncoder encoder, String email, String clave) {
        this.repo = repo;
        this.encoder = encoder;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.clave = clave == null ? "" : clave;
        // Falla AQUÍ, al construir el bean: el servicio no arranca con una
        // variable a medias o una clave débil.
        if (activo()) {
            if (!this.email.contains("@")) {
                throw new IllegalStateException("KAM_BOOTSTRAP_EMAIL no parece un correo: " + this.email);
            }
            try {
                ClaveDeKam.validar(this.clave, this.email);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("KAM_BOOTSTRAP_PASSWORD: " + e.getMessage(), e);
            }
        } else if (!this.email.isBlank() || !this.clave.isBlank()) {
            throw new IllegalStateException(
                    "KAM_BOOTSTRAP_EMAIL y KAM_BOOTSTRAP_PASSWORD van juntas: falta una de las dos");
        }
    }

    boolean activo() {
        return !email.isBlank() && !clave.isBlank();
    }

    /** Tras el arranque completo (Flyway ya corrió): crea si no existe; si existe, no toca. */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        crearSiNoExiste();
    }

    /** @return true si creó la cuenta. */
    boolean crearSiNoExiste() {
        if (!activo()) {
            return false;
        }
        if (repo.findByEmail(email).isPresent()) {
            log.info("KAM de arranque: la cuenta {} ya existe; no se toca", email);
            return false;
        }
        repo.insert(email, encoder.encode(clave));
        log.warn("KAM de arranque CREADO: {} (desde KAM_BOOTSTRAP_EMAIL; en producción esta variable no se define)",
                email);
        return true;
    }
}
