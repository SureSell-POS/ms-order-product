package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Plan de mayoristas F5.5 y F5.6 por la API real, con {@code app_user} y la intención de
 * inventario encendida: despachar un pedido crea su venta en la misma transacción.
 *
 * <p>Hecho cuando (plan): despachar 18 de 20 crea una venta de 18 con DEBIT y vencimiento;
 * la intención de inventario nace; el consecutivo por sede avanza; {@code ck_int_reloj} no
 * rechaza. Y las decisiones de ECM: plazo del pedido (no el del cliente), contraentrega =
 * CONTADO con medio CREDITO, sin plazo = CREDITO sin fecha, la insolvencia bloquea el
 * despacho, y el segundo despacho o cancelar tras una entrega fallida = 409 REVERSA_PENDIENTE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class DespachoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-despacho";
    static final String ADMIN = "admin@qa-despacho.invalid";
    static final String CAJA = "caja@qa-despacho.invalid";
    static final String ANA = "ana@qa-despacho.invalid";
    static final String TIENDA = "900";
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
        r.add("inventario.intenciones.enabled", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate dueno;
    private long ana;
    private UUID lista;
    private long sedePrincipal;
    private long sedeBodega;
    private final LocalDate hoy = LocalDate.now(BOGOTA);

    private static String bearer(String email, String rol) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", rol)
                .claim("modules", List.of("ventas", "mayorista", "cartera", "cocina", "meseros"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String tabla : new String[] {"pedidos.entregas", "pedidos.pedidos_eventos_lineas", "pedidos.pedidos_eventos", "pedidos.pedidos_lineas",
                "pedidos.pedidos", "pedidos.contadores_de_pedidos", "public.inventario_intenciones", "cartera_aplicaciones",
                "debt_transactions", "order_delivery_tracking", "order_item", "orders", "tenant_order_counters", "clientes_eventos",
                "accounts_receivable", "clientes", "listas_precio_items", "listas_precio", "menu_products", "sites", "users"}) {
            dueno.update("DELETE FROM " + tabla + " WHERE tenant_id = ?", T);
        }
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Despacho', 'pro') ON CONFLICT (id) DO NOTHING", T);
        sedePrincipal = dueno.queryForObject("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                + "VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true) RETURNING id", Long.class, T);
        sedeBodega = dueno.queryForObject("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                + "VALUES (?, 'Bodega', 'BODEGA', 'DIRECTO', false) RETURNING id", Long.class, T);
        usuario(ADMIN, "admin");
        usuario(CAJA, "cajero");
        ana = usuario(ANA, "vendedor");
        for (int i = 1; i <= 20; i++) {
            dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, ?, ?, true)",
                    producto(i), T, "Producto " + i, 1000 + i);
        }
        lista = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) VALUES (?, 'tiendas', 'Tiendas', 's') RETURNING id",
                UUID.class, T);
        dueno.update("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, vigente_desde) "
                + "VALUES (?, ?, ?, 1, 800, 's', 'declarado_comerciante', 1, now() - interval '30 days')", T, lista, producto(1));
        cliente(TIENDA, 8);
    }

    private long usuario(String email, String rol) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, ?, ?) RETURNING id",
                Long.class, email, T, rol, email.substring(0, email.indexOf('@')));
    }

    private void cliente(String documento, Integer plazo) {
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, lista_precio_id, creado_por) VALUES (?, ?, 'Tienda', ?, ?, ?, 's')",
                T, documento, ana, plazo, lista);
    }

    private static String producto(int i) {
        return String.format("qa-desp-%02d", i);
    }

    private static String lineas(int cuantas, int cantidad) {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= cuantas; i++) {
            l.add("{\"productoId\":\"" + producto(i) + "\",\"cantidad\":" + cantidad + "}");
        }
        return "[" + String.join(",", l) + "]";
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode tomar(String documento, String clave, int cuantas) throws Exception {
        return leer(mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ANA, "vendedor")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + documento + "\",\"origen\":\"vendedor\",\"lineas\":" + lineas(cuantas, 10)
                                + ",\"ocurridoEn\":\"" + OffsetDateTime.now(BOGOTA).minusHours(2).withNano(0) + "\",\"idempotencyKey\":\"" + clave + "\"}"))
                .andExpect(status().isCreated()));
    }

    private ResultActions accion(String quien, String rol, UUID id, String que, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos/" + id + "/" + que).header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private UUID tomarYConfirmar(String documento, String clave, int cuantas) throws Exception {
        UUID id = UUID.fromString(tomar(documento, clave, cuantas).get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"" + clave + "-c\"}").andExpect(status().isOk());
        return id;
    }

    private Map<String, Object> laVenta(UUID pedido) {
        return dueno.queryForMap("SELECT * FROM orders WHERE tenant_id = ? AND pedido_id = ?", T, pedido);
    }

    @Test
    @DisplayName("🔴 F5.5: despachar 18 de 20 crea una venta de 18 al precio congelado, con DEBIT al plazo del pedido, intención, fuera de cocina y del encadenado")
    void despacharCreaLaVenta() throws Exception {
        JsonNode tomado = tomar(TIENDA, "d-1", 20);
        UUID id = UUID.fromString(tomado.get("id").asText());
        String l19 = tomado.get("lineas").get(18).get("lineaId").asText();
        String l20 = tomado.get("lineas").get(19).get("lineaId").asText();
        String l3 = tomado.get("lineas").get(2).get("lineaId").asText();
        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + l19 + "\",\"cantidad\":0},{\"lineaId\":\"" + l20
                + "\",\"cantidad\":0}],\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"d-1-c\"}").andExpect(status().isOk());

        // Entre la confirmación y el despacho: la lista sube y el cliente pasa a 30 días.
        // Un precio no se edita (fn_lpi_solo_se_cierra): se cierra la línea vigente y se abre otra.
        dueno.update("UPDATE listas_precio_items SET vigente_hasta = now() WHERE tenant_id = ? AND producto_id = ? AND vigente_hasta IS NULL", T, producto(1));
        dueno.update("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, vigente_desde) "
                + "VALUES (?, ?, ?, 1, 950, 's', 'declarado_comerciante', 1, now())", T, lista, producto(1));
        dueno.update("UPDATE clientes SET plazo_dias = 30 WHERE tenant_id = ? AND documento = ?", T, TIENDA);

        JsonNode despachado = leer(accion(CAJA, "cajero", id, "despachar", "{\"lineas\":[{\"lineaId\":\"" + l3 + "\",\"cantidad\":7}],"
                        + "\"idempotencyKey\":\"d-1-despacho\",\"ocurridoEn\":\"" + OffsetDateTime.now(BOGOTA).plusMinutes(2).withNano(0) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DESPACHADO"))
                .andExpect(jsonPath("$.venta.uuid").isString()));

        Map<String, Object> venta = laVenta(id);
        UUID uuid = (UUID) venta.get("uuid_id");
        assertThat(venta).containsEntry("origen", "pedido").containsEntry("payment_method", "CREDITO")
                .containsEntry("condicion_pago", "CREDITO").containsEntry("cliente_documento", TIENDA);
        assertThat(((Number) venta.get("plazo_dias")).intValue()).as("el plazo del pedido, no el del cliente de hoy").isEqualTo(8);
        assertThat(((Number) venta.get("vendedor_id")).longValue()).isEqualTo(ana);
        assertThat(venta.get("terminal_id")).as("F5.6: sin terminal").isNull();
        assertThat(venta.get("cadena_origen")).as("F5.6: fuera del encadenado").isNull();
        assertThat(venta.get("hash_propio")).isNull();
        assertThat(((Number) venta.get("site_id")).longValue()).isEqualTo(sedePrincipal);

        List<Map<String, Object>> items = dueno.queryForList("SELECT product_id, quantity, unit_price FROM order_item WHERE order_uuid_id = ? ORDER BY product_id", uuid);
        assertThat(items).hasSize(18);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            total = total.add(((BigDecimal) it.get("unit_price")).multiply(BigDecimal.valueOf(((Number) it.get("quantity")).longValue())));
        }
        assertThat(items.get(0)).containsEntry("product_id", producto(1));
        assertThat((BigDecimal) items.get(0).get("unit_price")).as("Q2: congelado, no la lista de hoy").isEqualByComparingTo("800");
        assertThat(((Number) items.get(2).get("quantity")).intValue()).as("lo despachado, no lo confirmado").isEqualTo(7);
        assertThat((BigDecimal) venta.get("total")).isEqualByComparingTo(total);
        assertThat(despachado.get("venta").get("total").decimalValue()).isEqualByComparingTo(total);

        Map<String, Object> debito = dueno.queryForMap("SELECT amount, vence_el, type FROM debt_transactions WHERE order_uuid = ?", uuid);
        assertThat((BigDecimal) debito.get("amount")).isEqualByComparingTo(total);
        assertThat(debito.get("vence_el").toString()).as("hoy + 8 del pedido").isEqualTo(hoy.plusDays(8).toString());

        Map<String, Object> intencion = dueno.queryForMap("SELECT idempotency_key, ocurrido_en <= registrado_en AS coherente FROM public.inventario_intenciones WHERE orden_uuid = ?", uuid);
        assertThat(intencion).containsEntry("idempotency_key", "venta-" + uuid).containsEntry("coherente", true);

        Map<String, Object> cocina = dueno.queryForMap("SELECT t.delivered, o.is_printed FROM order_delivery_tracking t JOIN orders o ON o.uuid_id = t.order_id_uuid WHERE t.order_id_uuid = ?", uuid);
        assertThat(cocina).containsEntry("delivered", true).containsEntry("is_printed", true);
        mockMvc.perform(get("/api/kitchen/orders/active").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].uuidId", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(uuid.toString()))));

        // El reintento: mismo pedido, misma venta, nada nuevo.
        accion(CAJA, "cajero", id, "despachar", "{\"lineas\":[{\"lineaId\":\"" + l3 + "\",\"cantidad\":7}],\"idempotencyKey\":\"d-1-despacho\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.venta.uuid").value(uuid.toString()));
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND pedido_id = ?", Integer.class, T, id)).isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.pedidos_eventos WHERE pedido_id = ? AND tipo = 'DESPACHADO'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F5.5: contraentrega = CONTADO con medio CREDITO y vence hoy; sin plazo = CREDITO sin fecha")
    void contraentregaYSinPlazo() throws Exception {
        cliente("901", 0);
        cliente("902", null);
        UUID contraentrega = tomarYConfirmar("901", "d-0", 2);
        UUID sinPlazo = tomarYConfirmar("902", "d-n", 2);
        accion(ADMIN, "admin", contraentrega, "despachar", "{\"idempotencyKey\":\"d-0-desp\"}").andExpect(status().isOk());
        accion(ADMIN, "admin", sinPlazo, "despachar", "{\"idempotencyKey\":\"d-n-desp\"}").andExpect(status().isOk());

        Map<String, Object> v0 = laVenta(contraentrega);
        assertThat(v0).containsEntry("payment_method", "CREDITO").containsEntry("condicion_pago", "CONTADO").containsEntry("excede_cupo", false);
        assertThat(dueno.queryForObject("SELECT vence_el FROM debt_transactions WHERE order_uuid = ?", java.sql.Date.class, v0.get("uuid_id")).toString())
                .isEqualTo(hoy.toString());
        Map<String, Object> vn = laVenta(sinPlazo);
        assertThat(vn).containsEntry("payment_method", "CREDITO").containsEntry("condicion_pago", "CREDITO");
        assertThat(vn.get("plazo_dias")).isNull();
        assertThat(dueno.queryForObject("SELECT vence_el FROM debt_transactions WHERE order_uuid = ?", java.sql.Date.class, vn.get("uuid_id"))).isNull();
    }

    @Test
    @DisplayName("🔴 F5.5: la insolvencia bloquea el despacho — 409, el pedido sigue CONFIRMADO y no hay venta")
    void insolvenciaBloquea() throws Exception {
        UUID id = tomarYConfirmar(TIENDA, "d-ins", 2);
        dueno.update("UPDATE clientes SET en_insolvencia_desde = current_date - 1 WHERE tenant_id = ? AND documento = ?", T, TIENDA);
        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"d-ins-desp\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CLIENTE_EN_INSOLVENCIA"));
        assertThat(dueno.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id = ?", String.class, id)).isEqualTo("CONFIRMADO");
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND pedido_id = ?", Integer.class, T, id)).isZero();
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.pedidos_eventos WHERE pedido_id = ? AND tipo = 'DESPACHADO'", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("🔴 F5.5: tras ENTREGA_FALLIDA, despachar otra vez y cancelar son 409 REVERSA_PENDIENTE; sigue habiendo UNA venta")
    void reversaPendiente() throws Exception {
        UUID id = tomarYConfirmar(TIENDA, "d-rev", 2);
        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"d-rev-1\"}").andExpect(status().isOk());
        long admin = dueno.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN);
        try (Connection c = dueno.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true)")) {
                ps.setString(1, T);
                ps.setString(2, String.valueOf(admin));
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pedidos.fn_pedido_entregar(?, 'ENTREGA_FALLIDA', 'CERRADO', NULL, NULL, NULL, NULL, NULL, NULL, now(), 'd-rev-falla')")) {
                ps.setObject(1, id);
                ps.executeQuery().close();
            }
            c.commit();
        }
        accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"d-rev-2\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("REVERSA_PENDIENTE"));
        accion(ADMIN, "admin", id, "cancelar", "{\"motivo\":\"CLIENTE_DESISTIO\",\"idempotencyKey\":\"d-rev-cancela\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("REVERSA_PENDIENTE"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND pedido_id = ?", Integer.class, T, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F5.5: la venta sale de la sede de despacho con su consecutivo; en la otra sede el mismo número es otra venta con su intención")
    void sedeYConsecutivo() throws Exception {
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + producto(2) + "\",\"quantity\":1,\"unitPrice\":1}],\"idempotencyKey\":\"caja-1\"}"))
                .andExpect(status().isCreated());
        UUID id = tomarYConfirmar(TIENDA, "d-sede", 2);
        accion(CAJA, "cajero", id, "despachar", "{\"siteId\":" + sedeBodega + ",\"idempotencyKey\":\"d-sede-desp\"}").andExpect(status().isOk());
        Map<String, Object> venta = laVenta(id);
        assertThat(((Number) venta.get("site_id")).longValue()).isEqualTo(sedeBodega);
        Map<String, Object> caja = dueno.queryForMap("SELECT id_order, site_id FROM orders WHERE tenant_id = ? AND idempotency_key = 'caja-1'", T);
        assertThat(((Number) caja.get("site_id")).longValue()).isEqualTo(sedePrincipal);
        assertThat(((Number) venta.get("id_order")).longValue()).as("primer número de la bodega").isEqualTo(1);
        assertThat(((Number) caja.get("id_order")).longValue()).as("primer número de la principal").isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT count(DISTINCT idempotency_key) FROM public.inventario_intenciones WHERE tenant_id = ?", Integer.class, T))
                .as("dos ventas con el número 1 en dos sedes: dos intenciones").isEqualTo(2);

        accion(CAJA, "cajero", tomarYConfirmar(TIENDA, "d-sede-x", 1), "despachar", "{\"siteId\":999999,\"idempotencyKey\":\"d-sede-x-desp\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("siteId"));
    }

    @Test
    @DisplayName("🔴 F5.6: una venta de pedido en medio no rompe la cadena de una terminal: la siguiente apunta a la anterior")
    void noRompeLaCadena() throws Exception {
        String terminal = "8c1e4f2a-3b55-4c29-9d16-7a2f5e1c4b88";
        for (int i = 1; i <= 2; i++) {
            if (i == 2) {
                UUID id = tomarYConfirmar(TIENDA, "d-cadena", 1);
                accion(CAJA, "cajero", id, "despachar", "{\"idempotencyKey\":\"d-cadena-desp\"}").andExpect(status().isOk());
            }
            mockMvc.perform(post("/api/waiter/mobile/orders").header("Authorization", bearer(CAJA, "cajero")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + i + "\",\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\""
                                    + producto(2) + "\",\"quantity\":1,\"unitPrice\":1}],\"idempotencyKey\":\"cadena-" + i + "\",\"terminalId\":\"" + terminal
                                    + "\",\"ocurridoEn\":\"" + OffsetDateTime.now(BOGOTA).minusMinutes(10 - i).withNano(0) + "\"}"))
                    .andExpect(status().isCreated());
        }
        List<Map<String, Object>> cadena = dueno.queryForList("SELECT seq, hash_anterior, hash_propio FROM orders WHERE tenant_id = ? "
                + "AND terminal_id = ?::uuid ORDER BY seq", T, terminal);
        assertThat(cadena).hasSize(2);
        assertThat(((Number) cadena.get(1).get("seq")).longValue()).isEqualTo(((Number) cadena.get(0).get("seq")).longValue() + 1);
        assertThat(cadena.get(1).get("hash_anterior")).as("la siguiente apunta a la anterior, no a la venta del pedido").isEqualTo(cadena.get(0).get("hash_propio"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND origen = 'pedido' AND (terminal_id IS NOT NULL OR hash_propio IS NOT NULL)",
                Integer.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 F5.5: solo admin y cajero despachan; sin clave, 400; un despacho sin cantidades es 400 y no deja evento")
    void lasReglasDelDespacho() throws Exception {
        JsonNode tomado = tomar(TIENDA, "d-reg", 2);
        UUID id = UUID.fromString(tomado.get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"d-reg-c\"}").andExpect(status().isOk());
        accion(ANA, "vendedor", id, "despachar", "{\"idempotencyKey\":\"x\"}").andExpect(status().isForbidden());
        accion(CAJA, "cajero", id, "despachar", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("idempotencyKey"));
        String l1 = tomado.get("lineas").get(0).get("lineaId").asText();
        String l2 = tomado.get("lineas").get(1).get("lineaId").asText();
        accion(CAJA, "cajero", id, "despachar", "{\"lineas\":[{\"lineaId\":\"" + l1 + "\",\"cantidad\":0},{\"lineaId\":\"" + l2
                + "\",\"cantidad\":0}],\"idempotencyKey\":\"d-reg-cero\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("lineas"));
        assertThat(dueno.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id = ?", String.class, id)).isEqualTo("CONFIRMADO");
        assertThat(dueno.queryForObject("SELECT count(*) FROM orders WHERE tenant_id = ? AND pedido_id = ?", Integer.class, T, id)).isZero();
    }
}
