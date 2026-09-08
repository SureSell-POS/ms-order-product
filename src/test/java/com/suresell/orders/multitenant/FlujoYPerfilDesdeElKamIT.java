package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Lo que el KAM hace después del alta, contra un Postgres real con V1→V48 y el
 * doble del esquema del inventario: cambiar el flujo de una sede (dentro de lo
 * que el perfil admite), cambiar el perfil (una fila más en el libro), y crear
 * otra cuenta de KAM.
 */
@Testcontainers
class FlujoYPerfilDesdeElKamIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static SuperAdminService kam;
    static SuperAdminRepository saRepo;
    static String tenant;
    static long sede;

    @BeforeAll
    static void preparar() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        EsquemaInventarioDePrueba.crear(jdbc);

        var flujos = new com.suresell.orders.flujo.FlujosDeVenta(jdbc);
        var perfiles = new PerfilDelNegocio(jdbc);
        var planRepo = new PlanRepository(jdbc);
        saRepo = new SuperAdminRepository(jdbc);
        kam = new SuperAdminService(saRepo, mock(AuthService.class), planRepo,
                new PlanCatalogService(planRepo), jdbc, flujos, perfiles,
                "clave-de-pruebas-suficientemente-larga-32bytes!", 3600);

        var alta = new AltaDeNegocioService(jdbc, new BCryptPasswordEncoder(),
                new PlanCatalogService(planRepo), flujos, perfiles);
        var r = alta.darDeAlta(new AltaDeNegocioService.Solicitud(
                "Asadero KAM", "admin@asaderokam.co", "clave-seguraaa", "pro", null, 3,
                null, null, null, "restaurante", null), "kam@suresell.com.co");
        tenant = r.tenantId();
        sede = r.siteId();
    }

    @Test
    @DisplayName("las sedes del KAM traen el flujo y su posMode legado, leídos del catálogo")
    void lasSedesTraenElFlujo() {
        List<Map<String, Object>> sedes = kam.getSites(tenant);

        assertThat(sedes).hasSize(1);
        assertThat(sedes.get(0).get("flujo_de_venta")).isEqualTo("MESA");
        assertThat(sedes.get(0).get("pos_mode")).isEqualTo("RESTAURANTE");
        assertThat(sedes.get(0).get("usa_mesas")).isEqualTo(true);
    }

    @Test
    @DisplayName("el KAM cambia el flujo con el nombre nuevo o con el posMode viejo, y queda en la bitácora")
    void cambiarElFlujoConCualquieraDeLosDosNombres() {
        // Un KAM viejo manda PLAZOLETA: se resuelve a RASTREADOR por la fila del catálogo.
        var sedes = kam.setSiteMode(tenant, sede, "PLAZOLETA", "kam@suresell.com.co");
        assertThat(sedes.get(0).get("flujo_de_venta")).isEqualTo("RASTREADOR");
        assertThat(sedes.get(0).get("pos_mode")).isEqualTo("PLAZOLETA");

        // Un KAM nuevo manda el código del flujo.
        sedes = kam.setSiteMode(tenant, sede, "mesa", "kam@suresell.com.co");
        assertThat(sedes.get(0).get("flujo_de_venta")).isEqualTo("MESA");
        assertThat(sedes.get(0).get("pos_mode")).isEqualTo("RESTAURANTE");

        List<Map<String, Object>> bitacora = jdbc.queryForList(
                "SELECT modo_antes, modo_despues, hecho_por FROM site_mode_audit WHERE tenant_id = ? ORDER BY id",
                tenant);
        assertThat(bitacora).extracting(m -> m.get("modo_antes") + "→" + m.get("modo_despues"))
                .containsExactly("MESA→RASTREADOR", "RASTREADOR→MESA");
        assertThat(bitacora.get(0).get("hecho_por")).isEqualTo("kam@suresell.com.co");
    }

    @Test
    @DisplayName("🔴 control negativo: el KAM no puede poner a un restaurante en DIRECTO; su perfil no lo admite")
    void elPerfilAcotaLoQueElKamPuedePoner() {
        assertThatThrownBy(() -> kam.setSiteMode(tenant, sede, "DIRECTO", "kam@suresell.com.co"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("no admite el flujo DIRECTO");
        assertThatThrownBy(() -> kam.setSiteMode(tenant, sede, "BUFFET", "kam@suresell.com.co"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Flujo de venta inválido");
    }

    @Test
    @DisplayName("cambiar el perfil es una fila más: la anterior se conserva y la vigente es la última")
    void cambiarElPerfilAnexaAlLibro() {
        var antes = kam.perfilVigente(tenant).orElseThrow();
        assertThat(antes.codigo()).isEqualTo("restaurante");

        var despues = kam.setPerfil(tenant, "minimercado", "kam2@suresell.com.co");

        assertThat(despues.codigo()).isEqualTo("minimercado");
        assertThat(despues.fuente()).isEqualTo("asignado_por_suresell");
        assertThat(despues.asignadoPor()).isEqualTo("kam2@suresell.com.co");

        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenant);
        Integer filas = jdbc.queryForObject(
                "SELECT count(*) FROM inventario.perfil_asignado WHERE tenant_id = ?", Integer.class, tenant);
        jdbc.queryForObject("SELECT set_config('app.tenant_id', '', false)", String.class);
        assertThat(filas).isEqualTo(2);

        assertThatThrownBy(() -> kam.setPerfil(tenant, "telepatia", "kam@suresell.com.co"))
                .isInstanceOf(AuthException.class).hasMessageContaining("no existe");
        assertThatThrownBy(() -> kam.setPerfil("no-existe", "drogueria", "kam@suresell.com.co"))
                .isInstanceOf(AuthException.class).hasMessageContaining("Negocio no encontrado");

        // Se deja como estaba para el resto de casos.
        kam.setPerfil(tenant, "restaurante", "kam@suresell.com.co");
    }

    @Test
    @DisplayName("otra cuenta de KAM: se crea con clave válida, no dos veces, y no con clave débil")
    void otraCuentaDeKam() {
        var creado = kam.createSuperAdmin("Nuevo.KAM@suresell.com.co", "Correcta-2026-xyz", "kam@suresell.com.co");
        assertThat(creado.email()).isEqualTo("nuevo.kam@suresell.com.co");
        assertThat(saRepo.findByEmail("nuevo.kam@suresell.com.co")).isPresent();

        assertThatThrownBy(() -> kam.createSuperAdmin("nuevo.kam@suresell.com.co", "Otra-clave-2026", "kam"))
                .isInstanceOf(AuthException.class).hasMessageContaining("Ya existe");
        assertThatThrownBy(() -> kam.createSuperAdmin("debil@suresell.com.co", "shark2026", "kam"))
                .isInstanceOf(AuthException.class).hasMessageContaining("clave");
        assertThat(saRepo.findByEmail("debil@suresell.com.co")).isEmpty();
    }
}
