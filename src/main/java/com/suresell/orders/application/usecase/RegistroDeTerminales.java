package com.suresell.orders.application.usecase;

import com.suresell.orders.domain.model.Terminal;
import com.suresell.orders.infrastructure.persistence.TerminalRepository;
import com.suresell.orders.multitenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da de alta terminales que aparecen por primera vez, y registra su contacto.
 *
 * <h3>El servidor NUNCA rechaza un terminal desconocido</h3>
 *
 * Es la decisión de diseño de V35 y conviene entender por qué: el POS genera su
 * UUID en el primer arranque y puede pasar días vendiendo sin conexión antes de
 * sincronizar. Si el servidor exigiera un registro previo, un local sin internet
 * no podría abrir caja — y perder ventas para proteger un registro es un mal
 * negocio.
 *
 * <p>Así que el terminal desconocido se da de alta con lo que se sabe de él (su
 * UUID y el negocio del JWT) y el administrador le pone nombre después.
 *
 * <h3>Por qué esto no puede tumbar una venta</h3>
 *
 * Corre en una transacción PROPIA ({@code REQUIRES_NEW}) y **se traga sus
 * errores a propósito**, que es la única excepción deliberada al criterio de
 * `FALLBACK-SILENCIOSO.md`: el registro del terminal es metadato, la venta es el
 * hecho. Si el alta falla, la orden se guarda igual con su `terminal_id` — la
 * clave foránea es nullable justo para esto — y el terminal se registrará en la
 * siguiente sincronización.
 *
 * <h3>El choque que sí tumbaba la venta (fase 0, 2026-09-09)</h3>
 *
 * El POS guarda UN UUID por navegador, no por negocio, y {@code terminals.id}
 * es clave primaria global. Cuando un segundo negocio vendía desde el mismo
 * navegador, {@code registrarContacto} devolvía 0 (bajo RLS la fila del otro
 * negocio no existe) y el {@code save()} chocaba con {@code terminals_pkey}.
 * El {@code catch} de aquí abajo <b>no lo atrapaba</b>: Hibernate sólo manda
 * el INSERT al confirmar, y la confirmación de una {@code REQUIRES_NEW} ocurre
 * al salir del método, ya fuera del {@code try}. La venta salía con 500 y el
 * POS la disfrazaba de «guardada offline».
 *
 * <p>Se cerró en dos pasos. Primero el alta pasó a {@code INSERT … ON CONFLICT
 * DO NOTHING}, que evita el 500 pero dejaba la orden apuntando al terminal de
 * otro negocio (invisible, no imposible). Después, <b>V50</b>: la identidad
 * del terminal es (negocio, UUID), la clave primaria lleva el negocio y la FK
 * de {@code orders} es (tenant_id, terminal_id). El mismo navegador con dos
 * cuentas tiene dos filas, cada negocio ve la suya bajo RLS, y una orden no
 * puede apuntar fuera de su negocio: la base lo rechaza. El POS no cambia.
 *
 * <p>La degradación (si el alta falla por otra cosa) no es invisible: queda el
 * WARN y, sobre todo, queda la propia orden con un `terminal_id` que no está
 * en `terminals` para su negocio… salvo que desde V50 eso ya no entra: la FK
 * lo impide, y entonces es la venta la que falla con 422 y texto, no en
 * silencio.
 */
@Log4j2
@Service
public class RegistroDeTerminales {

    private final TerminalRepository repositorio;

    public RegistroDeTerminales(TerminalRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * Asegura que el terminal existe y anota su contacto.
     *
     * @param id    UUID que generó el cliente. Si es null no hace nada: un
     *              cliente viejo que no manda terminal sigue vendiendo
     * @param epoch epoch que declara el cliente; 1 si no lo manda
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void asegurarRegistrado(UUID id, Integer epoch) {
        if (id == null) {
            return;
        }
        int epochSeguro = (epoch == null || epoch < 1) ? 1 : epoch;
        try {
            if (repositorio.registrarContacto(id, epochSeguro, OffsetDateTime.now()) == 0) {
                darDeAlta(id, epochSeguro);
            }
        } catch (Exception e) {
            // Ver el javadoc: el registro es metadato, la venta es el hecho.
            log.warn("No se pudo registrar el terminal {} ({}). La venta sigue adelante; "
                    + "el terminal quedara registrado en la proxima sincronizacion.",
                    id, e.getClass().getSimpleName());
        }
    }

    private void darDeAlta(UUID id, int epoch) {
        // `tenant_id` se pone aquí a mano: es SQL nativo y el TenantEntityListener
        // no interviene. `site_id`, `codigo` y `alias` quedan nulos: el servidor
        // no puede saber cómo llama el negocio a esta caja y no se lo inventa.
        String negocio = TenantContext.get();
        OffsetDateTime ahora = OffsetDateTime.now();
        int filas = repositorio.darDeAltaSiNoExiste(id, negocio, Terminal.ACTIVO, ahora, epoch);
        if (filas == 1) {
            log.info("Terminal {} dado de alta automaticamente (epoch {})", id, epoch);
            return;
        }
        // 0 filas: desde V50 la clave es (negocio, UUID), así que esto solo
        // pasa si OTRA petición de este mismo negocio lo dio de alta entre el
        // UPDATE y el INSERT. No es un error de la venta. (Antes de V50 aquí
        // vivía el WARN de «ya está a nombre de otro negocio»; ese caso ya no
        // existe: cada negocio tiene su fila para el mismo UUID.)
        log.debug("Terminal {} dado de alta por otra peticion concurrente del negocio '{}'", id, negocio);
    }
}
