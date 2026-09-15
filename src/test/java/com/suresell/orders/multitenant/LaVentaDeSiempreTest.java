package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 🔴 Prueba de oro (plan de mayoristas F5.5, condición de ECM): <b>ninguna venta normal
 * del POS ni de meseros cambia</b> cuando el despacho de un pedido empiece a crear
 * ventas por dentro de {@code OrderHandler}.
 *
 * <p>Ocho formas de venta entran por las puertas reales ({@code /orders/create} y
 * {@code /api/waiter/mobile/orders}) contra un Postgres con todas las migraciones y la
 * intención de inventario encendida. De cada una se guarda TODO lo que dejó: la fila de
 * {@code orders}, sus líneas, sus pagos, su seguimiento de cocina, su intención de
 * inventario, su débito de cartera y la cuenta del cliente. Se normaliza lo que cambia
 * de una corrida a otra (uuid, momentos, ids de serie, hashes) y el resultado se compara
 * columna por columna con {@code src/test/resources/oro/la-venta-de-siempre.json}.
 *
 * <p><b>El fichero se grabó sobre el commit ANTERIOR a tocar {@code OrderHandler}</b>
 * (F5.5a ya aplicado: la clave de intención ya es {@code venta-<uuid>}). Si una
 * diferencia es a propósito, se regraba copiando {@code build/la-venta-de-siempre.json.nuevo}
 * y se dice en el commit por qué; nunca en silencio.
 *
 * <p><b>La cadena, cubierta de punta a punta</b> (condición de ECM, 2026-09-15): esta
 * misma prueba corrida sobre el commit ANTERIOR a F5.5a (49ade2e) contra este fichero
 * dio 14 diferencias y solo esas, dos por cada una de las 7 ventas que se crean:
 * {@code inventario_intenciones[0].idempotency_key} {@code orden-N → venta-<uuid>} y
 * {@code clave_de_intencion_es_la_de_la_venta} {@code false → true}. Ninguna otra
 * columna de orden, líneas, pagos, cocina, débito ni cuenta cambió con 5.5a.
 *
 * <p><b>Regrabado con F5.5 (V71), a propósito:</b> 7 diferencias, una por venta, todas
 * {@code orders[0].plazo_dias} que aparece con {@code null} (la columna nace en V71 y solo
 * la llena la venta de un pedido). Quitando esa clave, el resultado es idéntico al fichero
 * anterior; comprobado al regrabar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class LaVentaDeSiempreTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-venta-de-siempre";
    static final String CAJA = "caja@qa-venta-de-siempre.invalid";
    static final String ADMIN = "admin@qa-venta-de-siempre.invalid";
    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    static final Path ORO = Path.of("src/test/resources/oro/la-venta-de-siempre.json");
    static final Path NUEVO = Path.of("build/la-venta-de-siempre.json.nuevo");
    static final String TERMINAL = "5d2a8f1c-7e44-4b18-9c05-3a1f6d2b9e11";

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
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private JdbcTemplate dueno;

    /** Lo que cambia de una corrida a otra sin que la venta cambie. */
    static final Set<String> VOLATILES = Set.of("id", "uuid_id", "order_uuid_id", "order_id_uuid", "order_id", "order_uuid", "orden_uuid", "account_id",
            "created_at", "updated_at", "delivered_at", "ocurrido_en", "registrado_en", "aplicada_en", "hash_anterior",
            "hash_propio", "waiter_session_id", "table_session_id", "recibo_id", "credito_tx_id", "debito_tx_id",
            "orden_id", "id_order", "site_id", "lista_precio_id", "lista_precio_item_id", "transaction_date", "vence_el",
            "terminal_id", "epoch", "seq");
    static final Pattern UUID_TEXTO = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static String bearer(String email, String rol) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", rol)
                .claim("modules", List.of("ventas", "mayorista", "cartera", "cocina", PlanCatalog.MESEROS))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String item(String producto, int cantidad, int precio) {
        return "{\"productId\":\"" + producto + "\",\"quantity\":" + cantidad + ",\"unitPrice\":" + precio + "}";
    }

    private static String haceUnRato(int minutos) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutos).withNano(0).toString();
    }

    @Test
    @DisplayName("🔴 F5.5: ocho ventas normales dejan exactamente lo mismo que antes de que el despacho cree ventas")
    void laVentaDeSiempre() throws Exception {
        sembrar();
        Map<String, String> formas = new LinkedHashMap<>();
        formas.put("1-pos-efectivo-sin-cliente",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"1\",\"paymentMethod\":\"CASH\",\"items\":[" + item("oro-hamburguesa", 2, 1) + "],\"idempotencyKey\":\"oro-1\"}");
        formas.put("2-pos-tarjeta-con-cliente-y-lista",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"2\",\"paymentMethod\":\"CARD\",\"clienteDocumento\":\"900\",\"items\":["
                        + item("oro-hamburguesa", 1, 12000) + "," + item("oro-gaseosa", 3, 3000) + "],\"idempotencyKey\":\"oro-2\"}");
        formas.put("3-pos-pago-mixto",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"3\",\"paymentMethod\":\"MIXED\",\"payments\":[{\"method\":\"CASH\",\"amount\":10000},"
                        + "{\"method\":\"CARD\",\"amount\":14000}],\"items\":[" + item("oro-hamburguesa", 2, 12000) + "],\"idempotencyKey\":\"oro-3\"}");
        formas.put("4-pos-credito-con-plazo",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"4\",\"paymentMethod\":\"CREDITO\",\"clienteDocumento\":\"900\",\"items\":["
                        + item("oro-gaseosa", 10, 3000) + "],\"idempotencyKey\":\"oro-4\"}");
        formas.put("5-pos-con-terminal-y-hora-del-dispositivo",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"5\",\"paymentMethod\":\"CASH\",\"terminalId\":\"" + TERMINAL + "\",\"epoch\":1,\"seq\":1,"
                        + "\"ocurridoEn\":\"" + haceUnRato(20) + "\",\"items\":[" + item("oro-gaseosa", 1, 3000) + "],\"idempotencyKey\":\"oro-5\"}");
        formas.put("6-pos-preparada-en-comanda",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"6\",\"paymentMethod\":\"QR\",\"preparadoEnComanda\":true,\"items\":["
                        + item("oro-hamburguesa", 1, 12000) + "],\"idempotencyKey\":\"oro-6\"}");
        formas.put("7-mesero-efectivo-con-terminal-encadenada", null);
        formas.put("8-pos-que-intenta-hacerse-pasar-por-pedido",
                "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"8\",\"paymentMethod\":\"CASH\",\"origen\":\"pedido\",\"pedidoId\":\"" + UUID.randomUUID()
                        + "\",\"siteId\":999,\"items\":[" + item("oro-hamburguesa", 1, 1) + "],\"idempotencyKey\":\"oro-8\"}");

        ObjectNode resultado = json.createObjectNode();
        for (Map.Entry<String, String> forma : formas.entrySet()) {
            int estado;
            String clave;
            if (forma.getValue() == null) {
                clave = "oro-7";
                estado = mockMvc.perform(post("/api/waiter/mobile/orders").header("Authorization", bearer(CAJA, "cajero"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"pagerColor\":\"MESA\",\"pagerNumber\":\"7\",\"paymentMethod\":\"CASH\",\"items\":["
                                        + item("oro-hamburguesa", 1, 12000) + "],\"idempotencyKey\":\"" + clave + "\",\"terminalId\":\"" + TERMINAL
                                        + "\",\"ocurridoEn\":\"" + haceUnRato(15) + "\"}"))
                        .andReturn().getResponse().getStatus();
            } else {
                clave = json.readTree(forma.getValue()).get("idempotencyKey").asText();
                estado = mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero"))
                                .contentType(MediaType.APPLICATION_JSON).content(forma.getValue()))
                        .andReturn().getResponse().getStatus();
            }
            ObjectNode caso = json.createObjectNode();
            caso.put("estadoHttp", estado);
            caso.set("lo_que_quedo", loQueDejo(clave));
            resultado.set(forma.getKey(), caso);
        }

        String obtenido = json.writeValueAsString(resultado) + "\n";
        Files.createDirectories(NUEVO.getParent());
        Files.writeString(NUEVO, obtenido);
        assertThat(Files.exists(ORO)).as("no hay fichero de oro: copiar " + NUEVO + " a " + ORO + " en el commit ANTERIOR a tocar OrderHandler").isTrue();
        assertThat(json.readTree(obtenido)).as("una venta normal cambió; diff " + ORO + " " + NUEVO).isEqualTo(json.readTree(Files.readString(ORO)));
    }

    private void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'La venta de siempre', 'pro') ON CONFLICT (id) DO NOTHING", T);
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        dueno.update("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, 'cajero', 'Caja'), (?, '!', ?, 'admin', 'Admin')",
                CAJA, T, ADMIN, T);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                + "('oro-hamburguesa', ?, 'Hamburguesa', 12000, true), ('oro-gaseosa', ?, 'Gaseosa', 3000, true)", T, T);
        UUID lista = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) VALUES (?, 'tiendas', 'Tiendas', 's') RETURNING id",
                UUID.class, T);
        dueno.update("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, vigente_desde) "
                + "VALUES (?, ?, 'oro-gaseosa', 1, 2500, 's', 'declarado_comerciante', 1, now() - interval '30 days')", T, lista);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, plazo_dias, lista_precio_id, creado_por) VALUES (?, '900', 'Tienda', 8, ?, 's')", T, lista);
    }

    /** Todo lo que dejó una venta, normalizado. */
    private JsonNode loQueDejo(String clave) throws Exception {
        ObjectNode r = json.createObjectNode();
        List<Map<String, Object>> orden = dueno.queryForList("SELECT uuid_id, created_at FROM orders WHERE tenant_id = ? AND idempotency_key = ?", T, clave);
        if (orden.isEmpty()) {
            r.put("orden", "ninguna");
            return r;
        }
        UUID uuid = (UUID) orden.get(0).get("uuid_id");
        LocalDate hoy = LocalDate.now(BOGOTA);
        r.set("orders", filas("SELECT to_jsonb(o) AS j FROM orders o WHERE o.uuid_id = ?", uuid, hoy));
        r.set("order_item", filas("SELECT to_jsonb(i) AS j FROM order_item i WHERE i.order_uuid_id = ? ORDER BY i.product_id, i.quantity", uuid, hoy));
        r.set("order_payments", filas("SELECT to_jsonb(p) AS j FROM order_payments p WHERE p.order_uuid_id = ? ORDER BY p.method", uuid, hoy));
        r.set("order_delivery_tracking", filas("SELECT to_jsonb(t) AS j FROM order_delivery_tracking t WHERE t.order_id_uuid = ?", uuid, hoy));
        r.set("inventario_intenciones", filas("SELECT to_jsonb(x) AS j FROM public.inventario_intenciones x WHERE x.orden_uuid = ?", uuid, hoy));
        r.set("debt_transactions", filas("SELECT to_jsonb(d) AS j FROM debt_transactions d WHERE d.order_uuid = ?", uuid, hoy));
        r.put("clave_de_intencion_es_la_de_la_venta", dueno.queryForObject(
                "SELECT COALESCE(bool_and(idempotency_key = 'venta-' || ?::text), false) FROM public.inventario_intenciones WHERE orden_uuid = ?",
                Boolean.class, uuid.toString(), uuid));
        r.put("vence_a_dias_de_hoy", dueno.queryForObject(
                "SELECT (max(vence_el) - ?::date) FROM debt_transactions WHERE order_uuid = ?", Integer.class, java.sql.Date.valueOf(hoy), uuid));
        r.put("en_la_sede_por_defecto", dueno.queryForObject(
                "SELECT o.site_id = s.id FROM orders o JOIN sites s ON s.tenant_id = o.tenant_id AND s.is_default WHERE o.uuid_id = ?", Boolean.class, uuid));
        r.set("cuenta_del_cliente", filas("SELECT jsonb_build_object('credit_limit', credit_limit, 'total_debt', total_debt, 'status', status) AS j "
                + "FROM accounts_receivable WHERE tenant_id = ? AND customer_document = '900'", T, hoy));
        r.put("creada_por", dueno.queryForObject("SELECT u.email FROM orders o LEFT JOIN users u ON u.id = o.created_by WHERE o.uuid_id = ?", String.class, uuid));
        return r;
    }

    private ArrayNode filas(String sql, Object param, LocalDate hoy) throws Exception {
        ArrayNode a = json.createArrayNode();
        for (String j : dueno.queryForList(sql, String.class, param)) {
            a.add(normalizar((ObjectNode) json.readTree(j)));
        }
        return a;
    }

    private JsonNode normalizar(ObjectNode fila) {
        TreeMap<String, JsonNode> ordenado = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = fila.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (VOLATILES.contains(e.getKey())) {
                ordenado.put(e.getKey(), json.getNodeFactory().textNode(v.isNull() ? "<nulo>" : "<presente>"));
            } else if (e.getKey().equals("created_by") || e.getKey().equals("usuario_id") || e.getKey().equals("vendedor_id")) {
                ordenado.put(e.getKey(), json.getNodeFactory().textNode(v.isNull() ? "<nulo>" : "<usuario>"));
            } else if (v.isTextual() && UUID_TEXTO.matcher(v.asText()).find()) {
                ordenado.put(e.getKey(), json.getNodeFactory().textNode(UUID_TEXTO.matcher(v.asText()).replaceAll("<uuid>")));
            } else {
                ordenado.put(e.getKey(), v);
            }
        }
        ObjectNode n = json.createObjectNode();
        ordenado.forEach(n::set);
        return n;
    }
}
