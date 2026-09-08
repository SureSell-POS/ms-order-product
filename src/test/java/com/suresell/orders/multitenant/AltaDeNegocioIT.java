package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * El alta de un negocio, ejecutada de verdad contra Postgres.
 *
 * <p>Un test con dobles no diría nada acá: lo que puede salir mal es
 * precisamente lo que sólo aparece contra la base — <b>Row-Level Security en
 * modo FORCE</b>. `sites`, `restaurant_tables` y `tenant_order_counters` filtran
 * por `app.tenant_id`, y el KAM es cross-tenant: no trae negocio en contexto.
 *
 * <p>Sin el `set_config` los INSERT <b>no insertan nada y no fallan</b>. El alta
 * respondería "listo" y el cliente quedaría sin sede y sin mesas: un negocio que
 * no puede vender y que nadie puede arreglar desde la aplicación.
 */
@Testcontainers
class AltaDeNegocioIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static AltaDeNegocioService servicio;

    @BeforeAll
    static void preparar() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        // V48: el alta lee el catálogo de perfiles del inventario (otra cadena).
        // Aquí se levanta su doble; sin él, el alta exige perfil solo si hay catálogo.
        EsquemaInventarioDePrueba.crear(jdbc);

        servicio = new AltaDeNegocioService(jdbc, new BCryptPasswordEncoder(),
                new PlanCatalogService(new PlanRepository(jdbc)),
                new com.suresell.orders.flujo.FlujosDeVenta(jdbc), new PerfilDelNegocio(jdbc));
    }

    /** Un KAM viejo: manda `modo` y ningún `flujoDeVenta`, pero ya con perfil. */
    private static AltaDeNegocioService.Solicitud restaurante(String nombre, String email, int mesas) {
        return new AltaDeNegocioService.Solicitud(
                nombre, email, "clave-seguraaa", "pro", "RESTAURANTE", mesas,
                "900123456-1", "Calle 1 # 2-3", "3001234567", "restaurante", null);
    }

    private static AltaDeNegocioService.Solicitud plazoleta(String nombre, String email) {
        return new AltaDeNegocioService.Solicitud(
                nombre, email, "clave-seguraaa", "pro", "PLAZOLETA", null, null, null, null,
                "restaurante", null);
    }

    /** Un KAM nuevo: perfil y, si quiere, flujo. */
    private static AltaDeNegocioService.Solicitud conPerfil(String nombre, String email, String perfil,
                                                            String flujo, Integer mesas) {
        return new AltaDeNegocioService.Solicitud(
                nombre, email, "clave-seguraaa", "pro", null, mesas, null, null, null, perfil, flujo);
    }

    @Test
    @DisplayName("un restaurante queda listo para vender de una sola vez")
    void altaCompletaDeRestaurante() {
        var r = servicio.darDeAlta(restaurante("Pizzería del Centro", "admin@pizzeria.co", 12));

        assertThat(r.tenantId()).isEqualTo("pizzeria-del-centro");
        assertThat(r.modo()).isEqualTo("RESTAURANTE");
        assertThat(r.mesasCreadas()).isEqualTo(12);

        // LO QUE IMPORTA: que las filas existan de verdad. Con RLS mal fijado
        // los INSERT no fallan, simplemente no insertan.
        assertThat(cuenta("tenants", "id = 'pizzeria-del-centro'")).isEqualTo(1);
        assertThat(cuenta("users", "tenant_id = 'pizzeria-del-centro'")).isEqualTo(1);
        assertThat(cuenta("sites", "tenant_id = 'pizzeria-del-centro'")).isEqualTo(1);
        assertThat(cuenta("restaurant_tables", "tenant_id = 'pizzeria-del-centro'")).isEqualTo(12);
        assertThat(cuenta("tenant_order_counters", "tenant_id = 'pizzeria-del-centro'")).isEqualTo(1);
    }

    @Test
    @DisplayName("la sede nace en el modo elegido, no en el que traiga por defecto")
    void laSedeNaceEnModoRestaurante() {
        servicio.darDeAlta(restaurante("Asadero La 80", "admin@asadero80.co", 5));

        Map<String, Object> sede = jdbc.queryForMap(
                "SELECT pos_mode, is_default, code FROM sites WHERE tenant_id = 'asadero-la-80'");

        // Si se dejara al disparador de V28, la sede naceria en PLAZOLETA y el
        // restaurante arrancaria sin plano de mesas.
        assertThat(sede.get("pos_mode")).isEqualTo("RESTAURANTE");
        // V48: `modo` viejo RESTAURANTE se resolvió contra el catálogo.
        assertThat(jdbc.queryForObject("SELECT flujo_de_venta FROM sites WHERE tenant_id = 'asadero-la-80'",
                String.class)).isEqualTo("MESA");
        assertThat(sede.get("is_default")).isEqualTo(true);
        assertThat(sede.get("code")).isEqualTo("PRINCIPAL");
    }

    @Test
    @DisplayName("las mesas se numeran de 1 a N y quedan activas")
    void mesasNumeradasDesdeUno() {
        servicio.darDeAlta(restaurante("Fonda Antioqueña", "admin@fonda.co", 4));

        List<Integer> numeros = jdbc.queryForList(
                "SELECT number FROM restaurant_tables WHERE tenant_id = 'fonda-antioquena' "
                        + "ORDER BY number", Integer.class);

        assertThat(numeros).containsExactly(1, 2, 3, 4);
        assertThat(cuenta("restaurant_tables", "tenant_id = 'fonda-antioquena' AND active")).isEqualTo(4);
    }

    @Test
    @DisplayName("el primer folio del negocio nuevo es 1, no hereda el de otro")
    void elContadorArrancaEnCero() {
        servicio.darDeAlta(plazoleta("Café Central", "admin@cafecentral.co"));

        Long ultimo = jdbc.queryForObject(
                "SELECT last_id FROM tenant_order_counters WHERE tenant_id = 'cafe-central'", Long.class);

        assertThat(ultimo).isZero();
    }

    @Test
    @DisplayName("un negocio de plazoleta no lleva mesas")
    void plazoletaSinMesas() {
        var r = servicio.darDeAlta(plazoleta("Comidas Rápidas El Portal", "admin@elportal.co"));

        assertThat(r.modo()).isEqualTo("PLAZOLETA");
        assertThat(r.mesasCreadas()).isZero();
        assertThat(cuenta("restaurant_tables", "tenant_id = '" + r.tenantId() + "'")).isZero();
    }

    @Test
    @DisplayName("dos negocios con el mismo nombre no chocan")
    void nombresRepetidosNoChocan() {
        var a = servicio.darDeAlta(plazoleta("Burger House", "admin1@burger.co"));
        var b = servicio.darDeAlta(plazoleta("Burger House", "admin2@burger.co"));

        assertThat(a.tenantId()).isEqualTo("burger-house");
        assertThat(b.tenantId()).isEqualTo("burger-house-2");
    }

    @Test
    @DisplayName("cada negocio ve SOLO sus mesas")
    void aislamientoEntreNegocios() {
        servicio.darDeAlta(restaurante("Mesas Uno", "admin@mesasuno.co", 3));
        servicio.darDeAlta(restaurante("Mesas Dos", "admin@mesasdos.co", 7));

        assertThat(cuenta("restaurant_tables", "tenant_id = 'mesas-uno'")).isEqualTo(3);
        assertThat(cuenta("restaurant_tables", "tenant_id = 'mesas-dos'")).isEqualTo(7);
    }

    @Test
    @DisplayName("un restaurante SIN mesas se rechaza: no podría vender")
    void restauranteSinMesasSeRechaza() {
        assertThatThrownBy(() -> servicio.darDeAlta(restaurante("Sin Mesas", "admin@sinmesas.co", 0)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .hasMessageContaining("al menos una mesa");
    }

    @Test
    @DisplayName("un email ya usado se rechaza con 409")
    void emailRepetidoSeRechaza() {
        servicio.darDeAlta(plazoleta("Primero", "repetido@correo.co"));

        assertThatThrownBy(() -> servicio.darDeAlta(plazoleta("Segundo", "REPETIDO@correo.co")))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .satisfies(e -> assertThat(((AltaDeNegocioService.AltaInvalidaException) e).codigo())
                        .isEqualTo(409));
    }

    @Test
    @DisplayName("si el alta falla, NO queda un negocio a medio crear")
    void elAltaEsTodoONada() {
        // Un negocio sin sede o sin usuario admin no se puede arreglar desde la
        // aplicacion: hay que entrar a la base a mano.
        assertThatThrownBy(() -> servicio.darDeAlta(
                new AltaDeNegocioService.Solicitud("Negocio Roto", "admin@roto.co", "123",
                        "pro", "RESTAURANTE", 5, null, null, null)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class);

        assertThat(cuenta("tenants", "id = 'negocio-roto'")).isZero();
        assertThat(cuenta("users", "email = 'admin@roto.co'")).isZero();
    }

    @Test
    @DisplayName("datos incompletos o inválidos se rechazan con 400")
    void validaciones() {
        assertThatThrownBy(() -> servicio.darDeAlta(plazoleta("", "a@b.co")))
                .hasMessageContaining("obligatorios");

        assertThatThrownBy(() -> servicio.darDeAlta(plazoleta("Sin Arroba", "no-es-un-email")))
                .hasMessageContaining("email");

        // V48: un `modo` que no está en el catálogo se rechaza diciendo cuáles hay.
        assertThatThrownBy(() -> servicio.darDeAlta(
                new AltaDeNegocioService.Solicitud("Modo Raro", "admin@modoraro.co", "clave-seguraaa",
                        "pro", "DELIVERY", null, null, null, null, "restaurante", null)))
                .hasMessageContaining("DELIVERY")
                .hasMessageContaining("RASTREADOR");

        assertThatThrownBy(() -> servicio.darDeAlta(
                new AltaDeNegocioService.Solicitud("Plan Raro", "admin@planraro.co", "clave-seguraaa",
                        "inexistente", "PLAZOLETA", null, null, null, null, "restaurante", null)))
                .hasMessageContaining("no existe");
    }

    @Test
    @DisplayName("la clave se guarda cifrada, nunca en claro")
    void laClaveSeGuardaCifrada() {
        servicio.darDeAlta(plazoleta("Seguridad Ok", "admin@seguridadok.co"));

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = 'admin@seguridadok.co'", String.class);

        assertThat(hash).isNotNull().doesNotContain("clave-seguraaa").startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches("clave-seguraaa", hash)).isTrue();
    }

    private static int cuenta(String tabla, String condicion) {
        // Como superusuario del contenedor se ve todo, que es justo lo que se
        // quiere para comprobar que las filas existen de verdad.
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tabla + " WHERE " + condicion,
                Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------
    // V48 (ola 3): perfil en el alta, flujo por perfil, mesas solo donde hay.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("🔴 una droguería nace vendiendo DIRECTO, sin mesas, y con su perfil anotado por el KAM")
    void unaDrogueriaNaceDirectoYConPerfil() {
        var r = servicio.darDeAlta(conPerfil("Droguería La Salud", "admin@lasalud.co", "drogueria", null, null),
                "kam@suresell.com.co");

        assertThat(r.perfil()).isEqualTo("drogueria");
        assertThat(r.flujoDeVenta()).isEqualTo("DIRECTO");
        assertThat(r.perfilRegistrado()).isTrue();
        assertThat(r.mesasCreadas()).isZero();
        // Lo que ve un POS viejo: PLAZOLETA, porque así lo dice la fila del catálogo.
        assertThat(r.modo()).isEqualTo("PLAZOLETA");

        Map<String, Object> sede = jdbc.queryForMap(
                "SELECT pos_mode, flujo_de_venta FROM sites WHERE tenant_id = 'drogueria-la-salud'");
        assertThat(sede.get("flujo_de_venta")).isEqualTo("DIRECTO");
        assertThat(sede.get("pos_mode")).isEqualTo("PLAZOLETA");

        // El libro del perfil: una fila, del KAM, con la fuente y la confianza de V50.
        jdbc.queryForObject("SELECT set_config('app.tenant_id', 'drogueria-la-salud', false)", String.class);
        Map<String, Object> libro = jdbc.queryForMap(
                "SELECT perfil_codigo, fuente, confianza, usuario_id, referencia "
                        + "FROM inventario.perfil_asignado WHERE tenant_id = 'drogueria-la-salud'");
        assertThat(libro.get("perfil_codigo")).isEqualTo("drogueria");
        assertThat(libro.get("fuente")).isEqualTo("asignado_por_suresell");
        assertThat(((Number) libro.get("confianza")).intValue()).isEqualTo(3);
        assertThat(libro.get("usuario_id")).isEqualTo("kam@suresell.com.co");
        assertThat(libro.get("referencia")).isEqualTo("alta:drogueria-la-salud");
        jdbc.queryForObject("SELECT set_config('app.tenant_id', '', false)", String.class);
    }

    @Test
    @DisplayName("un restaurante sin decir flujo nace en el que su perfil trae por defecto (MESA), con mesas")
    void elRestauranteHeredaElDefectoDelPerfil() {
        var r = servicio.darDeAlta(conPerfil("Mesón Real", "admin@meson.co", "restaurante", null, 6));

        assertThat(r.flujoDeVenta()).isEqualTo("MESA");
        assertThat(r.modo()).isEqualTo("RESTAURANTE");
        assertThat(r.mesasCreadas()).isEqualTo(6);
    }

    @Test
    @DisplayName("un restaurante puede elegir RASTREADOR: el perfil lo admite")
    void elRestaurantePuedeElegirRastreador() {
        var r = servicio.darDeAlta(conPerfil("Plazoleta Sabor", "admin@sabor.co", "restaurante", "RASTREADOR", null));

        assertThat(r.flujoDeVenta()).isEqualTo("RASTREADOR");
        assertThat(r.mesasCreadas()).isZero();
    }

    @Test
    @DisplayName("🔴 control negativo: una droguería NO puede nacer con mesas; el perfil no lo admite")
    void elPerfilNoAdmiteEseFlujo() {
        assertThatThrownBy(() -> servicio.darDeAlta(
                conPerfil("Droguería Mesas", "admin@drogmesas.co", "drogueria", "MESA", 4)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .hasMessageContaining("no admite el flujo MESA");
        assertThat(cuenta("tenants", "id = 'drogueria-mesas'")).isZero();
    }

    @Test
    @DisplayName("🔴 control negativo: con catálogo, el alta sin perfil se rechaza y dice cuáles hay")
    void sinPerfilSeRechaza() {
        assertThatThrownBy(() -> servicio.darDeAlta(
                conPerfil("Sin Perfil", "admin@sinperfil.co", null, null, null)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .hasMessageContaining("obligatorio")
                .hasMessageContaining("drogueria");
        assertThat(cuenta("tenants", "id = 'sin-perfil'")).isZero();
    }

    @Test
    @DisplayName("un perfil que no existe se rechaza con 400")
    void perfilInexistente() {
        assertThatThrownBy(() -> servicio.darDeAlta(
                conPerfil("Telepatía", "admin@telepatia.co", "telepatia", null, null)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .satisfies(e -> assertThat(((AltaDeNegocioService.AltaInvalidaException) e).codigo())
                        .isEqualTo(400));
    }

    @Test
    @DisplayName("un flujo que no está en el catálogo se rechaza, aunque el perfil sea válido")
    void flujoInexistente() {
        assertThatThrownBy(() -> servicio.darDeAlta(
                conPerfil("Buffet", "admin@buffet.co", "restaurante", "BUFFET", null)))
                .isInstanceOf(AltaDeNegocioService.AltaInvalidaException.class)
                .hasMessageContaining("BUFFET");
    }

    @Test
    @DisplayName("el catálogo del KAM trae cada perfil con sus flujos, y el que no tiene flujo aparece con la lista vacía")
    void elCatalogoDelKam() {
        var perfiles = new PerfilDelNegocio(jdbc).catalogo();

        assertThat(perfiles).extracting(PerfilDelNegocio.Perfil::codigo)
                .contains("restaurante", "drogueria", "optica");
        var restaurante = perfiles.stream().filter(p -> p.codigo().equals("restaurante")).findFirst().orElseThrow();
        assertThat(restaurante.flujos()).extracting(PerfilDelNegocio.FlujoAdmitido::codigo)
                .containsExactly("MESA", "RASTREADOR");
        assertThat(restaurante.flujos().get(0).esDefecto()).isTrue();
        // Óptica está en el inventario pero nadie le declaró flujo en public:
        // se ve, con la lista vacía. Visible, no escondido.
        var optica = perfiles.stream().filter(p -> p.codigo().equals("optica")).findFirst().orElseThrow();
        assertThat(optica.flujos()).isEmpty();
    }
}
