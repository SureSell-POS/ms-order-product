package com.suresell.orders.multitenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta de un negocio completa, en un solo paso.
 *
 * <p><b>Por qué existe.</b> Dar de alta un cliente eran cinco pasos repartidos
 * en dos sesiones distintas —el token del KAM y el del administrador del propio
 * negocio—: registrar el negocio, crear la sede, ponerle el modo, crear las
 * mesas y ajustar el plan. Media docena de oportunidades de equivocarse, y el
 * paso más propenso a error de toda la operación.
 *
 * <p><b>Todo o nada.</b> Corre dentro de una transacción: si algo falla, no
 * queda un negocio a medio crear. Un negocio sin sede o sin usuario admin es
 * peor que ninguno — no se puede entrar a arreglarlo desde la aplicación.
 *
 * <p>Escribe directo con JDBC en vez de reusar los repositorios de cada área
 * porque el alta ocurre <b>antes</b> de que exista el tenant, así que no hay
 * contexto de negocio: las políticas RLS y los repositorios acotados por tenant
 * todavía no aplican.
 */
@Service
public class AltaDeNegocioService {

    /**
     * Lo que el KAM manda para dar de alta.
     *
     * <p>V48 (ola 3): {@code perfil} es el perfil vertical (obligatorio cuando
     * la base tiene el catálogo del inventario) y {@code flujoDeVenta} el flujo
     * de la sede principal, que tiene que ser uno de los que el perfil admite;
     * si no viene, el que el perfil trae por defecto. {@code modo} se conserva
     * para un KAM viejo: se resuelve contra el catálogo como cualquier flujo.
     */
    public record Solicitud(
            String nombreNegocio,
            String emailAdmin,
            String clave,
            String plan,
            String modo,
            Integer cantidadMesas,
            String nit,
            String direccion,
            String telefono,
            String perfil,
            String flujoDeVenta) {

        /** La forma que tenía este record antes de V48: sin perfil ni flujo. */
        public Solicitud(String nombreNegocio, String emailAdmin, String clave, String plan,
                         String modo, Integer cantidadMesas, String nit, String direccion,
                         String telefono) {
            this(nombreNegocio, emailAdmin, clave, plan, modo, cantidadMesas, nit, direccion,
                    telefono, null, null);
        }
    }

    /** Lo que se creó, para mostrárselo al KAM. */
    public record Resultado(
            String tenantId,
            String nombreNegocio,
            String emailAdmin,
            String plan,
            String modo,
            long siteId,
            int mesasCreadas,
            List<String> modulos,
            String perfil,
            String flujoDeVenta,
            boolean perfilRegistrado) {}

    /** El alta falló por un dato del formulario, no por un fallo del sistema. */
    public static class AltaInvalidaException extends RuntimeException {
        private final int codigo;

        public AltaInvalidaException(int codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }

        public int codigo() {
            return codigo;
        }
    }

    private static final String ROL_ADMIN = "admin";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PlanCatalogService planes;
    private final com.suresell.orders.flujo.FlujosDeVenta flujos;
    private final PerfilDelNegocio perfiles;

    // Se crea acá y no se inyecta: el proyecto no publica un bean de
    // PasswordEncoder (AuthService también instancia el suyo). Inyectarlo hacía
    // fallar el arranque entero por una dependencia que nadie provee.
    //
    // El @Autowired es imprescindible teniendo dos constructores: sin él Spring
    // no sabe cuál usar, busca el vacío y el contexto no levanta.
    @org.springframework.beans.factory.annotation.Autowired
    public AltaDeNegocioService(JdbcTemplate jdbc, PlanCatalogService planes,
                                com.suresell.orders.flujo.FlujosDeVenta flujos,
                                PerfilDelNegocio perfiles) {
        this(jdbc, new BCryptPasswordEncoder(), planes, flujos, perfiles);
    }

    /** Para tests: permite inyectar el codificador. */
    AltaDeNegocioService(JdbcTemplate jdbc, PasswordEncoder encoder, PlanCatalogService planes,
                         com.suresell.orders.flujo.FlujosDeVenta flujos, PerfilDelNegocio perfiles) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.planes = planes;
        this.flujos = flujos;
        this.perfiles = perfiles;
    }

    /** Alta sin decir quién la hace: solo para llamadores viejos y tests. */
    @Transactional
    public Resultado darDeAlta(Solicitud s) {
        return darDeAlta(s, "kam");
    }

    /**
     * @param quien el correo del KAM que da de alta (sale del JWT, no del
     *              cuerpo). Queda como {@code usuario_id} en el libro del perfil.
     */
    @Transactional
    public Resultado darDeAlta(Solicitud s, String quien) {
        String nombre = limpiar(s.nombreNegocio());
        String email = s.emailAdmin() == null ? "" : s.emailAdmin().trim().toLowerCase(Locale.ROOT);
        String clave = s.clave() == null ? "" : s.clave();

        if (nombre.isBlank() || email.isBlank() || clave.isBlank()) {
            throw new AltaInvalidaException(400, "El nombre del negocio, el email y la clave son obligatorios");
        }
        if (!email.contains("@")) {
            throw new AltaInvalidaException(400, "El email no parece válido");
        }
        if (clave.length() < 6) {
            throw new AltaInvalidaException(400, "La clave debe tener al menos 6 caracteres");
        }

        // V39 — por la función `existe_email`: `users.email` es UNIQUE GLOBAL, así
        // que la pregunta es cross-tenant y el KAM no trae negocio en sesión. Con
        // un count(*) normal y la política cerrada respondería siempre "libre".
        Boolean yaExiste = jdbc.queryForObject(
                "SELECT existe_email(?)", Boolean.class, email);
        if (Boolean.TRUE.equals(yaExiste)) {
            throw new AltaInvalidaException(409, "Ese email ya está registrado en otro negocio");
        }

        String plan = planValido(s.plan());

        // V48 — El perfil vertical va en el alta, no después: es lo que decide
        // con qué rasgos nacen los insumos y qué flujos puede vender. Y el flujo
        // de la sede tiene que ser uno de los que el perfil admite.
        Optional<PerfilDelNegocio.Perfil> perfil = perfilElegido(s.perfil());
        com.suresell.orders.flujo.FlujosDeVenta.Flujo flujo = flujoElegido(s, perfil);

        // Una sede que abre cuenta por mesa SIN mesas no puede vender: el POS
        // muestra el plano de mesas y estaria vacio. Que un flujo use mesas lo
        // dice el catálogo, no el nombre del flujo.
        int mesas = 0;
        if (flujo.usaMesas()) {
            mesas = s.cantidadMesas() == null ? 0 : s.cantidadMesas();
            if (mesas < 1) {
                throw new AltaInvalidaException(400,
                        "El flujo " + flujo.codigo() + " abre cuenta por mesa: necesita al menos una mesa");
            }
            if (mesas > 500) {
                throw new AltaInvalidaException(400, "El máximo es 500 mesas");
            }
        }

        String tenantId = slugUnico(nombre);

        jdbc.update("INSERT INTO tenants (id, name, plan, nit, address, phone) VALUES (?, ?, ?, ?, ?, ?)",
                tenantId, nombre, plan, limpiarONulo(s.nit()),
                limpiarONulo(s.direccion()), limpiarONulo(s.telefono()));

        // `users`, `sites`, `restaurant_tables` y `tenant_order_counters` tienen
        // RLS en modo FORCE: aplica hasta al dueño de la tabla. El KAM es
        // cross-tenant y no trae negocio en contexto, así que sin fijarlo acá los
        // INSERT de abajo NO insertarían nada —y sin error—. El `true` del tercer
        // parámetro lo acota a esta transacción.
        //
        // ⚠️ Esta línea estaba DESPUÉS del INSERT de `users`, que entonces no
        // tenía política por negocio. Con V39 sí la tiene, así que se movió
        // arriba. El comentario original ya nombraba tres tablas en FORCE
        // cuando `tenant_order_counters` todavía no lo estaba: describía el
        // estado deseado como si fuera el real. Ahora las cuatro lo están.
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);

        jdbc.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, ?, ?, ?)",
                email, encoder.encode(clave), tenantId, ROL_ADMIN);

        // La sede se crea explicitamente y no se deja al disparador de V28: asi
        // queda con el flujo elegido desde el minuto cero. `pos_mode` lo deriva
        // el trigger de V48 del catálogo; aquí no se escribe.
        Long siteId = jdbc.queryForObject(
                "INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                        + "VALUES (?, 'Principal', 'PRINCIPAL', ?, true) RETURNING id",
                Long.class, tenantId, flujo.codigo());
        String modo = jdbc.queryForObject("SELECT pos_mode FROM sites WHERE id = ?", String.class, siteId);

        // El contador arranca en 0: la primera venta sera el folio 1.
        jdbc.update("INSERT INTO tenant_order_counters (tenant_id, site_id, last_id) VALUES (?, ?, 0) "
                + "ON CONFLICT (tenant_id, site_id) DO NOTHING", tenantId, siteId);

        for (int n = 1; n <= mesas; n++) {
            jdbc.update("INSERT INTO restaurant_tables (tenant_id, site_id, number, active) "
                    + "VALUES (?, ?, ?, true)", tenantId, siteId, n);
        }

        List<String> modulos = new ArrayList<>(planes.modulesForPlan(plan));

        // El perfil queda asignado EN el alta, en la misma transacción, con la
        // fuente y la confianza que V50 reservó para el KAM. Si la base no tiene
        // el esquema del inventario (la suite de este servicio), no se escribe y
        // el resultado lo dice: `perfilRegistrado = false` no es un éxito a medias
        // silencioso, es un dato.
        boolean perfilRegistrado = perfil.isPresent()
                && perfiles.asignarPorElKam(tenantId, perfil.get().codigo(), quien, "alta:" + tenantId);

        return new Resultado(tenantId, nombre, email, plan, modo, siteId, mesas, modulos,
                perfil.map(PerfilDelNegocio.Perfil::codigo).orElse(null), flujo.codigo(),
                perfilRegistrado);
    }

    /**
     * El perfil es obligatorio cuando hay catálogo. Sin catálogo (solo la
     * cadena `public`) se acepta un código que tenga flujos declarados, o
     * ninguno: no hay dónde registrarlo.
     */
    private Optional<PerfilDelNegocio.Perfil> perfilElegido(String codigo) {
        boolean hayCatalogo = perfiles.hayCatalogo();
        if (codigo == null || codigo.isBlank()) {
            if (hayCatalogo) {
                throw new AltaInvalidaException(400, "El perfil vertical es obligatorio. Válidos: "
                        + String.join(" | ", perfiles.catalogo().stream()
                                .map(PerfilDelNegocio.Perfil::codigo).toList()));
            }
            return Optional.empty();
        }
        return Optional.of(perfiles.porCodigo(codigo).orElseThrow(() -> new AltaInvalidaException(400,
                "El perfil '" + codigo + "' no existe. Válidos: " + String.join(" | ",
                        perfiles.catalogo().stream().map(PerfilDelNegocio.Perfil::codigo).toList()))));
    }

    /**
     * El flujo de la sede: lo que mande `flujoDeVenta`, o `modo` si viene de un
     * KAM viejo, o el defecto del perfil. Siempre resuelto contra el catálogo, y
     * siempre uno de los que el perfil admite.
     */
    private com.suresell.orders.flujo.FlujosDeVenta.Flujo flujoElegido(
            Solicitud s, Optional<PerfilDelNegocio.Perfil> perfil) {
        String pedido = s.flujoDeVenta() != null && !s.flujoDeVenta().isBlank() ? s.flujoDeVenta() : s.modo();
        com.suresell.orders.flujo.FlujosDeVenta.Flujo flujo;
        if (pedido == null || pedido.isBlank()) {
            flujo = perfil.flatMap(p -> flujos.defectoDe(p.codigo())).orElseGet(flujos::defectoGlobal);
        } else {
            flujo = flujos.resolver(pedido).orElseThrow(() -> new AltaInvalidaException(400,
                    "El flujo de venta '" + pedido + "' no existe. Válidos: " + flujos.nombresValidos()));
        }
        if (perfil.isPresent()) {
            List<String> admitidos = perfil.get().flujos().stream()
                    .map(PerfilDelNegocio.FlujoAdmitido::codigo).toList();
            if (!admitidos.isEmpty() && !admitidos.contains(flujo.codigo())) {
                throw new AltaInvalidaException(400, "El perfil '" + perfil.get().codigo()
                        + "' no admite el flujo " + flujo.codigo() + ". Admite: " + String.join(" | ", admitidos));
            }
        }
        return flujo;
    }

    private String planValido(String plan) {
        String p = plan == null || plan.isBlank() ? "pro" : plan.trim().toLowerCase(Locale.ROOT);
        boolean existe = planes.catalogo().stream().anyMatch(x -> x.id().equalsIgnoreCase(p));
        if (!existe) {
            throw new AltaInvalidaException(400, "El plan '" + p + "' no existe");
        }
        return p;
    }

    /**
     * Identificador legible derivado del nombre, sin chocar con otro negocio.
     *
     * <p>Los acentos y la eñe se convierten a su letra base ANTES de limpiar.
     * Sin eso, "Pizzería" quedaba como {@code pizzer-a} y "Antioqueña" como
     * {@code antioque-a}: el carácter acentuado no entraba en {@code [a-z0-9]} y
     * se volvía un guion. En español eso es la norma, no la excepción.
     */
    static String slugDe(String nombre) {
        String sinAcentos = java.text.Normalizer
                .normalize(nombre, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinAcentos.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    private String slugUnico(String nombre) {
        String base = slugDe(nombre);
        if (base.isBlank()) {
            base = "negocio";
        }
        if (!existeTenant(base)) {
            return base;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidato = base + "-" + i;
            if (!existeTenant(candidato)) {
                return candidato;
            }
        }
        throw new AltaInvalidaException(409, "No se pudo generar un identificador libre para ese nombre");
    }

    private boolean existeTenant(String id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.trim();
    }

    private static String limpiarONulo(String s) {
        String t = limpiar(s);
        return t.isEmpty() ? null : t;
    }
}
