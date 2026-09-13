package com.suresell.orders.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 🔴 La venta que sube por el outbox tiene que quedar LEGIBLE para el panel.
 *
 * <p>El historial de ventas del panel lo sirve {@code ms-core-app}, y el core
 * une la línea con su orden por {@code order_item.order_id → orders.id_order}
 * ({@code OrderItem.java:23}, {@code @JoinColumn(name = "order_id")}). En -mt la
 * relación canónica es la otra, el UUID
 * ({@code V1__multitenant_baseline.sql:45}), y por eso este sincronizador
 * escribía <b>solo</b> {@code order_uuid_id}.
 *
 * <p>Consecuencia: toda venta que llegara a la nube por este camino —la caja
 * local que sincroniza— quedaba con {@code order_id} nulo, y el core no veía
 * NINGUNA línea. La orden se listaba con su total y al abrir el detalle no
 * había artículos. Silencioso: ni error, ni log, ni fila de menos.
 *
 * <p>Medido el 2026-09-13 en staging: hoy no hay ninguna fila así
 * ({@code count(*) FILTER (WHERE order_id IS NULL) = 0} sobre 134 líneas),
 * porque las ventas de esos negocios entraron por el camino JPA, que sí lo
 * asigna ({@code OrderHandler.java:1186}). El agujero estaba abierto y sin
 * pisar; esta prueba lo cierra antes de que alguien lo pise.
 *
 * <p>La prueba no mira la columna por mirarla: hace la <b>misma consulta que
 * hace el core</b> y exige que encuentre las líneas.
 */
@Testcontainers
class SincronizacionEscribeOrderIdTest {

    static final String NEGOCIO = "nube-order-id";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private PostgresOrderCloudSyncAdapter adaptador;

    @BeforeAll
    static void migrar() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void preparar() {
        var ds = new SingleConnectionDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        adaptador = new PostgresOrderCloudSyncAdapter(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), new ObjectMapper());
        // El negocio de la sesión: de aquí sale el `tenant_id` por defecto de
        // `orders` y `order_item` (V32), que este sincronizador tampoco escribe.
        jdbc.execute("SET app.tenant_id = '" + NEGOCIO + "'");
        jdbc.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Nube', 'pro') ON CONFLICT (id) DO NOTHING",
                NEGOCIO);
    }

    @Test
    @DisplayName("🔴 una venta sincronizada desde la caja local deja las líneas visibles para el core (order_id, no solo el UUID)")
    void laVentaSincronizadaDejaLasLineasVisiblesParaElCore() {
        UUID ordenUuid = UUID.randomUUID();
        String payload = """
                {"eventType":"ORDER_CREATED",
                 "order":{"uuidId":"%s","createdAt":"2026-09-13T10:00:00",
                          "subtotal":9000,"total":9000,"status":"pagado",
                          "paymentMethod":"CASH","isPrinted":false,
                          "items":[
                            {"uuidId":"%s","productId":"P-uno","quantity":2,
                             "unitPrice":2500,"totalPrice":5000},
                            {"uuidId":"%s","productId":"P-dos","quantity":1,
                             "unitPrice":4000,"totalPrice":4000}]},
                 "tracking":{"delivered":false,"pagerReturned":false,
                             "preparationDurationSeconds":0}}
                """.formatted(ordenUuid, UUID.randomUUID(), UUID.randomUUID());

        adaptador.syncOrderCreatedPayload(payload);

        // Las dos líneas están, por el UUID. Esto ya funcionaba.
        Integer porUuid = jdbc.queryForObject(
                "SELECT count(*) FROM order_item WHERE order_uuid_id = ?", Integer.class, ordenUuid);
        assertThat(porUuid).as("las líneas se guardaron").isEqualTo(2);

        // 🔴 Y están para el CORE, que pregunta por order_id + tenant_id.
        // Antes del arreglo esto daba 0 y el detalle de la venta salía vacío.
        Integer comoLoVeElCore = jdbc.queryForObject("""
                SELECT count(*)
                  FROM order_item i
                  JOIN orders o ON o.id_order = i.order_id AND o.tenant_id = i.tenant_id
                 WHERE o.uuid_id = ?
                """, Integer.class, ordenUuid);
        assertThat(comoLoVeElCore)
                .as("el core une por order_id; si es nulo, el detalle de la venta sale sin artículos")
                .isEqualTo(2);

        // Y el número que se escribió es el de ESTA orden, no otro.
        Long idDeLaOrden = jdbc.queryForObject(
                "SELECT id_order FROM orders WHERE uuid_id = ?", Long.class, ordenUuid);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT order_id) FROM order_item WHERE order_uuid_id = ?",
                Integer.class, ordenUuid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT order_id FROM order_item WHERE order_uuid_id = ?",
                Long.class, ordenUuid)).isEqualTo(idDeLaOrden);
    }
}
