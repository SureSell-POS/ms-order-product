package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F5.3f por la API real: la venta N.º 26 de staging salió por $0 a crédito y dejó un DEBIT de 0
 * (pedido 3 de ponyferrelectrico: la cerradura con precio base 0, el anticorrosivo ajustado a 0 por SIN_EXISTENCIA).
 * Tomar, ajustar o confirmar una línea con cantidad y precio 0 → 400 SIN_PRECIO con el producto; despachar lo que suma
 * $0 → 409 VENTA_EN_CERO sin venta ni deuda. Una factura de $0 en cartera no se crea nunca.
 *
 * <p>F5.3g: la cerradura era un producto precargado INACTIVO. Tomar, ajustar o confirmar una línea con cantidad de un
 * producto inactivo → 400 PRODUCTO_INACTIVO; el despacho no lo vuelve a comprobar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class PrecioCeroTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-precio-cero";
    static final String ADMIN = "admin@qa-precio-cero.invalid";
    static final String CAJA = "caja@qa-precio-cero.invalid";
    static final String ANA = "ana@qa-precio-cero.invalid";
    static final String TIENDA = "900";
    static final String CERRADURA = "qa-cero-cerradura";
    static final String ANTICORROSIVO = "qa-cero-anticorrosivo";
    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

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
        r.add("security.jwt.secret", () -> SECRET);
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate dueno;

    private static String bearer(String email, String rol) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", rol)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String tabla : new String[] {"pedidos.entregas", "pedidos.pedidos_eventos_lineas", "pedidos.pedidos_eventos", "pedidos.pedidos_lineas",
                "pedidos.pedidos", "pedidos.contadores_de_pedidos", "public.inventario_intenciones", "cartera_aplicaciones",
                "debt_transactions", "order_delivery_tracking", "order_item", "orders", "tenant_order_counters", "clientes_eventos",
                "accounts_receivable", "clientes", "menu_products", "sites", "users"}) {
            dueno.update("DELETE FROM " + tabla + " WHERE tenant_id = ?", T);
        }
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Precio cero', 'pro') ON CONFLICT (id) DO NOTHING", T);
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        long ana = 0;
        for (String[] u : new String[][] {{ADMIN, "admin"}, {CAJA, "cajero"}, {ANA, "vendedor"}}) {
            long id = dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, ?, ?) RETURNING id",
                    Long.class, u[0], T, u[1], u[1]);
            if (u[1].equals("vendedor")) {
                ana = id;
            }
        }
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                + "(?, ?, 'Cerradura de sobreponer', 30000, true), (?, ?, 'Anticorrosivo', 18000, true)", CERRADURA, T, ANTICORROSIVO, T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES (?, ?, 'Tienda', ?, 8, 's')",
                T, TIENDA, ana);
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions tomar(String clave) throws Exception {
        return mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ANA, "vendedor")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"origen\":\"vendedor\",\"lineas\":[{\"productoId\":\"" + CERRADURA
                        + "\",\"cantidad\":2},{\"productoId\":\"" + ANTICORROSIVO + "\",\"cantidad\":3}],\"ocurridoEn\":\""
                        + OffsetDateTime.now(BOGOTA).minusHours(2).withNano(0) + "\",\"idempotencyKey\":\"" + clave + "\"}"));
    }

    private ResultActions accion(String quien, String rol, UUID id, String que, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos/" + id + "/" + que).header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private String estado(UUID id) throws Exception {
        return dueno.queryForObject("SELECT tipo FROM pedidos.pedidos_eventos WHERE tenant_id = ? AND pedido_id = ? ORDER BY secuencia DESC LIMIT 1",
                String.class, T, id);
    }

    private int contar(String sql) {
        return dueno.queryForObject(sql, Integer.class, T);
    }

    /** Un pedido de antes del arreglo: tomado con precio y dejado en 0 en la base, como el pedido 3 de staging. */
    private UUID pedidoConLaCerraduraEnCero(String clave) throws Exception {
        UUID id = UUID.fromString(leer(tomar(clave).andExpect(status().isCreated())).get("id").asText());
        dueno.update("UPDATE pedidos.pedidos_lineas SET precio_visto = 0, precio_origen = 'BASE' WHERE tenant_id = ? AND pedido_id = ? AND producto_id = ?",
                T, id, CERRADURA);
        return id;
    }

    private String linea(UUID id, String producto) {
        return dueno.queryForObject("SELECT id::text FROM pedidos.pedidos_lineas WHERE tenant_id = ? AND pedido_id = ? AND producto_id = ?",
                String.class, T, id, producto);
    }

    @Test
    @DisplayName("🔴 F5.3f: tomar un pedido con un producto de precio base 0 → 400 SIN_PRECIO con el producto, y no nace el pedido")
    void tomarConPrecioBaseCero() throws Exception {
        dueno.update("UPDATE menu_products SET price = 0 WHERE tenant_id = ? AND id_product = ?", T, CERRADURA);
        tomar("cero-tomar")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SIN_PRECIO"))
                .andExpect(jsonPath("$.campo").value("lineas"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        assertThat(contar("SELECT count(*) FROM pedidos.pedidos WHERE tenant_id = ?")).isZero();
        assertThat(contar("SELECT count(*) FROM pedidos.pedidos_eventos WHERE tenant_id = ?")).isZero();
    }

    @Test
    @DisplayName("🔴 F5.3f: corregir un precio a 0 con ERROR_DE_PRECIO → 400 SIN_PRECIO y el precio sigue como estaba")
    void ajustarPrecioACero() throws Exception {
        UUID id = UUID.fromString(leer(tomar("cero-ajustar").andExpect(status().isCreated())).get("id").asText());
        accion(ADMIN, "admin", id, "ajustar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, CERRADURA) + "\",\"cantidad\":2,\"precio\":0}],"
                        + "\"motivo\":\"ERROR_DE_PRECIO\",\"idempotencyKey\":\"cero-ajustar-a\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SIN_PRECIO"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        assertThat(estado(id)).isEqualTo("ENVIADO");
        assertThat(dueno.queryForObject("SELECT COALESCE(precio_confirmado, precio_visto) FROM pedidos.pedidos_lineas WHERE id = ?::uuid",
                java.math.BigDecimal.class, linea(id, CERRADURA))).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("🔴 F5.3f: confirmar un pedido que ya tenía una línea en 0 → 400 SIN_PRECIO; corregido el precio, confirma")
    void confirmarConUnaLineaEnCero() throws Exception {
        UUID id = pedidoConLaCerraduraEnCero("cero-confirmar");
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"cero-confirmar-c\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SIN_PRECIO"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        assertThat(estado(id)).isEqualTo("ENVIADO");

        accion(ADMIN, "admin", id, "ajustar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, CERRADURA) + "\",\"cantidad\":2,\"precio\":28000}],"
                + "\"motivo\":\"ERROR_DE_PRECIO\",\"idempotencyKey\":\"cero-confirmar-precio\"}").andExpect(status().isOk());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"cero-confirmar-c2\"}").andExpect(status().isOk());
        assertThat(estado(id)).isEqualTo("CONFIRMADO");
    }

    @Test
    @DisplayName("control: una línea en 0 por SIN_EXISTENCIA (cantidad 0) no cuenta: el pedido se confirma y se despacha")
    void cantidadCeroNoEsPrecioCero() throws Exception {
        UUID id = UUID.fromString(leer(tomar("cero-control").andExpect(status().isCreated())).get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, ANTICORROSIVO) + "\",\"cantidad\":0}],"
                + "\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"cero-control-c\"}").andExpect(status().isOk());
        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"cero-control-d\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venta.total").value(60000));
    }

    @Test
    @DisplayName("🔴 F5.3f: el pedido 3 de staging (cerradura en 0 y anticorrosivo a 0 por SIN_EXISTENCIA) al despachar → 409 VENTA_EN_CERO, sin venta ni DEBIT")
    void despacharLoQueSumaCero() throws Exception {
        UUID id = UUID.fromString(leer(tomar("cero-despachar").andExpect(status().isCreated())).get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, ANTICORROSIVO) + "\",\"cantidad\":0}],"
                + "\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"cero-despachar-c\"}").andExpect(status().isOk());
        // Como el pedido 3: la cerradura quedó en 0 antes de que existiera la guarda.
        dueno.update("UPDATE pedidos.pedidos_lineas SET precio_visto = 0, precio_confirmado = 0, precio_origen = 'BASE' "
                + "WHERE tenant_id = ? AND pedido_id = ? AND producto_id = ?", T, id, CERRADURA);

        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"cero-despachar-d\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VENTA_EN_CERO"));
        assertThat(estado(id)).isEqualTo("CONFIRMADO");
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ?")).isZero();
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ?")).isZero();
    }

    @Test
    @DisplayName("🔴 F5.3f: despachar con una línea en 0 y otra con precio → 400 SIN_PRECIO, sin venta ni DEBIT")
    void despacharConUnaLineaEnCero() throws Exception {
        UUID id = UUID.fromString(leer(tomar("cero-mixto").andExpect(status().isCreated())).get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"cero-mixto-c\"}").andExpect(status().isOk());
        dueno.update("UPDATE pedidos.pedidos_lineas SET precio_visto = 0, precio_confirmado = 0 WHERE tenant_id = ? AND pedido_id = ? AND producto_id = ?",
                T, id, CERRADURA);

        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"cero-mixto-d\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SIN_PRECIO"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        assertThat(estado(id)).isEqualTo("CONFIRMADO");
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ?")).isZero();
        assertThat(contar("SELECT count(*) FROM debt_transactions WHERE tenant_id = ?")).isZero();
    }

    // ---------------------------------------------------------------- F5.3g: producto inactivo

    private void desactivar(String producto) {
        dueno.update("UPDATE menu_products SET active = false WHERE tenant_id = ? AND id_product = ?", T, producto);
    }

    @Test
    @DisplayName("🔴 F5.3g: tomar un pedido con un producto INACTIVO con precio → 400 PRODUCTO_INACTIVO con el producto, y no nace el pedido")
    void tomarConProductoInactivo() throws Exception {
        desactivar(CERRADURA);
        tomar("inactivo-tomar")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PRODUCTO_INACTIVO"))
                .andExpect(jsonPath("$.campo").value("lineas"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        assertThat(contar("SELECT count(*) FROM pedidos.pedidos WHERE tenant_id = ?")).isZero();
    }

    @Test
    @DisplayName("control F5.3g: con los dos productos activos el pedido nace")
    void tomarConProductosActivos() throws Exception {
        tomar("activo-tomar").andExpect(status().isCreated());
        assertThat(contar("SELECT count(*) FROM pedidos.pedidos WHERE tenant_id = ?")).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F5.3g: desactivado después de tomarlo, confirmar o ajustar su cantidad → 400; dejar esa línea en 0 sí confirma")
    void confirmarYAjustarConProductoInactivo() throws Exception {
        UUID id = UUID.fromString(leer(tomar("inactivo-confirmar").andExpect(status().isCreated())).get("id").asText());
        desactivar(CERRADURA);
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"inactivo-confirmar-c\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PRODUCTO_INACTIVO"))
                .andExpect(jsonPath("$.productoId").value(CERRADURA));
        accion(CAJA, "cajero", id, "ajustar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, CERRADURA) + "\",\"cantidad\":1}],"
                        + "\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"inactivo-ajustar\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PRODUCTO_INACTIVO"));
        assertThat(estado(id)).isEqualTo("ENVIADO");

        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + linea(id, CERRADURA) + "\",\"cantidad\":0}],"
                + "\"motivo\":\"PRODUCTO_DESCONTINUADO\",\"idempotencyKey\":\"inactivo-confirmar-c2\"}").andExpect(status().isOk());
        assertThat(estado(id)).isEqualTo("CONFIRMADO");
    }

    @Test
    @DisplayName("F5.3g: desactivado después de confirmar, el despacho NO lo vuelve a comprobar: la mercancía ya está comprometida")
    void despacharConProductoDesactivadoDespuesDeConfirmar() throws Exception {
        UUID id = UUID.fromString(leer(tomar("inactivo-despachar").andExpect(status().isCreated())).get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"inactivo-despachar-c\"}").andExpect(status().isOk());
        desactivar(CERRADURA);
        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"inactivo-despachar-d\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venta.total").value(114000));
    }

    @Test
    @DisplayName("🔒 F5.3g: la venta de CAJA con un producto inactivo SIGUE entrando (201). No añadir aquí la guarda del pedido")
    void laCajaVendeUnProductoInactivo() throws Exception {
        // Decisión de ECM (2026-09-15), fijada a propósito. Una venta de caja YA OCURRIÓ: la mercancía salió y puede
        // llegar horas después por el outbox, con el producto desactivado entre tanto. Rechazarla perdería una venta real
        // (el mismo criterio de F4.11, y el de lo que tumbó a los meseros el 03/09). El POS ya no ofrece inactivos en su
        // búsqueda. El pedido es otra cosa: su mercancía todavía no salió, y por eso él sí da PRODUCTO_INACTIVO.
        desactivar(ANTICORROSIVO);
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pagerColor\":\"MESA\",\"pagerNumber\":\"1\",\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\""
                                + ANTICORROSIVO + "\",\"quantity\":1,\"unitPrice\":18000}],\"idempotencyKey\":\"caja-inactivo\"}"))
                .andExpect(status().isCreated());
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ?")).isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT active FROM menu_products WHERE tenant_id = ? AND id_product = ?", Boolean.class, T, ANTICORROSIVO))
                .as("control: el producto seguía inactivo").isFalse();
    }
}
