package com.suresell.orders.multitenant;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operación del super-admin (KAM) GLOBAL: login propio y administración cross-tenant
 * (listar negocios, cambiar plan, ajustar módulos, ver usuarios). Reusa
 * {@link AuthService} para la config de módulos/usuarios por tenant. F3, Inc.3.
 * SOLO perfil `cloud`. Emite un JWT con claim `super_admin=true` (sin tenant).
 */
@Service
@Profile("cloud")
public class SuperAdminService {

    private final SuperAdminRepository saRepo;
    private final AuthService authService;
    // N4 — el catálogo de planes vive en BD (V27) y lo edita el KAM.
    private final PlanRepository planRepo;
    private final PlanCatalogService planes;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    // V48 (ola 3): el flujo de venta y el perfil son datos; se leen de aquí.
    private final com.suresell.orders.flujo.FlujosDeVenta flujos;
    private final PerfilDelNegocio perfiles;
    private final SecretKey key;
    private final long ttlSeconds;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public SuperAdminService(
            SuperAdminRepository saRepo,
            AuthService authService,
            PlanRepository planRepo,
            PlanCatalogService planes,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.suresell.orders.flujo.FlujosDeVenta flujos,
            PerfilDelNegocio perfiles,
            @Value("${security.jwt.secret:cambia-esta-clave-en-produccion-min-32-bytes!}") String secret,
            @Value("${auth.token.ttl-seconds:43200}") long ttlSeconds) {
        this.saRepo = saRepo;
        this.authService = authService;
        this.planRepo = planRepo;
        this.planes = planes;
        this.jdbc = jdbc;
        this.flujos = flujos;
        this.perfiles = perfiles;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public record LoginResponse(String token, String email) {}

    public record TenantDetail(String tenantId, String name, String status,
                               AuthService.ModuleConfig modules,
                               List<AuthRepository.UserSummary> users) {}

    public LoginResponse login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new AuthException(400, "Email y contraseña son requeridos");
        }
        AuthException invalid = new AuthException(401, "Credenciales inválidas");
        var sa = saRepo.findByEmail(email.trim()).orElseThrow(() -> invalid);
        if (!encoder.matches(password, sa.passwordHash())) {
            throw invalid;
        }
        return new LoginResponse(issueToken(sa.email()), sa.email());
    }

    public List<SuperAdminRepository.TenantListItem> listTenants() {
        return saRepo.listTenants();
    }

    public TenantDetail getTenant(String tenantId) {
        AuthService.ModuleConfig cfg = authService.getModuleConfig(tenantId); // 404 si no existe
        List<AuthRepository.UserSummary> users = authService.listUsers(tenantId);
        // El nombre/status salen de la lista de negocios (evita otra consulta).
        var item = saRepo.listTenants().stream().filter(t -> t.id().equals(tenantId)).findFirst();
        return new TenantDetail(tenantId,
                item.map(SuperAdminRepository.TenantListItem::name).orElse(tenantId),
                item.map(SuperAdminRepository.TenantListItem::status).orElse("active"),
                cfg, users);
    }

    // ---------- La cuenta del negocio, vista por el KAM (ola 4) ----------

    public record Administrador(String email, String rol, boolean activo, java.time.Instant creadoEn) {}

    /**
     * Lo que el KAM ve al entrar a un negocio: el correo con el que se creó (el
     * primer usuario), los administradores con su estado, y el estado de la
     * cuenta. Nada de hashes ni de tokens.
     */
    public record Cuenta(String tenantId, String nombre, String estado, String correoPrincipal,
                         java.time.Instant creadoEn, List<Administrador> administradores) {}

    public Cuenta cuenta(String tenantId) {
        authService.getModuleConfig(tenantId); // 404 si no existe
        var item = saRepo.listTenants().stream().filter(t -> t.id().equals(tenantId)).findFirst();
        List<Administrador> admins = authService.administradores(tenantId).stream()
                .map(a -> new Administrador(a.email(), a.rol(), a.activo(), a.creadoEn()))
                .toList();
        // El correo principal es el del alta: el primero por fecha de creación.
        var principal = admins.stream().findFirst();
        return new Cuenta(tenantId,
                item.map(SuperAdminRepository.TenantListItem::name).orElse(tenantId),
                item.map(SuperAdminRepository.TenantListItem::status).orElse("active"),
                principal.map(Administrador::email).orElse(null),
                principal.map(Administrador::creadoEn).orElse(null),
                admins);
    }

    /** El KAM restablece la clave de un administrador del negocio. Ver {@code AuthService.restablecerPorElKam}. */
    public AuthService.EnvioDeReset restablecerClave(String tenantId, String email, String kam) {
        authService.getModuleConfig(tenantId); // 404 si no existe
        return authService.restablecerPorElKam(tenantId, email, kam);
    }

    public void setPlan(String tenantId, String plan) {
        String p = plan == null ? "" : plan.trim().toLowerCase();
        // Se valida contra el catálogo REAL: con un Set quemado, un plan creado
        // desde el KAM era inasignable.
        List<String> validos = planes.catalogo().stream()
                .filter(PlanRepository.Plan::active)
                .map(PlanRepository.Plan::id)
                .toList();
        if (!validos.contains(p)) {
            throw new AuthException(400, "Plan inválido (válidos: " + String.join("|", validos) + ")");
        }
        if (saRepo.updateTenantPlan(tenantId, p) == 0) {
            throw new AuthException(404, "Negocio no encontrado");
        }
    }

    public AuthService.ModuleConfig setModules(String tenantId, Map<String, Boolean> overrides) {
        return authService.setModuleOverrides(tenantId, overrides);
    }

    // ------------------------------------------------------------------
    // N4 — Catálogo: módulos conocidos y planes.
    // ------------------------------------------------------------------

    /** Un módulo, con etiqueta legible y dónde aplica. */
    public record ModuleInfo(String id, String label, String scope) {}

    public record Catalog(List<ModuleInfo> modules, List<PlanRepository.Plan> plans) {}

    /**
     * Lo que el KAM necesita para pintarse. El panel tenía la lista de módulos
     * QUEMADA con 4 de los 16 que conoce el backend, así que los demás no se
     * podían tocar y cada módulo nuevo había que acordarse de copiarlo.
     */
    public Catalog catalog() {
        List<ModuleInfo> mods = new java.util.ArrayList<>();
        for (String m : ORDEN_MODULOS) {
            mods.add(new ModuleInfo(m, ETIQUETAS.getOrDefault(m, m),
                    MODULOS_POS.contains(m) ? "pos" : "panel"));
        }
        // Cualquier módulo que exista en el backend y no esté en el orden de
        // arriba igual se muestra: mejor desordenado que invisible.
        for (String m : PlanCatalog.KNOWN) {
            if (!ORDEN_MODULOS.contains(m)) {
                mods.add(new ModuleInfo(m, m, "otro"));
            }
        }
        return new Catalog(mods, planes.catalogo());
    }

    private static final Set<String> MODULOS_POS = Set.of(
            PlanCatalog.VENTAS, PlanCatalog.HISTORIAL, PlanCatalog.CIERRE,
            PlanCatalog.DESCUENTOS, PlanCatalog.COCINA, PlanCatalog.MESEROS);

    private static final List<String> ORDEN_MODULOS = List.of(
            PlanCatalog.VENTAS, PlanCatalog.HISTORIAL, PlanCatalog.CIERRE,
            PlanCatalog.DESCUENTOS, PlanCatalog.COCINA, PlanCatalog.MESEROS,
            PlanCatalog.PANEL, PlanCatalog.ANALITICA, PlanCatalog.MENU_ADMIN,
            PlanCatalog.GASTOS, PlanCatalog.NOMINA, PlanCatalog.EMPLEADOS,
            PlanCatalog.VALERAS, PlanCatalog.INSUMOS, PlanCatalog.COMPRAS,
            PlanCatalog.CARTERA, PlanCatalog.PRODUCCION, PlanCatalog.VENTA_SIN_REGISTRO);

    private static final Map<String, String> ETIQUETAS = Map.ofEntries(
            Map.entry(PlanCatalog.VENTAS, "Ventas (POS)"),
            Map.entry(PlanCatalog.HISTORIAL, "Historial de órdenes"),
            Map.entry(PlanCatalog.CIERRE, "Cierre de caja"),
            Map.entry(PlanCatalog.DESCUENTOS, "Descuentos y cupones"),
            Map.entry(PlanCatalog.COCINA, "App de cocina"),
            Map.entry(PlanCatalog.MESEROS, "App de meseros"),
            Map.entry(PlanCatalog.PANEL, "Panel de administración"),
            Map.entry(PlanCatalog.ANALITICA, "Analítica"),
            Map.entry(PlanCatalog.MENU_ADMIN, "Menú y productos"),
            Map.entry(PlanCatalog.GASTOS, "Gastos"),
            Map.entry(PlanCatalog.NOMINA, "Nómina"),
            Map.entry(PlanCatalog.EMPLEADOS, "Empleados"),
            Map.entry(PlanCatalog.VALERAS, "Valeras"),
            Map.entry(PlanCatalog.INSUMOS, "Insumos"),
            Map.entry(PlanCatalog.COMPRAS, "Compras"),
            Map.entry(PlanCatalog.CARTERA, "Cartera"),
            Map.entry(PlanCatalog.PRODUCCION, "Producción (recetas de lo que se prepara)"),
            Map.entry(PlanCatalog.VENTA_SIN_REGISTRO, "Vender sin registrar (la caja acepta un producto que no está en el catálogo)"));

    /** Crea un plan. El id es el slug con el que se guarda en `tenants.plan`. */
    public PlanRepository.Plan createPlan(String id, String name, String description,
                                          List<String> modules) {
        String slug = id == null ? "" : id.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (slug.isBlank()) {
            throw new AuthException(400, "El id del plan es obligatorio (letras, números, - y _)");
        }
        if (planRepo.exists(slug)) {
            throw new AuthException(409, "Ya existe un plan con id '" + slug + "'");
        }
        String nombre = name == null || name.isBlank() ? slug : name.trim();
        planRepo.insert(slug, nombre, description == null ? null : description.trim());
        planRepo.replaceModules(slug, validarModulos(modules));
        planes.invalidar();
        return buscarPlan(slug);
    }

    /** Edita nombre, descripción, estado y módulos de un plan. */
    public PlanRepository.Plan updatePlan(String id, String name, String description,
                                          Boolean active, List<String> modules) {
        if (!planRepo.exists(id)) {
            throw new AuthException(404, "Plan no encontrado: " + id);
        }
        boolean activo = active == null || active;
        if (!activo && planRepo.countTenants(id) > 0) {
            // Desactivar solo lo saca del selector; los negocios que ya lo tienen
            // lo conservan. Avisar es mejor que sorprender.
            throw new AuthException(409, "No se desactiva: hay " + planRepo.countTenants(id)
                    + " negocio(s) en este plan. Muévelos primero.");
        }
        PlanRepository.Plan actual = buscarPlan(id);
        planRepo.update(id,
                name == null || name.isBlank() ? actual.name() : name.trim(),
                description == null ? actual.description() : description.trim(),
                activo);
        if (modules != null) {
            planRepo.replaceModules(id, validarModulos(modules));
        }
        planes.invalidar();
        return buscarPlan(id);
    }

    private List<String> validarModulos(List<String> modules) {
        if (modules == null) {
            return List.of();
        }
        List<String> desconocidos = modules.stream()
                .filter(m -> !PlanCatalog.isKnownModule(m))
                .toList();
        if (!desconocidos.isEmpty()) {
            throw new AuthException(400, "Módulos desconocidos: " + String.join(", ", desconocidos));
        }
        return modules.stream().map(m -> m.trim().toLowerCase()).distinct().toList();
    }

    private PlanRepository.Plan buscarPlan(String id) {
        return planRepo.findAll().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AuthException(404, "Plan no encontrado: " + id));
    }

    /**
     * Modo de POS de las sedes de un negocio (Inc. 1 del modo Restaurante).
     *
     * Va por JdbcTemplate con `set_config` explícito porque `sites` tiene RLS
     * FORCE y el KAM es cross-tenant: sin fijar el tenant en la sesión no vería
     * ninguna fila. El `true` del tercer parámetro lo acota a la transacción.
     *
     * <h3>Le faltaba la transacción, y por eso no devolvía nada</h3>
     *
     * El {@code true} acota el {@code set_config} <b>a la transacción</b>, así
     * que sin {@code @Transactional} este método no tenía ninguna: en autocommit
     * cada sentencia es su propia transacción, el valor se descartaba antes del
     * {@code SELECT} de la línea siguiente, y encima el {@code JdbcTemplate}
     * tomaba otra conexión del pool que {@code TenantAwareDataSource} reinicia a
     * cadena vacía.
     *
     * <p>Medido contra Staging el 2026-08-25, con un JWT de super-admin real:
     * {@code GET /admin/tenants/shark-burger/sites} devolvía <b>200 con lista
     * vacía</b>, teniendo ese negocio su sede PRINCIPAL. Un 200 con cero sedes y
     * "este negocio no tiene sedes" son la misma pantalla.
     *
     * <p>{@code setSiteMode}, justo debajo, sí llevaba la anotación desde el
     * principio — que es lo que hace que ese sí funcione. Mismo código, misma
     * línea, resultado opuesto.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> getSites(String tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        return sedes(tenantId);
    }

    /**
     * El negocio va explícito en el WHERE además de en la sesión (RLS). Antes
     * solo estaba en la sesión; con un usuario que salte RLS —el de una
     * migración, el de un test— la consulta devolvía las sedes de TODOS los
     * negocios sin que nada fallara.
     */
    private java.util.List<Map<String, Object>> sedes(String tenantId) {
        // V48: `pos_mode` se conserva para el KAM viejo; el nuevo lee `flujo_de_venta`.
        return jdbc.queryForList(
                "SELECT s.id, s.name, s.code, s.pos_mode, s.flujo_de_venta, f.nombre AS flujo_nombre, "
                        + "f.usa_mesas, f.usa_rastreador, s.active, s.is_default, "
                        // V52: dos nombres para el mismo dato — snake_case como sus
                        // hermanos de esta fila, camelCase como el contrato del POS.
                        + "s.imprime_tirilla, s.imprime_tirilla AS \"imprimeTirilla\" "
                        + "FROM sites s JOIN flujos_de_venta f ON f.codigo = s.flujo_de_venta "
                        + "WHERE s.tenant_id = ? ORDER BY s.id", tenantId);
    }

    /**
     * V52 — «Esta sede imprime tirilla». Solo el KAM: es parte de cómo se
     * instala el negocio, no una preferencia. Con {@code false} el POS deja de
     * buscar el programa de impresión y no muestra la alerta roja.
     */
    @org.springframework.transaction.annotation.Transactional
    public java.util.List<Map<String, Object>> setSiteImprimeTirilla(String tenantId, Long siteId,
                                                                     Boolean imprimeTirilla) {
        if (imprimeTirilla == null) {
            throw new AuthException(400, "Falta imprimeTirilla (true|false)");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        int filas = jdbc.update(
                "UPDATE sites SET imprime_tirilla = ? WHERE id = ? AND tenant_id = ?",
                imprimeTirilla, siteId, tenantId);
        if (filas == 0) {
            throw new AuthException(404, "No existe la sede " + siteId + " en el negocio " + tenantId);
        }
        return sedes(tenantId);
    }

    /**
     * Cambia el flujo de venta de una sede. Es potestad EXCLUSIVA del KAM: se
     * vende, no se elige. Acepta un código del catálogo o un {@code posMode}
     * viejo; los dos se resuelven contra {@code flujos_de_venta}. Si el negocio
     * tiene perfil, el flujo tiene que ser uno de los que el perfil admite.
     */
    @org.springframework.transaction.annotation.Transactional
    public java.util.List<Map<String, Object>> setSiteMode(String tenantId, Long siteId, String mode,
                                                          String quienCambia) {
        com.suresell.orders.flujo.FlujosDeVenta.Flujo flujo = flujos.resolver(mode).orElseThrow(
                () -> new AuthException(400, "Flujo de venta inválido. Use " + flujos.nombresValidos()));
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);

        String flujoActual = jdbc.query(
                "SELECT flujo_de_venta FROM sites WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, siteId, tenantId);
        if (flujoActual == null) {
            throw new AuthException(404, "No existe la sede " + siteId + " en el negocio " + tenantId);
        }

        java.util.Optional<PerfilDelNegocio.Vigente> vigente = perfiles.vigente(tenantId);
        if (vigente.isPresent()) {
            java.util.List<String> admitidos = flujos.admitidosPor(vigente.get().codigo()).stream()
                    .map(f -> f.flujo().codigo()).toList();
            if (!admitidos.isEmpty() && !admitidos.contains(flujo.codigo())) {
                throw new AuthException(400, "El perfil '" + vigente.get().codigo() + "' no admite el flujo "
                        + flujo.codigo() + ". Admite: " + String.join(" | ", admitidos));
            }
        }

        // Cambiar de flujo con consumo abierto dejaría cuentas huérfanas: sin
        // mesas el POS ni siquiera dibuja el plano, así que nadie podría
        // cobrarlas. Se bloquea del lado seguro. Solo si el flujo CAMBIA de
        // verdad y hay cuentas VIVAS.
        boolean cambia = !flujo.codigo().equals(flujoActual);
        if (cambia) {
            List<Map<String, Object>> vivas = jdbc.queryForList(
                    "SELECT s.id, t.number AS mesa, s.status "
                            + "FROM table_sessions s JOIN restaurant_tables t ON t.id = s.table_id "
                            + "WHERE s.status <> 'CERRADA' AND t.tenant_id = ? ORDER BY t.number", tenantId);
            if (!vivas.isEmpty()) {
                String mesas = vivas.stream()
                        .map(m -> String.valueOf(m.get("mesa")))
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new AuthException(409,
                        "No se puede cambiar el flujo con cuentas abiertas. "
                                + "Cobra o cierra primero la(s) mesa(s): " + mesas);
            }
        }

        // `pos_mode` lo deriva el trigger de V48 del catálogo; aquí no se escribe.
        int filas = jdbc.update("UPDATE sites SET flujo_de_venta = ? WHERE id = ? AND tenant_id = ?",
                flujo.codigo(), siteId, tenantId);
        if (filas == 0) {
            throw new AuthException(404, "No existe la sede " + siteId + " en el negocio " + tenantId);
        }

        // Quién y cuándo: cambiar el flujo es una acción de alto impacto operativo.
        if (cambia) {
            jdbc.update("INSERT INTO site_mode_audit (tenant_id, site_id, modo_antes, modo_despues, hecho_por) "
                    + "VALUES (?, ?, ?, ?, ?)", tenantId, siteId, flujoActual, flujo.codigo(), quienCambia);
        }
        return sedes(tenantId);
    }

    // ------------------------------------------------------------------
    // V48 (ola 3) — Perfil vertical desde el KAM, y catálogos.
    // ------------------------------------------------------------------

    public java.util.List<PerfilDelNegocio.Perfil> perfiles() {
        return perfiles.catalogo();
    }

    public java.util.List<com.suresell.orders.flujo.FlujosDeVenta.Flujo> flujos() {
        return flujos.todos();
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Optional<PerfilDelNegocio.Vigente> perfilVigente(String tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        return perfiles.vigente(tenantId);
    }

    /**
     * Cambia el perfil vertical de un negocio. SOLO el KAM. Es una fila nueva en
     * un libro append-only. <b>No reconvierte nada</b>: los rasgos por defecto
     * del perfil aplican a los insumos que se creen desde ahora; los que ya
     * existen se quedan como están. El KAM lo lee antes de confirmar (en su
     * pantalla), no aquí.
     */
    @org.springframework.transaction.annotation.Transactional
    public PerfilDelNegocio.Vigente setPerfil(String tenantId, String codigo, String quien) {
        if (!perfiles.hayCatalogo()) {
            throw new AuthException(409, "Esta base no tiene el catálogo de perfiles del inventario");
        }
        PerfilDelNegocio.Perfil perfil = perfiles.porCodigo(codigo).orElseThrow(() -> new AuthException(400,
                "El perfil '" + codigo + "' no existe. Válidos: " + String.join(" | ",
                        perfiles.catalogo().stream().map(PerfilDelNegocio.Perfil::codigo).toList())));
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId);
        if (existe == null || existe == 0) {
            throw new AuthException(404, "Negocio no encontrado");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        perfiles.asignarPorElKam(tenantId, perfil.codigo(), quien, "kam:cambio-de-perfil");
        return perfiles.vigente(tenantId).orElseThrow(() -> new AuthException(500,
                "El perfil se anotó pero la vista no lo devuelve"));
    }

    // ------------------------------------------------------------------
    // V48 (ola 3) — Más cuentas de KAM, desde el KAM. La primera la crea
    // `KamDeArranque` por variable de entorno; las siguientes entran por aquí.
    // ------------------------------------------------------------------

    public record SuperAdminCreado(String email) {}

    public SuperAdminCreado createSuperAdmin(String email, String password, String quien) {
        String correo = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (correo.isBlank() || !correo.contains("@")) {
            throw new AuthException(400, "El email del KAM no parece válido");
        }
        try {
            ClaveDeKam.validar(password, correo);
        } catch (IllegalArgumentException e) {
            throw new AuthException(400, e.getMessage());
        }
        if (saRepo.findByEmail(correo).isPresent()) {
            throw new AuthException(409, "Ya existe un KAM con ese email");
        }
        saRepo.insert(correo, encoder.encode(password));
        org.slf4j.LoggerFactory.getLogger(SuperAdminService.class)
                .warn("Cuenta de KAM creada: {} (por {})", correo, quien);
        return new SuperAdminCreado(correo);
    }

    private String issueToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("super_admin", true)
                .subject(email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }
}
