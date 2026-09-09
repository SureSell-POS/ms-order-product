package com.suresell.orders.multitenant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fuente de verdad (server-authoritative) del mapa **plan → módulos** (F3, docs/160
 * / docs/50). La UI del POS refleja estos módulos; el backend los HACE CUMPLIR
 * (ver {@link ModuleAccessFilter}). Espejo temporal de `feature.model.ts` del front;
 * en F3.2 los overrides por-tenant se suman encima, y en F3.3 esto se mueve a DB
 * gestionable desde el panel.
 */
public final class PlanCatalog {

    private PlanCatalog() {}

    // --- Módulos del POS ---
    public static final String VENTAS = "ventas";
    public static final String HISTORIAL = "historial";
    public static final String CIERRE = "cierre";
    public static final String DESCUENTOS = "descuentos";
    public static final String COCINA = "cocina";
    public static final String MESEROS = "meseros";

    // --- Módulos del PANEL de administración (N2/6.8) ---
    // El panel (web_panel, hoy en sharkburger.suresell.com.co) reutiliza sus
    // módulos actuales, pero cuáles ve cada negocio lo decide el KAM: vienen por
    // plan y se pueden regalar o quitar por tenant con los overrides que ya
    // existen (`PUT /admin/tenants/{id}/modules`).
    public static final String PANEL = "panel";
    public static final String ANALITICA = "analitica";
    public static final String NOMINA = "nomina";
    public static final String EMPLEADOS = "empleados";
    public static final String VALERAS = "valeras";
    public static final String INSUMOS = "insumos";
    public static final String COMPRAS = "compras";
    public static final String GASTOS = "gastos";
    public static final String CARTERA = "cartera";
    /**
     * Ola 2 (H). Sin esta constante, `isKnownModule("mayorista")` devuelve
     * false y `effectiveModules` DESCARTA EN SILENCIO el override del KAM: el
     * negocio nunca recibe el módulo y las pantallas de listas de precio y
     * clientes no se pueden abrir aunque el backend responda. Medido en
     * staging el 2026-09-08: `qa-zeta-v38` y `qa-delta` tienen
     * `mayorista = true` en `tenant_modules` desde el 2026-09-07 y su login
     * devolvía `cartera` pero no `mayorista`.
     */
    public static final String MAYORISTA = "mayorista";
    public static final String MENU_ADMIN = "menu";
    /**
     * Ola 4 (A, a pedido de B). Producción es una capacidad del NEGOCIO, no de
     * la vertical: la activa el KAM por override, como `mayorista`. Sin la
     * constante, el override se descartaba en silencio (misma lección de
     * `MAYORISTA`). Fuera de todo plan por defecto a propósito.
     */
    public static final String PRODUCCION = "produccion";

    /** Módulos del panel que entran en el plan `pro`. */
    private static final List<String> PANEL_PRO =
            List.of(PANEL, ANALITICA, MENU_ADMIN, GASTOS);

    private static final Map<String, List<String>> PLAN_MODULES = Map.of(
            "basico", List.of(VENTAS, HISTORIAL, CIERRE, COCINA, INSUMOS),
            "pro", concat(List.of(VENTAS, HISTORIAL, CIERRE, DESCUENTOS, COCINA, MESEROS, INSUMOS),
                    PANEL_PRO));

    private static final List<String> DEFAULT =
            concat(List.of(VENTAS, HISTORIAL, CIERRE, DESCUENTOS, COCINA, MESEROS, INSUMOS),
                    PANEL_PRO);

    /**
     * Todos los módulos conocidos (para validar overrides).
     *
     * Los que NO están en ningún plan (nómina, valeras, compras, cartera,
     * empleados) existen a propósito: se venden aparte y el KAM los activa por
     * tenant con un override. Si no estuvieran acá, `isKnownModule` los
     * rechazaría y no se podrían regalar.
     *
     * `insumos` salió de esa lista en V47: el inventario va desde el plan más
     * básico, porque es captura de datos y no una función premium.
     */
    public static final Set<String> KNOWN = Set.of(
            VENTAS, HISTORIAL, CIERRE, DESCUENTOS, COCINA, MESEROS,
            PANEL, ANALITICA, NOMINA, EMPLEADOS, VALERAS, INSUMOS, COMPRAS,
            GASTOS, CARTERA, MAYORISTA, MENU_ADMIN, PRODUCCION);

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /** Módulos incluidos por el plan; si el plan es desconocido, cae a `pro` (todos). */
    public static List<String> modulesForPlan(String plan) {
        if (plan == null) {
            return DEFAULT;
        }
        return PLAN_MODULES.getOrDefault(plan.trim().toLowerCase(), DEFAULT);
    }

    public static boolean isKnownModule(String module) {
        return module != null && KNOWN.contains(module.trim().toLowerCase());
    }

    /**
     * Módulos EFECTIVOS = (módulos del plan ∪ grants) − revokes (F3, Inc.2).
     * `overrides`: módulo → enabled (true regala, false quita).
     */
    public static List<String> effectiveModules(String plan, Map<String, Boolean> overrides) {
        Set<String> set = new LinkedHashSet<>(modulesForPlan(plan));
        if (overrides != null) {
            for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
                if (!isKnownModule(e.getKey())) {
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
}
