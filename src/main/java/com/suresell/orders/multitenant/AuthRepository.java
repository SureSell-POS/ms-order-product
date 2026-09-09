package com.suresell.orders.multitenant;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a las tablas GLOBALES de auth (`tenants`, `users`) vía JdbcTemplate.
 *
 * Deliberadamente NO usa JPA/entidades: estas tablas no llevan tenant_id ni la
 * política RLS por tenant (ver V4__auth.sql y docs/110 §3). El login ocurre sin
 * tenant en contexto, así que se consultan por email/slug directo. SOLO perfil `cloud`.
 */
@Repository
@Profile("cloud")
public class AuthRepository {

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(long id, String email, String passwordHash, String tenantId,
                          String role, String status) {}

    public record TenantRow(String id, String name, String plan, String status,
                            String nit, String address, String phone, String ticketFooter) {}

    /** Proyección de usuario SIN el hash (para listar en el panel de usuarios). */
    public record UserSummary(long id, String email, String role, String status) {}

    /** Override de módulo por tenant: enabled=true regala, false quita. */
    public record ModuleOverride(String module, boolean enabled) {}

    /**
     * Lo mínimo que el login necesita saber de un usuario. Es un tipo distinto de
     * {@link UserRow} a propósito: marca la frontera entre lo que se lee con
     * privilegios y lo que se lee por RLS normal.
     */
    public record UsuarioParaLogin(String email, String tenantId, String passwordHash,
                                   String rol, boolean activo) {}

    /**
     * Busca el usuario del login <b>sin negocio en sesión</b>, vía la función
     * {@code buscar_usuario_para_login} de V39.
     *
     * <p>Es el único camino que puede leer {@code users} sin `app.tenant_id`, y
     * existe porque el login busca al usuario <i>para averiguar cuál es su
     * negocio</i>: exigirle que lo fije antes sería pedirle el dato que va a
     * buscar. Todo lo demás que lee {@code users} —listar, cambiar rol, resolver
     * el autor de una petición— va por RLS normal con {@link #findUserByEmail}.
     */
    public Optional<UsuarioParaLogin> buscarUsuarioParaLogin(String email) {
        List<UsuarioParaLogin> filas = jdbc.query(
                "SELECT email, tenant_id, password_hash, rol, activo "
                        + "FROM buscar_usuario_para_login(?)",
                (rs, i) -> new UsuarioParaLogin(
                        rs.getString("email"), rs.getString("tenant_id"),
                        rs.getString("password_hash"), rs.getString("rol"),
                        rs.getBoolean("activo")),
                email);
        return filas.stream().findFirst();
    }

    /**
     * Fija el negocio para el resto de <b>esta transacción</b>.
     *
     * <p>El tercer parámetro {@code true} de {@code set_config} lo acota a la
     * transacción: al terminar vuelve solo. Y esa acotación obliga a que quien
     * llame esté dentro de una — fuera de transacción, en autocommit, la
     * sentencia es su propia transacción y el valor se descarta antes de la
     * consulta siguiente. Es el defecto que tiene hoy
     * {@code SuperAdminService.getSites}, anotado en NOTAS.md.
     */
    public void fijarNegocioEnLaTransaccion(String tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
    }

    /** Busca el usuario por email (case-insensitive). Requiere negocio en sesión. */
    public Optional<UserRow> findUserByEmail(String email) {
        try {
            UserRow row = jdbc.queryForObject(
                    "SELECT id, email, password_hash, tenant_id, role, status "
                            + "FROM users WHERE lower(email) = lower(?)",
                    (rs, i) -> new UserRow(
                            rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                            rs.getString("tenant_id"), rs.getString("role"), rs.getString("status")),
                    email);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<TenantRow> findTenant(String id) {
        try {
            TenantRow row = jdbc.queryForObject(
                    "SELECT id, name, plan, status, nit, address, phone, ticket_footer "
                            + "FROM tenants WHERE id = ?",
                    (rs, i) -> new TenantRow(rs.getString("id"), rs.getString("name"),
                            rs.getString("plan"), rs.getString("status"),
                            rs.getString("nit"), rs.getString("address"),
                            rs.getString("phone"), rs.getString("ticket_footer")),
                    id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Si el email ya está tomado, <b>en cualquier negocio de la plataforma</b>.
     *
     * <p>Va por la función {@code existe_email} de V39 porque
     * {@code users.email} es UNIQUE GLOBAL (V4:23): la pregunta es forzosamente
     * cross-tenant. Con un {@code count(*)} normal y la política cerrada
     * respondería siempre "libre", el INSERT chocaría contra el índice único y
     * el usuario recibiría un 500 donde hoy recibe un 409 explicado.
     */
    public boolean emailExists(String email) {
        Boolean existe = jdbc.queryForObject(
                "SELECT existe_email(?)", Boolean.class, email);
        return Boolean.TRUE.equals(existe);
    }

    public boolean tenantExists(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public void insertTenant(String id, String name, String plan,
                             String nit, String address, String phone) {
        jdbc.update("INSERT INTO tenants (id, name, plan, nit, address, phone) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, name, plan, nit, address, phone);
    }

    /** Actualiza el perfil del negocio (datos del ticket). Acotado por id de tenant. */
    public void updateBusinessProfile(String tenantId, String name, String nit,
                                      String address, String phone, String ticketFooter) {
        jdbc.update("UPDATE tenants SET name = ?, nit = ?, address = ?, phone = ?, "
                        + "ticket_footer = ? WHERE id = ?",
                name, nit, address, phone, ticketFooter, tenantId);
    }

    public void insertUser(String email, String passwordHash, String tenantId, String role) {
        jdbc.update("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, ?, ?, ?)",
                email, passwordHash, tenantId, role);
    }

    /** Usuarios de un tenant (sin hash), para el panel de gestión (F3, admin). */
    public List<UserSummary> listUsers(String tenantId) {
        return jdbc.query(
                "SELECT id, email, role, status FROM users WHERE tenant_id = ? ORDER BY created_at",
                (rs, i) -> new UserSummary(rs.getLong("id"), rs.getString("email"),
                        rs.getString("role"), rs.getString("status")),
                tenantId);
    }

    /** Un usuario de la cuenta tal como lo ve el KAM (ola 4): con cuándo se creó. */
    public record AdministradorDeLaCuenta(String email, String rol, boolean activo, java.time.Instant creadoEn) {}

    /**
     * Los usuarios del negocio por orden de creación. El primero es el correo
     * principal: el del alta. La política de `users` es permisiva para
     * `app_user` (V4), así que no hace falta negocio en sesión.
     */
    public List<AdministradorDeLaCuenta> listAdministradores(String tenantId) {
        return jdbc.query(
                "SELECT email, role, status, created_at FROM users WHERE tenant_id = ? ORDER BY created_at, id",
                (rs, i) -> new AdministradorDeLaCuenta(rs.getString("email"), rs.getString("role"),
                        "active".equalsIgnoreCase(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId);
    }

    /** Overrides de módulos del tenant (F3, Inc.2). */
    public List<ModuleOverride> getOverrides(String tenantId) {
        return jdbc.query(
                "SELECT module, enabled FROM tenant_modules WHERE tenant_id = ?",
                (rs, i) -> new ModuleOverride(rs.getString("module"), rs.getBoolean("enabled")),
                tenantId);
    }

    /** Fija (o actualiza) un override de módulo para el tenant. */
    public void upsertOverride(String tenantId, String module, boolean enabled) {
        jdbc.update(
                "INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, module) DO UPDATE SET enabled = EXCLUDED.enabled",
                tenantId, module, enabled);
    }

    /** Quita un override (el módulo vuelve a decidirse solo por el plan). */
    public void deleteOverride(String tenantId, String module) {
        jdbc.update("DELETE FROM tenant_modules WHERE tenant_id = ? AND module = ?", tenantId, module);
    }

    // ---------- Reset de contraseña (F3, Inc.5) ----------

    public record ResetRow(String email, String tenantId) {}

    public void insertReset(String tokenHash, String email, String tenantId, java.time.Instant expiresAt) {
        jdbc.update(
                "INSERT INTO password_resets (token_hash, email, tenant_id, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, email, tenantId, java.sql.Timestamp.from(expiresAt));
    }

    /**
     * Resultado de consultar un token de recuperación.
     *
     * <p>{@code email} y {@code tenantId} vienen <b>en nulo salvo que el estado
     * sea {@code valido}</b>. No es una precaución decorativa: son los dos
     * únicos datos personales de la fila, y para decidir que un token está
     * vencido no hacen ninguna falta.
     */
    public record ConsultaDeReset(EstadoDelToken estado, String email, String tenantId) {}

    /**
     * Busca un token de recuperación y dice <b>por qué</b> no sirve, si no sirve.
     *
     * <p>Sustituye a {@code findValidReset}, que devolvía un {@code Optional}
     * vacío para cuatro situaciones distintas —no existe, caducado, ya usado— y
     * hacía imposible diagnosticar un reporte de "el enlace no funciona".
     *
     * <p><b>Precedencia deliberada:</b> un token usado Y caducado se reporta como
     * {@code usado}. Es lo que primero hay que saber: significa que el flujo sí
     * llegó al final alguna vez, y eso cambia por dónde se busca el problema.
     */
    public ConsultaDeReset buscarReset(String tokenHash) {
        // La lógica de estados vive en la función `buscar_token_de_reset` (V39) y
        // no aquí: es el único camino que puede leer `password_resets` sin
        // negocio en sesión, y una petición de /auth/reset-password no trae
        // ninguno — llega con un token y nada más.
        List<ConsultaDeReset> filas = jdbc.query(
                "SELECT estado, email, tenant_id FROM buscar_token_de_reset(?)",
                (rs, i) -> new ConsultaDeReset(
                        EstadoDelToken.valueOf(rs.getString("estado")),
                        rs.getString("email"), rs.getString("tenant_id")),
                tokenHash);
        return filas.stream().findFirst()
                .orElse(new ConsultaDeReset(EstadoDelToken.no_existe, null, null));
    }

    /** @return cuántas filas marcó (0 = el token no existe o ya estaba usado). */
    public int markResetUsed(String tokenHash) {
        return jdbc.update(
                "UPDATE password_resets SET used = true WHERE token_hash = ? AND used = false",
                tokenHash);
    }

    /**
     * Actualiza el hash de un usuario acotando por email+tenant (defensa en
     * profundidad: aunque la política RLS de app_user es abierta, nunca se toca la
     * fila de otro negocio). Devuelve cuántas filas cambió (0 = no coincide).
     */
    public int updatePasswordHash(String email, String tenantId, String newHash) {
        return jdbc.update(
                "UPDATE users SET password_hash = ? "
                        + "WHERE lower(email) = lower(?) AND tenant_id = ?",
                newHash, email, tenantId);
    }
}
