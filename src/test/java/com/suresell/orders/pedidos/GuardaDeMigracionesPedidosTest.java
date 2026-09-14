package com.suresell.orders.pedidos;

import com.suresell.orders.multitenant.GuardaDeMigracionesTest;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * La misma guarda que la de `public`, sobre la cadena `pedidos` (plan de
 * mayoristas F0.9): huella sellada, sin números repetidos, todo sellado,
 * rangos y nombres. Hereda en vez de copiar.
 */
class GuardaDeMigracionesPedidosTest extends GuardaDeMigracionesTest {

    @Override
    protected Path carpeta() {
        return Paths.get("src/main/resources/db/migration-pedidos");
    }

    @Override
    protected Path ayuda() {
        return Paths.get("build/MIGRACIONES-pedidos.txt.nuevo");
    }
}
