package com.suresell.orders.application.usecase;

import com.suresell.orders.domain.model.Site;
import com.suresell.orders.flujo.FlujosDeVenta;
import com.suresell.orders.infrastructure.persistence.SiteRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sedes y flujo de venta (V48; antes «modo de POS», Inc. 1 del modo Restaurante).
 *
 * <p>El flujo es <b>server-authoritative</b>: la UI lo refleja, no lo decide. Y
 * solo lo cambia el KAM, porque es parte de lo que se le vende al negocio, no
 * una preferencia del cliente.
 *
 * <p>Desde V48 esta clase no conoce ningún flujo por nombre: qué significa cada
 * uno ({@code usaMesas}, {@code usaRastreador}) y a qué valor viejo equivale lo
 * dice el catálogo ({@link FlujosDeVenta}). {@link #modoEfectivo()} y
 * {@link #enModoRestaurante()} se conservan porque los llaman los clientes
 * viejos y {@code WaiterService}; ahora salen del dato.
 */
@Service
@RequiredArgsConstructor
public class SiteService {

    private final SiteRepository repository;
    private final FlujosDeVenta flujos;

    public List<Site> listar() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * Sede por defecto del negocio. Si el tenant todavía no tiene ninguna (p.ej.
     * se creó después de la migración), se devuelve vacío y el llamante trata el
     * caso como lo de siempre — nunca se inventa una sede.
     */
    public Optional<Site> sedePorDefecto() {
        return repository.findFirstByIsDefaultTrue();
    }

    /**
     * El flujo efectivo del negocio: el de su sede por defecto. Sin sede ⇒ el
     * flujo por defecto del catálogo, que es lo que un negocio sin sede siempre
     * fue (V23: «sin sede configurada ⇒ PLAZOLETA»; hoy esa fila lleva
     * {@code es_defecto}).
     */
    public FlujosDeVenta.Flujo flujoEfectivo() {
        Optional<Site> sede = sedePorDefecto();
        if (sede.isPresent() && sede.get().getFlujoDeVenta() != null) {
            Optional<FlujosDeVenta.Flujo> f = flujos.porCodigo(sede.get().getFlujoDeVenta());
            if (f.isPresent()) {
                return f.get();
            }
        }
        return flujos.defectoGlobal();
    }

    /**
     * V52 — ¿La sede efectiva imprime tirilla? Sin sede, sí: es lo que siempre
     * fue, y un negocio que imprime no puede dejar de hacerlo por falta de fila.
     */
    public boolean imprimeTirillaEfectiva() {
        return sedePorDefecto().map(s -> !Boolean.FALSE.equals(s.getImprimeTirilla())).orElse(true);
    }

    /** Modo efectivo para los lectores viejos ({@code posMode}). Sale del catálogo. */
    public String modoEfectivo() {
        return flujoEfectivo().posModeLegado();
    }

    /** ¿El flujo efectivo abre cuenta por mesa? Antes: «¿está en RESTAURANTE?». */
    public boolean enModoRestaurante() {
        return flujoEfectivo().usaMesas();
    }

    /**
     * Cambia el flujo de una sede. Solo lo invoca el KAM. Acepta un código del
     * catálogo o un {@code posMode} viejo, y lo resuelve contra la base.
     */
    @Transactional
    public Site cambiarModo(Long siteId, String flujoOPosMode) {
        FlujosDeVenta.Flujo flujo = flujos.exigir(flujoOPosMode);
        Site sede = repository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la sede " + siteId));
        aplicar(sede, flujo);
        return repository.save(sede);
    }

    /**
     * Normaliza el código de la sede. Quita TILDES antes de filtrar: sin eso
     * "Sede Chicó" quedaba como "SEDE-CHIC-" (la ó se volvía guion y dejaba uno
     * colgando), y ese código es la base de la numeración de facturas.
     */
    static String normalizarCodigo(String texto) {
        String sinTildes = java.text.Normalizer
                .normalize(texto.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String code = sinTildes.toUpperCase().replaceAll("[^A-Z0-9]+", "-");
        return code.replaceAll("^-+|-+$", "");
    }

    /**
     * Crea una sede adicional. El multisede completo queda fuera de alcance.
     *
     * <p>Sin flujo declarado, la sede nueva hereda el de la sede por defecto del
     * negocio: una segunda sede de una droguería vende directo, sin que nadie
     * tenga que decirlo. Antes, cualquier cosa que no fuera RESTAURANTE caía a
     * PLAZOLETA en silencio; ahora un flujo que no existe se rechaza.
     */
    @Transactional
    public Site crear(String nombre, String codigo, String flujoOPosMode) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre de la sede es obligatorio");
        }
        String code = normalizarCodigo(codigo == null || codigo.isBlank() ? nombre : codigo);
        if (repository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Ya existe una sede con el código " + code);
        }
        FlujosDeVenta.Flujo flujo = flujoOPosMode == null || flujoOPosMode.isBlank()
                ? flujoEfectivo()
                : flujos.exigir(flujoOPosMode);
        Site sede = new Site();
        sede.setName(nombre.trim());
        sede.setCode(code);
        aplicar(sede, flujo);
        sede.setActive(true);
        sede.setIsDefault(repository.findFirstByIsDefaultTrue().isEmpty());
        return repository.save(sede);
    }

    private static void aplicar(Site sede, FlujosDeVenta.Flujo flujo) {
        sede.setFlujoDeVenta(flujo.codigo());
        // La base lo deriva igual por trigger; se escribe para que la entidad
        // en memoria diga lo mismo que la fila sin releerla.
        sede.setPosMode(flujo.posModeLegado());
    }
}
