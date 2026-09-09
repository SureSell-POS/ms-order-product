package com.suresell.orders.multitenant;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auth real (F1): login por email+contraseña (deriva el tenant del usuario) y
 * alta self-service de un negocio. Emite el JWT de tenant que valida
 * {@link JwtTenantResolver}. SOLO perfil `cloud`. Ver docs/110-plan-auth-real.md.
 */
@lombok.extern.log4j.Log4j2
@Service
@Profile("cloud")
public class AuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private static final String DEFAULT_PLAN = "pro";
    private static final String ADMIN_ROLE = "admin";

    private final AuthRepository repo;
    private final SecretKey key;
    private final long ttlSeconds;
    private final String registerKey;
    private final String businessEditKey;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // Reset de contraseña (F3, Inc.5). Config por env.
    @org.springframework.beans.factory.annotation.Value("${auth.reset.link-base:}")
    private String resetLinkBase;
    @org.springframework.beans.factory.annotation.Value("${auth.reset.ttl-minutes:60}")
    private long resetTtlMinutes;
    @org.springframework.beans.factory.annotation.Value("${auth.reset.expose-link:false}")
    private boolean resetExposeLink;
    @org.springframework.beans.factory.annotation.Value("${auth.reset.edge-url:}")
    private String resetEdgeUrl;
    @org.springframework.beans.factory.annotation.Value("${auth.reset.edge-key:}")
    private String resetEdgeKey;

    /** N4 — el mapa plan → módulos ahora vive en BD (V27), no en constantes. */
    private final PlanCatalogService planes;

    public AuthService(
            AuthRepository repo,
            PlanCatalogService planes,
            @Value("${security.jwt.secret:cambia-esta-clave-en-produccion-min-32-bytes!}") String secret,
            @Value("${auth.token.ttl-seconds:43200}") long ttlSeconds,
            @Value("${auth.register.key:}") String registerKey,
            @Value("${business.edit.password:}") String businessEditKey) {
        this.repo = repo;
        this.planes = planes;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
        this.registerKey = registerKey;
        this.businessEditKey = businessEditKey;
    }

    public record AuthResponse(String token, String tenantId, String tenantName,
                               String plan, String userName, String role,
                               String nit, String address, String phone, String ticketFooter,
                               List<String> modules) {}

    /** Perfil del negocio (datos que se imprimen en el ticket). */
    public record BusinessProfile(String tenantId, String name, String nit,
                                  String address, String phone, String ticketFooter) {}

    /**
     * Login por credenciales de usuario; el tenant sale de su cuenta.
     *
     * <h3>Por qué es {@code @Transactional}</h3>
     *
     * V40 cierra la política de {@code tenant_modules}, que se lee aquí abajo en
     * {@code effectiveModulesFor}. Para ese momento el negocio <b>ya se conoce</b>
     * —lo acaba de devolver {@code findTenant}— así que no hace falta ninguna
     * función privilegiada: basta con fijarlo. Pero fijarlo solo sirve dentro de
     * una transacción: con el pool, cada sentencia suelta toma su propia conexión
     * y {@code TenantAwareDataSource} la reinicia a cadena vacía.
     *
     * <p>El coste: la transacción abarca también el {@code encoder.matches}, que
     * es BCrypt y tarda del orden de 100 ms con una conexión retenida. Se acepta
     * porque es el mismo intercambio que ya hace {@code register}, y porque el
     * volumen de logins de un local es de unos pocos al día. Si algún día pesa,
     * lo que toca es acotar la transacción a la lectura de módulos, no quitarla.
     */
    @Transactional
    public AuthResponse login(String email, String password) {
        if (isBlank(email) || isBlank(password)) {
            throw new AuthException(400, "Email y contraseña son requeridos");
        }
        // Mensaje genérico e idéntico para "no existe" y "clave mala": no revela
        // qué emails están registrados.
        AuthException invalid = new AuthException(401, "Credenciales inválidas");
        // V39 — por la función `buscar_usuario_para_login`, no por `users` directo:
        // aquí todavía no hay negocio en sesión y no puede haberlo, porque el
        // negocio es justamente lo que esta consulta averigua.
        var user = repo.buscarUsuarioParaLogin(email.trim()).orElseThrow(() -> invalid);
        if (!encoder.matches(password, user.passwordHash())) {
            throw invalid;
        }
        if (!user.activo()) {
            throw new AuthException(403, "Usuario deshabilitado");
        }
        var tenant = repo.findTenant(user.tenantId())
                .orElseThrow(() -> new AuthException(403, "El negocio no está disponible"));
        if (!"active".equals(tenant.status())) {
            throw new AuthException(403, "El negocio está suspendido");
        }
        // V40 — a partir de aquí el negocio se conoce, así que `tenant_modules`
        // se lee por RLS normal. Sin esta línea la política cerrada devolvería
        // CERO overrides y el login no fallaría: emitiría un JWT con los módulos
        // del plan a secas. Un negocio con módulos regalados o revocados los
        // perdería en cada login, sin un solo error en ninguna parte.
        repo.fijarNegocioEnLaTransaccion(tenant.id());
        List<String> modules = effectiveModulesFor(tenant.id(), tenant.plan());
        String token = issueToken(tenant.id(), user.email(), user.rol(), modules);
        return new AuthResponse(token, tenant.id(), tenant.name(), tenant.plan(),
                user.email(), user.rol(),
                tenant.nit(), tenant.address(), tenant.phone(), tenant.ticketFooter(), modules);
    }

    /**
     * Alta de un negocio (crea tenant + usuario admin y devuelve sesión iniciada).
     * NO es abierto al público: exige la clave de registro que solo conoce el KAM
     * (env AUTH_REGISTER_KEY). Fail-closed: si no hay clave configurada, el registro
     * queda deshabilitado. Ver docs/110 §8 / docs/120 §4.2.
     */
    @Transactional
    public AuthResponse register(String businessName, String email, String password,
                                 String providedRegisterKey,
                                 String nit, String address, String phone) {
        if (isBlank(registerKey)) {
            throw new AuthException(403, "El registro no está habilitado");
        }
        if (isBlank(providedRegisterKey) || !registerKey.equals(providedRegisterKey)) {
            throw new AuthException(403, "Clave de registro inválida");
        }
        if (isBlank(businessName) || isBlank(email) || isBlank(password)) {
            throw new AuthException(400, "Negocio, email y contraseña son requeridos");
        }
        if (password.trim().length() < 6) {
            throw new AuthException(400, "La contraseña debe tener al menos 6 caracteres");
        }
        String cleanEmail = email.trim();
        if (repo.emailExists(cleanEmail)) {
            throw new AuthException(409, "Ese email ya está registrado");
        }
        String tenantId = uniqueSlug(businessName);
        repo.insertTenant(tenantId, businessName.trim(), DEFAULT_PLAN,
                trimOrNull(nit), trimOrNull(address), trimOrNull(phone));

        // V39 — `users` está en FORCE ROW LEVEL SECURITY y su WITH CHECK exige
        // que `tenant_id` coincida con el negocio de la sesión. Aquí el negocio
        // ya existe (se acaba de crear en la línea de arriba), así que no hace
        // falta ninguna función privilegiada: basta con fijarlo.
        //
        // Sin esta línea el INSERT de abajo NO insertaría nada Y NO DARÍA ERROR
        // — devolvería 0 filas y el método seguiría hasta emitir un JWT para un
        // usuario que no existe. El negocio quedaría creado y sin dueño.
        //
        // El método es @Transactional, que es lo que hace que el `set_config`
        // acotado a la transacción llegue vivo hasta el INSERT: con el pool,
        // fuera de transacción cada sentencia toma su propia conexión y
        // `TenantAwareDataSource` la reinicia a cadena vacía.
        repo.fijarNegocioEnLaTransaccion(tenantId);
        repo.insertUser(cleanEmail, encoder.encode(password), tenantId, ADMIN_ROLE);

        List<String> modules = planes.modulesForPlan(DEFAULT_PLAN);
        String token = issueToken(tenantId, cleanEmail, ADMIN_ROLE, modules);
        return new AuthResponse(token, tenantId, businessName.trim(), DEFAULT_PLAN,
                cleanEmail, ADMIN_ROLE,
                trimOrNull(nit), trimOrNull(address), trimOrNull(phone), null, modules);
    }

    /** Perfil del negocio del tenant autenticado (para mostrar/editar sus datos). */
    public BusinessProfile getBusiness(String tenantId) {
        var t = repo.findTenant(tenantId)
                .orElseThrow(() -> new AuthException(404, "Negocio no encontrado"));
        return new BusinessProfile(t.id(), t.name(), t.nit(), t.address(), t.phone(),
                t.ticketFooter());
    }

    /**
     * Actualiza el perfil del negocio (datos del ticket) del tenant autenticado.
     * Protegido por una clave de edición (env BUSINESS_EDIT_PASSWORD): los datos son
     * fiscales (NIT, etc.), así que no basta con estar logueado en el POS — hay que
     * conocer esta clave (la tiene el KAM/dueño, no el cajero). Fail-closed: si no
     * hay clave configurada, la edición queda deshabilitada. Ver docs/120.
     */
    public BusinessProfile updateBusiness(String tenantId, String name, String nit,
                                          String address, String phone, String ticketFooter,
                                          String editPassword) {
        if (isBlank(businessEditKey)) {
            throw new AuthException(403, "La edición de datos del negocio no está habilitada");
        }
        if (isBlank(editPassword) || !businessEditKey.equals(editPassword)) {
            throw new AuthException(403, "Clave de edición inválida");
        }
        if (isBlank(name)) {
            throw new AuthException(400, "El nombre del negocio es requerido");
        }
        repo.updateBusinessProfile(tenantId, name.trim(), trimOrNull(nit),
                trimOrNull(address), trimOrNull(phone), trimOrNull(ticketFooter));
        return getBusiness(tenantId);
    }

    /**
     * Cambia la contraseña del usuario autenticado (email+tenant del JWT), tras
     * verificar la actual. No emite token nuevo (el actual sigue vigente). Ver docs/110 §8.
     */
    public void changePassword(String email, String tenantId, String currentPassword, String newPassword) {
        if (isBlank(currentPassword) || isBlank(newPassword)) {
            throw new AuthException(400, "La contraseña actual y la nueva son requeridas");
        }
        if (newPassword.trim().length() < 6) {
            throw new AuthException(400, "La nueva contraseña debe tener al menos 6 caracteres");
        }
        var user = repo.findUserByEmail(email)
                .orElseThrow(() -> new AuthException(401, "Sesión inválida"));
        if (!user.tenantId().equals(tenantId)) {
            // El email del token no pertenece al tenant del token: token manipulado.
            throw new AuthException(401, "Sesión inválida");
        }
        if (!encoder.matches(currentPassword, user.passwordHash())) {
            throw new AuthException(401, "La contraseña actual no es correcta");
        }
        repo.updatePasswordHash(email, tenantId, encoder.encode(newPassword));
    }

    /** Módulos efectivos del tenant = plan ± overrides (F3, Inc.2). */
    private List<String> effectiveModulesFor(String tenantId, String plan) {
        Map<String, Boolean> overrides = new HashMap<>();
        for (AuthRepository.ModuleOverride o : repo.getOverrides(tenantId)) {
            overrides.put(o.module(), o.enabled());
        }
        return planes.effectiveModules(plan, overrides);
    }

    public record ModuleConfig(String plan, List<String> planModules,
                               Map<String, Boolean> overrides, List<String> effectiveModules) {}

    /**
     * Configuración de módulos del tenant (plan + overrides + efectivos). Para el panel.
     *
     * <p>{@code @Transactional} + fijar el negocio por la misma razón que en el
     * login, y con un llamador que lo necesita de verdad:
     * {@code /admin/tenants/{id}/modules} (`SuperAdminService:78`) es una ruta de
     * super-admin, exenta del filtro de negocio, que consulta el negocio de OTRO.
     * El llamador de {@code /account/modules} ya trae el suyo en el contexto, así
     * que ahí fijarlo es un no-op.
     */
    @Transactional
    public ModuleConfig getModuleConfig(String tenantId) {
        var tenant = repo.findTenant(tenantId)
                .orElseThrow(() -> new AuthException(404, "Negocio no encontrado"));
        repo.fijarNegocioEnLaTransaccion(tenantId);
        Map<String, Boolean> overrides = new HashMap<>();
        for (AuthRepository.ModuleOverride o : repo.getOverrides(tenantId)) {
            overrides.put(o.module(), o.enabled());
        }
        return new ModuleConfig(tenant.plan(), planes.modulesForPlan(tenant.plan()),
                overrides, planes.effectiveModules(tenant.plan(), overrides));
    }

    /**
     * Fija overrides de módulos (admin). Por cada entrada: true regala, false quita,
     * null borra el override (vuelve a decidirse por el plan). Devuelve la config
     * resultante. Los cambios aplican al PRÓXIMO login del usuario (el JWT lleva los
     * módulos). Ver docs/160.
     */
    @Transactional
    public ModuleConfig setModuleOverrides(String tenantId, Map<String, Boolean> overrides) {
        // 🔴 ESTA ES LA LÍNEA QUE TIENE QUE IR CON V40 EN EL MISMO CAMBIO.
        //
        // `/admin/tenants/{id}/modules` (SuperAdminController:100) es una ruta de
        // super-admin: está exenta del TenantContextFilter (:51), así que la
        // conexión sale con app.tenant_id = ''. Con la política de
        // `tenant_modules` cerrada y sin esta línea, el UPSERT y el DELETE de
        // abajo afectarían a CERO filas y devolverían 200 igual.
        //
        // El KAM regalaría un módulo, vería la pantalla confirmar el cambio, y el
        // negocio no lo tendría. Separar esta línea de la migración habría sido
        // fabricar exactamente el fallo que la migración viene a eliminar.
        repo.fijarNegocioEnLaTransaccion(tenantId);
        if (overrides != null) {
            for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
                if (!PlanCatalog.isKnownModule(e.getKey())) {
                    throw new AuthException(400, "Módulo desconocido: " + e.getKey());
                }
                if (e.getValue() == null) {
                    repo.deleteOverride(tenantId, e.getKey());
                } else {
                    repo.upsertOverride(tenantId, e.getKey(), e.getValue());
                }
            }
        }
        return getModuleConfig(tenantId);
    }

    private static final Set<String> VALID_ROLES = Set.of("admin", "cajero");

    /** Lista los usuarios del tenant (sin hash). Lo usa el panel de usuarios (admin). */
    @Transactional(readOnly = true)
    public List<AuthRepository.UserSummary> listUsers(String tenantId) {
        // Desde V39 `users` va por RLS normal: sin negocio en la transacción el
        // KAM (que no lleva negocio en sesión) veía una lista VACÍA en
        // /admin/tenants/{id}. Medido en staging el 2026-09-09.
        repo.fijarNegocioEnLaTransaccion(tenantId);
        return repo.listUsers(tenantId);
    }

    /**
     * Crea un usuario en el tenant (F3, gestión de usuarios). Rol ∈ {admin, cajero}.
     * La autorización (que el solicitante sea admin) la verifica el controlador con
     * el rol del JWT. Devuelve el usuario creado (sin hash).
     */
    public AuthRepository.UserSummary createUser(String tenantId, String email,
                                                 String password, String role) {
        if (isBlank(email) || isBlank(password)) {
            throw new AuthException(400, "Email y contraseña son requeridos");
        }
        if (password.trim().length() < 6) {
            throw new AuthException(400, "La contraseña debe tener al menos 6 caracteres");
        }
        String r = isBlank(role) ? "cajero" : role.trim().toLowerCase();
        if (!VALID_ROLES.contains(r)) {
            throw new AuthException(400, "Rol inválido (admin|cajero)");
        }
        String cleanEmail = email.trim();
        if (repo.emailExists(cleanEmail)) {
            throw new AuthException(409, "Ese email ya está registrado");
        }
        repo.insertUser(cleanEmail, encoder.encode(password), tenantId, r);
        return repo.listUsers(tenantId).stream()
                .filter(u -> u.email().equalsIgnoreCase(cleanEmail))
                .findFirst()
                .orElseThrow(() -> new AuthException(500, "No se pudo leer el usuario creado"));
    }

    // ---------- Reset de contraseña (F3, Inc.5) ----------

    public record ForgotResponse(boolean sent, String link) {}

    /**
     * Inicia el reset: si el email existe, genera un token de un solo uso (hash en
     * DB), lo envía por email (Edge Function) y —solo en staging con expose-link—
     * devuelve el link para pruebas. SIEMPRE responde igual (no revela si el email
     * existe). Ver docs/160.
     */
    @Transactional
    public ForgotResponse forgotPassword(String email) {
        if (isBlank(email)) {
            throw new AuthException(400, "Email es requerido");
        }
        // V39 — misma razón que en el login: aquí no hay negocio en sesión y no
        // puede haberlo, porque llega un correo y nada más.
        var user = repo.buscarUsuarioParaLogin(email.trim());
        String exposed = null;
        if (user.isPresent()) {
            String token = randomToken();
            Instant expires = Instant.now().plus(resetTtlMinutes, ChronoUnit.MINUTES);
            // A partir de aquí el negocio SÍ se conoce: sale del usuario que
            // acaba de encontrarse. `password_resets` está en FORCE, así que sin
            // fijarlo el INSERT insertaría cero filas sin dar error y el correo
            // saldría con un token que no existe en ninguna parte.
            repo.fijarNegocioEnLaTransaccion(user.get().tenantId());
            repo.insertReset(sha256(token), user.get().email(), user.get().tenantId(), expires);
            String link = buildResetLink(token);
            sendResetEmail(user.get().email(), link); // best-effort (no rompe el flujo)
            if (resetExposeLink) {
                exposed = link;
            }
        }
        return new ForgotResponse(true, exposed);
    }

    /**
     * Aplica la nueva contraseña usando el token del email. Un solo uso.
     *
     * <h3>Por qué es {@code @Transactional}</h3>
     *
     * Antes eran dos sentencias sueltas: primero cambiar el hash, después marcar
     * el token. Si la segunda fallaba —caída de conexión, timeout del pool—
     * <b>la contraseña quedaba cambiada y el enlace seguía sirviendo su hora
     * completa</b>. Un enlace de un solo uso que se puede usar dos veces no es
     * de un solo uso.
     *
     * <p>Y las dos comprueban ahora cuántas filas tocaron. Un {@code UPDATE} que
     * cambia cero filas no lanza nada: devuelve 0 y el método respondía
     * {@code ok}. Ese es exactamente el modo de fallo que aparecerá en cuanto
     * {@code users} tenga su política cerrada —la fila deja de ser visible y el
     * {@code UPDATE} no toca nada— así que comprobarlo no es defensivo, es el
     * arreglo.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (isBlank(token) || isBlank(newPassword)) {
            throw new AuthException(400, "Token y nueva contraseña son requeridos");
        }
        if (newPassword.trim().length() < 6) {
            throw new AuthException(400, "La contraseña debe tener al menos 6 caracteres");
        }
        String hash = sha256(token);
        var consulta = repo.buscarReset(hash);

        if (consulta.estado() != EstadoDelToken.valido) {
            // El mensaje al usuario sigue siendo ambiguo A PROPÓSITO: distinguir
            // los casos en la respuesta HTTP convertiría esto en un oráculo para
            // averiguar qué tokens existen. El log sí distingue, que es donde
            // hace falta y donde no lo ve nadie de fuera.
            //
            // Se registra el prefijo del HASH, nunca el token: el hash ya está en
            // la base, así que no revela nada nuevo y permite encontrar la fila.
            log.warn("Reset rechazado ({}): token con hash {}…", consulta.estado(),
                    hash.substring(0, Math.min(8, hash.length())));
            throw new AuthException(400, "Enlace inválido o expirado");
        }

        // V39 — el negocio sale de la fila del token, y a partir de aquí se
        // conoce. Las dos escrituras de abajo tocan `users` y `password_resets`,
        // las dos en FORCE: sin fijarlo cambiarían cero filas. Eso ya no pasaría
        // en silencio —las comprobaciones de más abajo lo cazarían— pero
        // convertiría cada recuperación de contraseña en un 500.
        repo.fijarNegocioEnLaTransaccion(consulta.tenantId());

        int cambiadas = repo.updatePasswordHash(
                consulta.email(), consulta.tenantId(), encoder.encode(newPassword));
        if (cambiadas != 1) {
            // Revierte la transacción entera. Responder ok con cero filas
            // cambiadas sería decirle al usuario que su contraseña es otra
            // cuando sigue siendo la misma.
            throw new AuthException(500,
                    "No se pudo aplicar la nueva contraseña; inténtalo de nuevo");
        }

        int marcadas = repo.markResetUsed(hash);
        if (marcadas != 1) {
            // Si el token no se puede marcar, la contraseña NO se cambia: entran
            // o no entran las dos cosas. Un enlace reutilizable es peor que un
            // reset que hay que repetir.
            throw new AuthException(500,
                    "No se pudo invalidar el enlace; la contraseña no se cambió");
        }
        log.info("Reset completado para el negocio {}", consulta.tenantId());
    }

    private String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String buildResetLink(String token) {
        String base = isBlank(resetLinkBase) ? "" : resetLinkBase.trim().replaceAll("/+$", "");
        return base + "/reset?token=" + token;
    }

    /** Envía el email de reset vía la Edge Function de Supabase (si está configurada). */
    /** Qué pasó con el correo de reset. Antes se tiraba: el KAM y el log creían que salía. */
    public enum CorreoDeReset { ENVIADO, SIN_PROVEEDOR, FALLO }

    /**
     * Manda el correo de reset y DICE qué pasó. Sigue siendo best-effort (un
     * fallo no rompe el flujo público), pero ya no es mudo: el código de
     * respuesta del proveedor y la excepción quedan en el log (WARN), y quien
     * llama sabe si salió. Hasta la ola 4 esto descartaba la respuesta y vaciaba
     * la excepción: «Reset de contraseña del POS silencioso».
     */
    CorreoDeReset sendResetEmail(String to, String link) {
        if (isBlank(resetEdgeUrl)) {
            return CorreoDeReset.SIN_PROVEEDOR; // staging usa expose-link
        }
        try {
            String body = "{\"to\":" + jsonStr(to) + ",\"link\":" + jsonStr(link) + "}";
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(resetEdgeUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resetEdgeKey)
                    .timeout(java.time.Duration.ofSeconds(8))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var res = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return CorreoDeReset.ENVIADO;
            }
            log.warn("El proveedor de correo respondio {} al reset de {}: {}", res.statusCode(), to,
                    res.body() == null ? "" : res.body().lines().findFirst().orElse(""));
            return CorreoDeReset.FALLO;
        } catch (Exception e) {
            log.warn("No se pudo mandar el correo de reset a {}: {} {}", to,
                    e.getClass().getSimpleName(), e.getMessage());
            return CorreoDeReset.FALLO;
        }
    }

    /** Los usuarios del negocio por orden de creación, para la vista de cuenta del KAM. */
    @Transactional(readOnly = true)
    public List<AuthRepository.AdministradorDeLaCuenta> administradores(String tenantId) {
        repo.fijarNegocioEnLaTransaccion(tenantId); // RLS de `users` (V39): sin esto, vacío
        return repo.listAdministradores(tenantId);
    }

    /** Lo que el KAM recibe al pedir un reset para un administrador (ola 4). */
    public record EnvioDeReset(boolean enviado, String enlace, Instant expira, String motivo) {}

    /**
     * El KAM restablece la clave de un administrador de un negocio, con el mismo
     * mecanismo que «olvidé mi clave»: token de un solo uso, con vencimiento,
     * por correo. Sin clave temporal: una clave que viaja por chat es una clave
     * que alguien más ve.
     *
     * <p>Devuelve la verdad: si el correo salió, si no hay proveedor (staging;
     * entonces el enlace viene en la respuesta para que el KAM lo entregue) o si
     * el proveedor falló (sin enlace: se reintenta cuando el correo funcione).
     * El correo tiene que ser de un administrador DE ESE negocio; si no, 404 con
     * un texto que no distingue «no existe» de «es de otro negocio».
     *
     * <p>Deja huella: quién (KAM), a quién, en qué negocio y qué pasó.
     */
    @Transactional
    public EnvioDeReset restablecerPorElKam(String tenantId, String email, String kam) {
        if (isBlank(tenantId) || isBlank(email)) {
            throw new AuthException(400, "Negocio y correo son requeridos");
        }
        var user = repo.buscarUsuarioParaLogin(email.trim())
                .filter(u -> tenantId.equals(u.tenantId()))
                .filter(u -> "admin".equalsIgnoreCase(u.rol()))
                .orElseThrow(() -> new AuthException(404,
                        "No hay un administrador con ese correo en este negocio."));
        String token = randomToken();
        Instant expires = Instant.now().plus(resetTtlMinutes, ChronoUnit.MINUTES);
        repo.fijarNegocioEnLaTransaccion(user.tenantId());
        repo.insertReset(sha256(token), user.email(), user.tenantId(), expires);
        String link = buildResetLink(token);
        CorreoDeReset correo = sendResetEmail(user.email(), link);
        log.info("El KAM {} pidio restablecer la clave de {} en el negocio {}: correo {}",
                kam, user.email(), user.tenantId(), correo);
        return switch (correo) {
            case ENVIADO -> new EnvioDeReset(true, resetExposeLink ? link : null, expires,
                    "Correo enviado a " + user.email() + ".");
            case SIN_PROVEEDOR -> new EnvioDeReset(false, resetExposeLink ? link : null, expires,
                    resetExposeLink
                            ? "Sin proveedor de correo en este entorno: entrega el enlace tú."
                            : "Sin proveedor de correo en este entorno y sin permiso para exponer el enlace.");
            case FALLO -> new EnvioDeReset(false, null, expires,
                    "El proveedor de correo fallo; queda en el registro. Vuelve a intentarlo.");
        };
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String issueToken(String tenantId, String subject, String role, List<String> modules) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("tenant_id", tenantId)
                .claim("role", role)
                .claim("modules", modules)
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Deriva un slug único desde el nombre del negocio (colisión → sufijo -2, -3…).
     *
     * <p>Comparte la limpieza con {@link AltaDeNegocioService#slugDe} para que el
     * alta desde el KAM y el registro directo produzcan el MISMO identificador.
     * Antes acá se perdían los acentos: "Pizzería" quedaba como {@code pizzer-a}.
     */
    private String uniqueSlug(String businessName) {
        String base = AltaDeNegocioService.slugDe(businessName.trim());
        if (base.isBlank()) {
            base = "negocio";
        }
        if (!repo.tenantExists(base)) {
            return base;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = base + "-" + i;
            if (!repo.tenantExists(candidate)) {
                return candidate;
            }
        }
        throw new AuthException(409, "No se pudo generar un identificador para el negocio");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
