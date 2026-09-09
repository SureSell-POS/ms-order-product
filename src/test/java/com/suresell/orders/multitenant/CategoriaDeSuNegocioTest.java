package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V51: un producto solo puede apuntar a una categoría de SU negocio. Antes no
 * había ninguna clave foránea en {@code menu_products.category_id}: el
 * aislamiento era por costumbre (segunda revisión manual de Santiago,
 * 2026-09-09).
 */
@Testcontainers
class CategoriaDeSuNegocioTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection app(String tenant) throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "app_pw");
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        }
        return c;
    }

    private void categoria(Connection c, String tenant, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO menu_categories (id_category, tenant_id, name_category) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, tenant);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    private int producto(Connection c, String tenant, String id, String categoria) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, category_id) "
                + "VALUES (?, ?, ?, 1000, true, ?)")) {
            ps.setString(1, id);
            ps.setString(2, tenant);
            ps.setString(3, id);
            ps.setString(4, categoria);
            return ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("🔴 un producto no puede apuntar a la categoría de otro negocio: la base lo rechaza")
    void categoriaAjenaRechazada() throws SQLException {
        try (Connection a = app("t-cat-a")) {
            categoria(a, "t-cat-a", "herramientas-a");
        }
        try (Connection b = app("t-cat-b")) {
            // La categoría existe (para A), pero B no la ve ni puede usarla.
            assertThatThrownBy(() -> producto(b, "t-cat-b", "martillo-b", "herramientas-a"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_menu_products_categoria_del_negocio");
            // Con la suya, entra.
            categoria(b, "t-cat-b", "herramientas-b");
            assertThat(producto(b, "t-cat-b", "martillo-b", "herramientas-b")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("un producto sin categoría sigue valiendo (en producción hay uno)")
    void sinCategoriaSigueValiendo() throws SQLException {
        try (Connection c = app("t-cat-c")) {
            assertThat(producto(c, "t-cat-c", "suelto-c", null)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("el mismo identificador de categoría en dos negocios sigue chocando: la clave primaria es global (deuda, otra ola)")
    void mismoIdEnDosNegociosTodaviaChoca() throws SQLException {
        try (Connection d = app("t-cat-d")) {
            categoria(d, "t-cat-d", "bebidas");
        }
        try (Connection e = app("t-cat-e")) {
            assertThatThrownBy(() -> categoria(e, "t-cat-e", "bebidas"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("menu_categories_pkey");
        }
    }
}
