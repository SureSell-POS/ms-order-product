package com.suresell.orders.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Frena la fuerza bruta contra el PIN de registro en caja (V55).
 *
 * <h2>Por qué hace falta</h2>
 *
 * El PIN tiene de 4 a 12 caracteres y lo teclea (o escanea) quien está en la
 * caja. Sin límite, un PIN de 4 dígitos son 10.000 intentos: un script con la
 * sesión de un cajero lo recorre en minutos, y el PIN es la única puerta del
 * registro rápido (contrato CAJA-POR-TURNOS §3).
 *
 * <h2>La regla</h2>
 *
 * {@value #MAX_FALLOS} claves incorrectas en {@value #MINUTOS_VENTANA} minutos
 * para el mismo NEGOCIO → las siguientes peticiones de registro rápido
 * responden 429 {@code DEMASIADOS_INTENTOS} SIN evaluar el PIN, hasta que el
 * fallo más antiguo salga de la ventana. Un acierto reinicia la cuenta, y
 * también que el administrador ponga un PIN nuevo (es la salida que ofrece el
 * mensaje: «pide al administrador que revise la clave»).
 *
 * <p>Por negocio y no por IP ni por usuario: el PIN es del negocio. Contar por
 * usuario o por IP dejaría al atacante rotar sesiones o redes y empezar de
 * cero; el negocio del JWT es la única dimensión que no puede cambiar.
 *
 * <p>Solo cuentan los FALLOS (como el login en {@code RegisterRateLimiter}):
 * quien teclea bien nunca acumula nada.
 *
 * <h2>Por instancia — límite conocido</h2>
 *
 * La cuenta vive en la memoria de ESTE proceso. Railway corre una sola
 * instancia de -mt, así que hoy es exacta. Con varias instancias cada una
 * llevaría su propia cuenta (N × {@value #MAX_FALLOS} intentos por ventana) y
 * un reinicio la borra: en ese momento hay que llevarla a la base (una tabla
 * de intentos por negocio con su ventana) o a un almacén compartido. Sin
 * migración a propósito mientras haya una instancia.
 *
 * <p>El reloj se inyecta ({@link Clock}) para poder probar la ventana sin
 * esperar diez minutos; sin un bean {@code Clock} en el contexto, es el del
 * sistema.
 */
@Component
public class LimiteDeClavesDeRegistro {

    static final int MAX_FALLOS = 5;
    static final int MINUTOS_VENTANA = 10;
    static final Duration VENTANA = Duration.ofMinutes(MINUTOS_VENTANA);

    public static final String MENSAJE =
            "Demasiadas claves incorrectas. Espera 10 minutos o pide al administrador que revise la clave";

    private final Clock reloj;
    /** Negocio → instantes de sus fallos dentro de la ventana. */
    private final Map<String, Deque<Instant>> fallos = new ConcurrentHashMap<>();

    /** El de Spring: el {@code Clock} del contexto si hay uno (los tests), si no el del sistema. */
    @org.springframework.beans.factory.annotation.Autowired
    public LimiteDeClavesDeRegistro(ObjectProvider<Clock> reloj) {
        this(reloj.getIfUnique(Clock::systemUTC));
    }

    /** Para los tests unitarios. */
    public LimiteDeClavesDeRegistro(Clock reloj) {
        this.reloj = reloj;
    }

    /** Lanza {@link DemasiadosIntentos} si el negocio agotó los fallos de la ventana. No consume nada. */
    public void comprobar(String negocio) {
        Deque<Instant> q = colaDe(negocio);
        synchronized (q) {
            Instant ahora = reloj.instant();
            purgar(q, ahora);
            if (q.size() >= MAX_FALLOS) {
                // Se libera un intento cuando el fallo más antiguo sale de la ventana.
                long segundos = Math.max(1, Duration.between(ahora, q.peekFirst().plus(VENTANA)).toSeconds());
                throw new DemasiadosIntentos(segundos);
            }
        }
    }

    /** Anota una clave incorrecta del negocio. */
    public void anotarFallo(String negocio) {
        Deque<Instant> q = colaDe(negocio);
        synchronized (q) {
            Instant ahora = reloj.instant();
            purgar(q, ahora);
            q.addLast(ahora);
        }
    }

    /** Un acierto (o un PIN nuevo del administrador): los fallos previos dejan de contar. */
    public void reiniciar(String negocio) {
        fallos.remove(clave(negocio));
    }

    private Deque<Instant> colaDe(String negocio) {
        return fallos.computeIfAbsent(clave(negocio), k -> new ArrayDeque<>());
    }

    private static String clave(String negocio) {
        return negocio == null || negocio.isBlank() ? "desconocido" : negocio;
    }

    private static void purgar(Deque<Instant> q, Instant ahora) {
        Instant corte = ahora.minus(VENTANA);
        while (!q.isEmpty() && !q.peekFirst().isAfter(corte)) {
            q.pollFirst();
        }
    }

    /** 429 {@code DEMASIADOS_INTENTOS}; {@link #segundos()} va en {@code Retry-After}. */
    public static class DemasiadosIntentos extends RuntimeException {
        private final long segundos;

        public DemasiadosIntentos(long segundos) {
            super(MENSAJE);
            this.segundos = segundos;
        }

        public long segundos() {
            return segundos;
        }
    }
}
