package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F1.8b: la carrera del incremental del catálogo de lista, con DOS transacciones de verdad (medida primero por
 * DevFront en un Postgres local). La escritura empieza (su {@code now()} queda fijado), el lector sirve, la escritura
 * confirma: su línea nueva tiene {@code vigente_desde} ANTERIOR al {@code servidoEn}. Sin solape, el siguiente
 * incremental con {@code desde = servidoEn} no la trae nunca y la caja se queda con el precio viejo abierto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("cloud")
@Testcontainers
class ListaIncrementalCarreraTest {

    static final String T = "qa-lista-carrera";
    static final String PRODUCTO = "arroz-carrera";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "app_pw");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
        r.add("security.jwt.secret", () -> "clave-de-prueba-multitenant-min-32-bytes!!");
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @Autowired ListasDePrecio listas;
    private JdbcTemplate dueno;
    private UUID lista;

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        // El lector corre como app_user con RLS: la conexión toma el negocio del contexto.
        com.suresell.orders.multitenant.TenantContext.set(T);
        dueno.update("DELETE FROM listas_precio_items WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM listas_precio WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", T);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Carrera', 'pro') ON CONFLICT (id) DO NOTHING", T);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, 'Arroz', 90000, true)", PRODUCTO, T);
        lista = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) VALUES (?, 'dist', 'Dist', 's') RETURNING id",
                UUID.class, T);
        // El precio de siempre, de hace dos horas (fuera de cualquier margen).
        dueno.update("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, vigente_desde) "
                + "VALUES (?, ?, ?, 1, 100000, 's', 'declarado_comerciante', 1, now() - interval '2 hours')", T, lista, PRODUCTO);
    }

    private Connection escritura() throws Exception {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    /** Lo que hace fijarPrecio: cerrar la vigente y abrir la nueva, en UNA transacción. */
    private static void cambiarPrecio(Connection c, UUID lista, int precio) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("UPDATE listas_precio_items SET vigente_hasta = now() WHERE tenant_id = '" + T + "' AND lista_id = '" + lista
                    + "' AND producto_id = '" + PRODUCTO + "' AND cantidad_minima = 1 AND vigente_hasta IS NULL");
            s.execute("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza) "
                    + "VALUES ('" + T + "', '" + lista + "', '" + PRODUCTO + "', 1, " + precio + ", 's', 'declarado_comerciante', 1)");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lineas(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("lineas");
    }

    private static boolean traePrecioAbierto(Map<String, Object> r, int precio) {
        return lineas(r).stream().anyMatch(l -> l.get("vigenteHasta") == null && new BigDecimal(precio).compareTo((BigDecimal) l.get("precio")) == 0);
    }

    @Test
    @DisplayName("🔴 F1.8b: una escritura que empieza antes de servir y confirma después llega en el incremental siguiente")
    void laCarrera() throws Exception {
        Map<String, Object> primera = listas.catalogoDeLista(T, lista, null);
        OffsetDateTime desde = (OffsetDateTime) primera.get("servidoEn");
        Thread.sleep(20);
        try (Connection w = escritura()) {
            cambiarPrecio(w, lista, 105000);                                     // su now() queda fijado aquí
            Thread.sleep(20);
            Map<String, Object> enMedio = listas.catalogoDeLista(T, lista, desde); // el lector sirve: no ve lo no confirmado
            assertThat(traePrecioAbierto(enMedio, 105000)).isFalse();
            desde = (OffsetDateTime) enMedio.get("servidoEn");
            w.commit();                                                          // confirma DESPUÉS de servir
        }
        OffsetDateTime vigenteDesde = dueno.queryForObject("SELECT vigente_desde FROM listas_precio_items WHERE tenant_id = ? AND precio = 105000",
                OffsetDateTime.class, T);
        Map<String, Object> siguiente = listas.catalogoDeLista(T, lista, desde);
        assertThat(traePrecioAbierto(siguiente, 105000))
                .as("vigente_desde %s frente al desde %s: el precio nuevo tiene que llegar", vigenteDesde, desde).isTrue();
        assertThat(lineas(siguiente)).anyMatch(l -> l.get("vigenteHasta") != null && new BigDecimal(100000).compareTo((BigDecimal) l.get("precio")) == 0);
    }

    @Test
    @DisplayName("control sin solape: la escritura confirma antes de servir y el incremental la trae")
    void sinCarrera() throws Exception {
        OffsetDateTime desde = (OffsetDateTime) listas.catalogoDeLista(T, lista, null).get("servidoEn");
        Thread.sleep(20);
        try (Connection w = escritura()) {
            cambiarPrecio(w, lista, 106000);
            w.commit();
        }
        assertThat(traePrecioAbierto(listas.catalogoDeLista(T, lista, desde), 106000)).isTrue();
    }

    @Test
    @DisplayName("🔴 F1.8b: fijarPrecio tiene tope de duración: bloqueada por otra transacción, se corta en el tope y no queda abierta")
    void laEscrituraTieneTope() throws Exception {
        try (Connection bloqueo = escritura(); Statement s = bloqueo.createStatement()) {
            s.execute("SELECT 1 FROM listas_precio_items WHERE tenant_id = '" + T + "' AND vigente_hasta IS NULL FOR UPDATE");
            long inicio = System.nanoTime();
            assertThatThrownBy(() -> listas.fijarPrecio(T, lista, PRODUCTO, 1, new BigDecimal("107000"), null, 1,
                    new ListasDePrecio.Autor("admin@carrera.invalid", null), null))
                    .isInstanceOf(RuntimeException.class);
            long segundos = (System.nanoTime() - inicio) / 1_000_000_000L;
            assertThat(segundos).as("segundos esperando").isLessThan(ListasDePrecio.TOPE_DE_ESCRITURA_SEGUNDOS + 3L);
            assertThat(ListasDePrecio.MARGEN_DEL_INCREMENTAL_SEGUNDOS).as("el margen cubre la escritura más larga")
                    .isGreaterThan(ListasDePrecio.TOPE_DE_ESCRITURA_SEGUNDOS * 3);
            bloqueo.rollback();
        }
        assertThat(dueno.queryForObject("SELECT count(*) FROM listas_precio_items WHERE tenant_id = ? AND precio = 107000", Integer.class, T)).isZero();
    }
}
