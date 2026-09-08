package com.suresell.orders.multitenant;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a la tabla global `super_admins` y a operaciones cross-tenant que solo el
 * super-admin (KAM) realiza (listar todos los negocios, cambiar plan). F3, Inc.3.
 * SOLO perfil `cloud`. Corre sin tenant en contexto (endpoints /admin/**); las
 * tablas globales tienen política RLS abierta para app_user.
 */
@Repository
@Profile("cloud")
public class SuperAdminRepository {

    private final JdbcTemplate jdbc;

    public SuperAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SuperAdminRow(long id, String email, String passwordHash) {}

    public record TenantListItem(String id, String name, String plan, String status, int users) {}

    public Optional<SuperAdminRow> findByEmail(String email) {
        try {
            SuperAdminRow row = jdbc.queryForObject(
                    "SELECT id, email, password_hash FROM super_admins WHERE lower(email) = lower(?)",
                    (rs, i) -> new SuperAdminRow(rs.getLong("id"), rs.getString("email"),
                            rs.getString("password_hash")),
                    email);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Todos los negocios con su conteo de usuarios (vista del panel del KAM).
     *
     * <p>V39 — el conteo va por {@code contar_usuarios_por_negocio()} y no por
     * una subconsulta a {@code users}. El KAM es cross-tenant por definición y
     * no trae negocio en sesión: con la política cerrada, la subconsulta habría
     * devuelto <b>0 para todos los negocios sin dar ningún error</b>. Un número
     * equivocado en pantalla es peor que una pantalla en blanco, porque nadie
     * abre una incidencia por él.
     *
     * <p>La función devuelve conteos, nunca filas de usuario.
     */
    public List<TenantListItem> listTenants() {
        return jdbc.query(
                "SELECT t.id, t.name, t.plan, t.status, "
                        + "coalesce(c.usuarios, 0) AS users "
                        + "FROM tenants t "
                        + "LEFT JOIN contar_usuarios_por_negocio() c ON c.tenant_id = t.id "
                        + "ORDER BY t.created_at",
                (rs, i) -> new TenantListItem(rs.getString("id"), rs.getString("name"),
                        rs.getString("plan"), rs.getString("status"), rs.getInt("users")));
    }

    /** V48 (ola 3): una cuenta de KAM más. El hash llega ya calculado. */
    public void insert(String email, String passwordHash) {
        jdbc.update("INSERT INTO super_admins (email, password_hash) VALUES (?, ?)", email, passwordHash);
    }

    /** Cambia el plan de un negocio. Devuelve cuántas filas cambió (0 = no existe). */
    public int updateTenantPlan(String tenantId, String plan) {
        return jdbc.update("UPDATE tenants SET plan = ? WHERE id = ?", plan, tenantId);
    }
}
