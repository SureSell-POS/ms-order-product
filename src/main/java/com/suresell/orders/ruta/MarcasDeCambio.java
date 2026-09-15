package com.suresell.orders.ruta;

import java.util.List;

/**
 * F6.0b (V83): lo que la base marca para el paquete de la ruta. Es contrato entre la migración y el paquete (F6.2):
 * {@code MarcaDeCambioTest} compara {@link #CATALOGO_QUE_VIAJA} con las columnas del disparador de V83, así que añadir una
 * columna al paquete sin añadirla al disparador sale rojo.
 */
public final class MarcasDeCambio {

    /** Columnas de {@code menu_products} que viajan en el paquete; el id no cambia. Las mismas del WHEN de V83. */
    public static final List<String> CATALOGO_QUE_VIAJA = List.of("name_product", "price", "active", "category_id");

    /** Días que se guardan las lápidas de productos borrados; un {@code desde} más viejo recibe el paquete completo. */
    public static final int RETENCION_DE_LAPIDAS_DIAS = 14;

    private MarcasDeCambio() {
    }
}
