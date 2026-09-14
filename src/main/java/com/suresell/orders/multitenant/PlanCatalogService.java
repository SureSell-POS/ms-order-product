package com.suresell.orders.multitenant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resuelve plan → módulos leyendo de la base (V27), con caché en memoria.
 *
 * <p>Antes esto eran constantes de Java: crear un plan o cambiar qué incluye
 * exigía redesplegar. Ahora lo edita el KAM.
 *
 * <p><b>Caída segura</b>: si la tabla `plans` no responde o está vacía, se usan
 * las constantes de {@link PlanCatalog}. Sin eso, un problema con la tabla
 * dejaría a TODOS los negocios sin módulos —o sea, sin POS— y el arranque en un
 * entorno sin migrar (tests) fallaría.
 *
 * <p>La caché se invalida al escribir desde el KAM. No hay TTL: el catálogo solo
 * cambia por acción del KAM, que corre en este mismo proceso.
 */
@Service
public class PlanCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogService.class);

    private final PlanRepository repo;
    private final AtomicReference<Map<String, List<String>>> cache = new AtomicReference<>();

    public PlanCatalogService(PlanRepository repo) {
        this.repo = repo;
    }

    /** Mapa plan → módulos. Vacío si la BD no tiene planes (se cae a constantes). */
    private Map<String, List<String>> planes() {
        Map<String, List<String>> actual = cache.get();
        if (actual != null) {
            return actual;
        }
        Map<String, List<String>> cargado;
        try {
            cargado = repo.findAll().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PlanRepository.Plan::id, PlanRepository.Plan::modules, (a, b) -> a));
        } catch (RuntimeException e) {
            // No se cachea el fallo: el siguiente request reintenta.
            log.warn("No se pudo leer el catálogo de planes; se usan las constantes. Causa: {}",
                    e.getMessage());
            return Map.of();
        }
        cache.set(cargado);
        return cargado;
    }

    public void invalidar() {
        cache.set(null);
    }

    /** Módulos del plan. Plan desconocido → mismo default que antes. */
    public List<String> modulesForPlan(String plan) {
        if (plan == null) {
            return PlanCatalog.modulesForPlan(null);
        }
        List<String> desdeBd = planes().get(plan.trim().toLowerCase());
        if (desdeBd != null && !desdeBd.isEmpty()) {
            return desdeBd;
        }
        return PlanCatalog.modulesForPlan(plan);
    }

    /** Módulos EFECTIVOS = (módulos del plan ∪ grants) − revokes. */
    public List<String> effectiveModules(String plan, Map<String, Boolean> overrides) {
        Set<String> set = new LinkedHashSet<>(modulesForPlan(plan));
        if (overrides != null) {
            for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
                if (!PlanCatalog.isKnownModule(e.getKey())) {
                    // Se descarta, pero no en silencio: así se perdieron
                    // `mayorista` y `produccion` (el KAM los regalaba y el
                    // login no los devolvía). Falta la constante en PlanCatalog.
                    log.warn("Override de modulo desconocido descartado: '{}' (falta en PlanCatalog.KNOWN)",
                            e.getKey());
                    continue;
                }
                if (Boolean.TRUE.equals(e.getValue())) {
                    set.add(e.getKey());
                } else {
                    set.remove(e.getKey());
                }
            }
        }
        return List.copyOf(set);
    }

    /** Ids de planes activos, para el selector del KAM. */
    public List<PlanRepository.Plan> catalogo() {
        try {
            List<PlanRepository.Plan> desdeBd = repo.findAll();
            if (!desdeBd.isEmpty()) {
                return desdeBd;
            }
        } catch (RuntimeException e) {
            log.warn("Catálogo de planes no disponible; se listan las constantes: {}", e.getMessage());
        }
        // Espejo de las constantes, para que el KAM nunca quede en blanco.
        return List.of(
                new PlanRepository.Plan("basico", "Básico", null, true,
                        PlanCatalog.modulesForPlan("basico")),
                new PlanRepository.Plan("pro", "Pro", null, true,
                        PlanCatalog.modulesForPlan("pro")));
    }
}
