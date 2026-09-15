package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderItem;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * El incidente del 2026-09-03, fijado para siempre.
 *
 * <p>Al encender {@code inventario.intenciones.enabled}, la app de meseros no
 * pudo crear <b>ninguna</b> orden y el POS perdió cerca de la mitad. No fue un
 * despliegue: fue que {@code registrado_en} es el {@code now()} de Postgres —el
 * <b>inicio</b> de la transacción— y el registrador ponía en {@code ocurrido_en}
 * el reloj de la JVM, tomado después de guardar la orden. Siempre posterior.
 * {@code ck_int_reloj} hacía lo que tenía que hacer, y la venta caía con él.
 *
 * <p>Los tres primeros casos reproducen el fallo tal cual: una transacción que
 * lleva un rato abierta antes de registrar. Con el código anterior, los tres
 * se ponen rojos.
 */
@Testcontainers
class RegistroDeIntencionDeInventarioTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RegistroDeIntencionDeInventario registro;

    @BeforeAll
    static void migrar() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void preparar() {
        var ds = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        registro = new RegistroDeIntencionDeInventario(jdbc, new ObjectMapper(), true);
        jdbc.execute("SET app.tenant_id = 'shark-burger'");
        jdbc.execute("DELETE FROM public.inventario_intenciones");
    }

    private static Order orden(long id, OffsetDateTime ocurridoEn) {
        Order o = new Order();
        o.setIdOrder(id);
        o.setUuidId(java.util.UUID.nameUUIDFromBytes(("orden-" + id).getBytes()));
        o.setOcurridoEn(ocurridoEn);
        o.setCreatedBy(5L);
        return o;
    }

    private static List<OrderItem> lineas() {
        OrderItem i = new OrderItem();
        i.setProductId("p1");
        i.setQuantity(2);
        return List.of(i);
    }

    /**
     * Como en una venta real: la transacción ya lleva trabajo hecho cuando se
     * registra la intención. Es lo que hacía que el reloj de la JVM fuera
     * siempre posterior al {@code now()} de la transacción.
     */
    private void enUnaTransaccionQueYaLlevaUnRato(Runnable r) {
        tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT now()", Timestamp.class);   // abre la transacción
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            r.run();
        });
    }

    private Map<String, Object> laIntencion() {
        return jdbc.queryForMap("""
                SELECT ocurrido_en, registrado_en, estado,
                       (ocurrido_en <= registrado_en) AS coherente
                  FROM public.inventario_intenciones""");
    }

    // =====================================================================

    @Test
    @DisplayName("🔴 la orden del mesero, sin hora del dispositivo, entra: era la que fallaba el 100 %")
    void sinHoraDelDispositivo() {
        // El camino del mesero crea la orden `sinProcedencia`: ocurridoEn nulo.
        enUnaTransaccionQueYaLlevaUnRato(() -> registro.registrar(orden(901, null), lineas()));

        Map<String, Object> i = laIntencion();
        assertThat(i.get("estado")).isEqualTo("PENDIENTE");
        assertThat(i.get("coherente")).isEqualTo(true);
        // Un solo reloj: el de la base. Sin hora del dispositivo, ocurrió
        // cuando se registró.
        assertThat(i.get("ocurrido_en")).isEqualTo(i.get("registrado_en"));
    }

    @Test
    @DisplayName("🔴 el POS con el reloj unos segundos adelantado entra, recortado a la hora de la base")
    void relojAdelantado() {
        OffsetDateTime dentroDeSeisSegundos = OffsetDateTime.now().plusSeconds(6);
        enUnaTransaccionQueYaLlevaUnRato(() ->
                registro.registrar(orden(902, dentroDeSeisSegundos), lineas()));

        Map<String, Object> i = laIntencion();
        assertThat(i.get("coherente")).isEqualTo(true);
        assertThat(i.get("ocurrido_en")).isEqualTo(i.get("registrado_en"));
    }

    @Test
    @DisplayName("una venta tomada sin cobertura conserva la hora del hecho, no la del registro")
    void horaDelHecho() {
        // Truncado a milisegundos a propósito: en Linux `now()` trae
        // nanosegundos y Postgres guarda microsegundos. En Mac coincidían por
        // casualidad y el test pasaba aquí y fallaba en el CI.
        OffsetDateTime haceDosHoras = OffsetDateTime.now().minusHours(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        enUnaTransaccionQueYaLlevaUnRato(() ->
                registro.registrar(orden(903, haceDosHoras), lineas()));

        Map<String, Object> i = laIntencion();
        Timestamp ocurrido = (Timestamp) i.get("ocurrido_en");
        assertThat(ocurrido.toInstant()).isEqualTo(haceDosHoras.toInstant());
        assertThat(i.get("coherente")).isEqualTo(true);
    }

    @Test
    @DisplayName("🔴 si la intención no se puede escribir, la venta NO cae")
    void laVentaNoCae() {
        // Sin negocio en la sesión, RLS rechaza el INSERT. Con el código
        // anterior eso era un 500 para el mesero.
        jdbc.execute("SET app.tenant_id = ''");
        assertThatCode(() -> tx.executeWithoutResult(s ->
                registro.registrar(orden(904, null), lineas())))
                .doesNotThrowAnyException();
        jdbc.execute("SET app.tenant_id = 'shark-burger'");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.inventario_intenciones", Integer.class)).isZero();
    }

    @Test
    @DisplayName("registrar dos veces la misma venta deja UNA intención y no falla")
    void idempotente() {
        tx.executeWithoutResult(s -> registro.registrar(orden(905, null), lineas()));
        assertThatCode(() -> tx.executeWithoutResult(s ->
                registro.registrar(orden(905, null), lineas())))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.inventario_intenciones", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F5.5a: dos sedes con el mismo número de orden son dos ventas y dos intenciones; la clave es la del uuid")
    void dosSedesMismoNumero() {
        Order sede1 = orden(7, null);
        Order sede2 = orden(7, null);
        sede2.setUuidId(java.util.UUID.randomUUID());
        tx.executeWithoutResult(s -> registro.registrar(sede1, lineas()));
        tx.executeWithoutResult(s -> registro.registrar(sede2, lineas()));
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM public.inventario_intenciones ORDER BY idempotency_key", String.class))
                .containsExactlyInAnyOrder("venta-" + sede1.getUuidId(), "venta-" + sede2.getUuidId());
    }

    @Test
    @DisplayName("con el interruptor apagado no escribe nada")
    void apagado() {
        var apagado = new RegistroDeIntencionDeInventario(jdbc, new ObjectMapper(), false);
        tx.executeWithoutResult(s -> apagado.registrar(orden(906, null), lineas()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.inventario_intenciones", Integer.class)).isZero();
    }
}
