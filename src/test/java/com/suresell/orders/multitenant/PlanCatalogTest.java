package com.suresell.orders.multitenant;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mapa plan→módulos + overrides efectivos (F3). Puro, sin DB. */
class PlanCatalogTest {

    @Test
    void planBasicoNoTieneDescuentos_proSi() {
        assertTrue(PlanCatalog.modulesForPlan("basico").contains("cierre"));
        assertFalse(PlanCatalog.modulesForPlan("basico").contains("descuentos"));
        assertTrue(PlanCatalog.modulesForPlan("pro").contains("descuentos"));
    }

    @Test
    void overrideRegalaModulo() {
        assertTrue(PlanCatalog.effectiveModules("basico", Map.of("descuentos", true)).contains("descuentos"));
    }

    @Test
    void overrideQuitaModulo() {
        assertFalse(PlanCatalog.effectiveModules("pro", Map.of("descuentos", false)).contains("descuentos"));
    }

    @Test
    void ignoraModuloDesconocidoEnOverride() {
        assertFalse(PlanCatalog.effectiveModules("pro", Map.of("xyz", true)).contains("xyz"));
    }

    @Test
    void isKnownModule() {
        assertTrue(PlanCatalog.isKnownModule("descuentos"));
        assertFalse(PlanCatalog.isKnownModule("xyz"));
    }

    @Test
    void cocinaIncluidaEnAmbosPlanes() {
        assertTrue(PlanCatalog.modulesForPlan("basico").contains("cocina"));
        assertTrue(PlanCatalog.modulesForPlan("pro").contains("cocina"));
        assertTrue(PlanCatalog.isKnownModule("cocina"));
    }

    @Test
    void meserosSoloEnPro() {
        assertFalse(PlanCatalog.modulesForPlan("basico").contains("meseros"));
        assertTrue(PlanCatalog.modulesForPlan("pro").contains("meseros"));
        assertTrue(PlanCatalog.isKnownModule("meseros"));
        // Override puede regalarlo a un básico.
        assertTrue(PlanCatalog.effectiveModules("basico", Map.of("meseros", true)).contains("meseros"));
    }

    @Test
    void overridePuedeQuitarCocina() {
        assertFalse(PlanCatalog.effectiveModules("basico", Map.of("cocina", false)).contains("cocina"));
    }

    @Test
    void insumosIncluidoEnAmbosPlanes() {
        // V47: el inventario va desde el plan más básico. Es captura de datos,
        // no una función premium.
        assertTrue(PlanCatalog.modulesForPlan("basico").contains("insumos"));
        assertTrue(PlanCatalog.modulesForPlan("pro").contains("insumos"));
        assertTrue(PlanCatalog.isKnownModule("insumos"));
    }

    @Test
    void mayoristaEsUnModuloConocidoYSePuedeRegalar() {
        // Si no lo fuera, el override del KAM se descartaría en silencio y las
        // pantallas de mayorista del panel no abrirían nunca.
        assertTrue(PlanCatalog.isKnownModule("mayorista"));
        assertFalse(PlanCatalog.modulesForPlan("basico").contains("mayorista"));
        assertTrue(PlanCatalog.effectiveModules("basico", Map.of("mayorista", true)).contains("mayorista"));
    }
}
