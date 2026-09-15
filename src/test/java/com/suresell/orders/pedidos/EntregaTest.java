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
 * Plan de mayoristas F5.7 por la API real, con {@code app_user}: entregar con su prueba, la
 * novedad sin inventario que queda PENDIENTE DE REVERSA (derivada, V4 de pedidos) y el recaudo
 * contraentrega con recibo y aplicación a la venta del pedido, en la misma transacción.
 *
 * <p>El caso: pedido de 10 a 800, despacho de 9 (venta de 7.200), entrega de 7. Pendiente de
 * reversa = (9 − 7) × 800 = 1.600, por lo DESPACHADO y no por lo pedido (que daría 2.400).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class EntregaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-entrega";
    static final String ADMIN = "admin@qa-entrega.invalid";
    static final String CAJA = "caja@qa-entrega.invalid";
    static final String ANA = "ana@qa-entrega.invalid";
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
                "debt_transactions", "recibos_de_caja", "contadores_de_recibos", "order_delivery_tracking", "order_item", "orders", "tenant_order_counters", "clientes_eventos",
                "accounts_receivable", "clientes", "listas_precio_items", "listas_precio", "menu_products", "sites", "users"}) {
            dueno.update("DELETE FROM " + tabla + " WHERE tenant_id = ?", T);
        }
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Entrega', 'pro') ON CONFLICT (id) DO NOTHING", T);
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
        return String.format("qa-entr-%02d", i);
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


    private UUID despachado(String documento, String clave, int despachar) throws Exception {
        JsonNode t = tomar(documento, clave, 1);
        UUID id = UUID.fromString(t.get("id").asText());
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"" + clave + "-c\"}").andExpect(status().isOk());
        accion(CAJA, "cajero", id, "despachar", "{\"lineas\":[{\"lineaId\":\"" + t.get("lineas").get(0).get("lineaId").asText()
                + "\",\"cantidad\":" + despachar + "}],\"idempotencyKey\":\"" + clave + "-d\"}").andExpect(status().isOk());
        return id;
    }

    private String linea(UUID id) throws Exception {
        return leer(mockMvc.perform(get("/api/pedidos/" + id).header("Authorization", bearer(ADMIN, "admin")))).get("lineas").get(0).get("lineaId").asText();
    }

    private BigDecimal saldoDeLaVenta(UUID pedido) {
        return dueno.queryForObject("SELECT v.saldo FROM v_cartera_por_documento v JOIN orders o ON o.uuid_id = v.order_uuid "
                + "WHERE o.tenant_id = ? AND o.pedido_id = ?", BigDecimal.class, T, pedido);
    }

    @Test
    @DisplayName("🔴 F5.7: entrega de 7 de 9 despachados con cobro contraentrega: prueba, recibo aplicado a la venta, 1.600 pendientes de reversa, venta e inventario intactos")
    void novedadYContraentrega() throws Exception {
        cliente("901", 0);
        UUID id = despachado("901", "e-1", 9);
        Map<String, Object> ventaAntes = laVenta(id);
        int intencionesAntes = dueno.queryForObject("SELECT count(*) FROM public.inventario_intenciones WHERE tenant_id = ?", Integer.class, T);
        String cuerpo = "{\"resultado\":\"ENTREGADO_CON_NOVEDAD\",\"motivo\":\"FALTANTE\",\"lineas\":[{\"lineaId\":\"" + linea(id)
                + "\",\"cantidad\":7}],\"recibe\":{\"nombre\":\"Maria Pérez\",\"documento\":\"52000111\"},\"latitud\":4.6097,"
                + "\"longitud\":-74.0817,\"recibo\":{\"monto\":5600,\"medio\":\"EFECTIVO\"},\"idempotencyKey\":\"e-1-e\"}";

        JsonNode entregado = leer(accion(CAJA, "cajero", id, "entregar", cuerpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO_CON_NOVEDAD"))
                .andExpect(jsonPath("$.entrega.recibeDocumento").value("52000111"))
                .andExpect(jsonPath("$.pendienteDeReversa").value(true)));
        assertThat(entregado.get("valorPendienteDeReversa").decimalValue()).as("(9 − 7) × 800, por lo despachado").isEqualByComparingTo("1600");

        Map<String, Object> ventaDespues = laVenta(id);
        assertThat((BigDecimal) ventaDespues.get("total")).as("la venta no se toca sin reversa").isEqualByComparingTo((BigDecimal) ventaAntes.get("total"));
        assertThat((BigDecimal) ventaDespues.get("total")).isEqualByComparingTo("7200");
        assertThat(dueno.queryForObject("SELECT count(*) FROM public.inventario_intenciones WHERE tenant_id = ?", Integer.class, T))
                .as("el inventario no se toca sin reversa").isEqualTo(intencionesAntes);
        Map<String, Object> aplicacion = dueno.queryForMap("SELECT a.monto, r.medio FROM cartera_aplicaciones a JOIN recibos_de_caja r ON r.id = a.recibo_id "
                + "JOIN debt_transactions d ON d.id = a.debito_tx_id WHERE d.order_uuid = ?", ventaDespues.get("uuid_id"));
        assertThat((BigDecimal) aplicacion.get("monto")).isEqualByComparingTo("5600");
        assertThat(aplicacion).containsEntry("medio", "EFECTIVO");
        assertThat(saldoDeLaVenta(id)).as("lo que queda debiendo = lo pendiente de reversa").isEqualByComparingTo("1600");
        assertThat(dueno.queryForObject("SELECT entregado_en IS NOT NULL FROM pedidos.pedidos WHERE id = ?", Boolean.class, id)).isTrue();

        // El reintento: una prueba y un recibo.
        accion(CAJA, "cajero", id, "entregar", cuerpo).andExpect(status().isOk());
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.entregas WHERE pedido_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ? AND cliente_documento = '901'", Integer.class, T)).isEqualTo(1);

        // A la vista: bandeja filtrada y conteo.
        mockMvc.perform(get("/api/pedidos").param("pendienteDeReversa", "true").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1))
                .andExpect(jsonPath("$.pedidos[0].valorPendienteDeReversa").value(1600.0));
        mockMvc.perform(get("/api/pedidos/conteos").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendientesDeReversa").value(1));
    }

    @Test
    @DisplayName("🔴 F5.7: entrega completa sin novedad no deja nada pendiente; el cobro total deja saldo 0")
    void entregaCompleta() throws Exception {
        UUID id = despachado(TIENDA, "e-2", 10);
        accion(ANA, "vendedor", id, "entregar", "{\"resultado\":\"ENTREGADO\",\"recibe\":{\"nombre\":\"Don José\",\"documento\":\"80111222\"},"
                        + "\"recibo\":{\"monto\":8000,\"medio\":\"TRANSFERENCIA\",\"referenciaMedio\":\"123\"},\"idempotencyKey\":\"e-2-e\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.pendienteDeReversa").value(false));
        assertThat(saldoDeLaVenta(id)).isEqualByComparingTo("0");
        mockMvc.perform(get("/api/pedidos").param("pendienteDeReversa", "true").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(0));
    }

    @Test
    @DisplayName("🔴 F5.7: entrega fallida — motivo obligatorio, sin quien recibe ni cobro; todo lo despachado pendiente de reversa")
    void fallida() throws Exception {
        UUID id = despachado(TIENDA, "e-3", 10);
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGA_FALLIDA\",\"idempotencyKey\":\"e-3-a\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGA_FALLIDA\",\"motivo\":\"CERRADO\",\"recibo\":{\"monto\":1,\"medio\":\"EFECTIVO\"},\"idempotencyKey\":\"e-3-b\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("recibo"));
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGA_FALLIDA\",\"motivo\":\"CERRADO\",\"recibe\":{\"nombre\":\"x\",\"documento\":\"1\"},\"idempotencyKey\":\"e-3-c\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("recibe"));
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGA_FALLIDA\",\"motivo\":\"CERRADO\",\"idempotencyKey\":\"e-3-entrega\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ENTREGA_FALLIDA"))
                .andExpect(jsonPath("$.valorPendienteDeReversa").value(8000.0))
                .andExpect(jsonPath("$.entrega.recibeNombre").doesNotExist());
    }

    @Test
    @DisplayName("🔴 F5.7: lo que no entra no deja nada — más de lo despachado, cobro mayor que la deuda, sin quien recibe, foto, vendedor ajeno, escritura directa")
    void loQueNoEntra() throws Exception {
        UUID id = despachado(TIENDA, "e-4", 5);
        String l = linea(id);
        String recibe = "\"recibe\":{\"nombre\":\"Maria\",\"documento\":\"52\"}";
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGADO_CON_NOVEDAD\",\"motivo\":\"SOBRANTE\",\"lineas\":[{\"lineaId\":\"" + l
                        + "\",\"cantidad\":6}]," + recibe + ",\"idempotencyKey\":\"e-4-a\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("lineas"));
        // El cobro no entra (mayor que la deuda) y arrastra la entrega: nada escrito.
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGADO\"," + recibe + ",\"recibo\":{\"monto\":999999,\"medio\":\"EFECTIVO\"},\"idempotencyKey\":\"e-4-b\"}")
                .andExpect(status().isBadRequest());
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGADO\",\"lineas\":[{\"lineaId\":\"" + l + "\",\"cantidad\":4}]," + recibe
                        + ",\"idempotencyKey\":\"e-4-menos\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("resultado"));
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGADO\",\"idempotencyKey\":\"e-4-c\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("recibe"));
        accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGADO\"," + recibe + ",\"fotoAssetId\":\"x\",\"idempotencyKey\":\"e-4-foto\"}")
                .andExpect(status().isBadRequest());
        assertThat(dueno.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id = ?", String.class, id)).isEqualTo("DESPACHADO");
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.entregas WHERE pedido_id = ?", Integer.class, id)).isZero();
        assertThat(dueno.queryForObject("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", Integer.class, T)).isZero();

        long otro = usuario("pedro@qa-entrega.invalid", "vendedor");
        mockMvc.perform(post("/api/pedidos/" + id + "/entregar").header("Authorization", bearer("pedro@qa-entrega.invalid", "vendedor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultado\":\"ENTREGADO\"," + recibe + ",\"idempotencyKey\":\"e-4-e\"}"))
                .andExpect(status().isNotFound());
        assertThat(otro).isPositive();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             java.sql.Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + T + "', false)");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute("INSERT INTO pedidos.entregas (tenant_id, pedido_id, evento_id, resultado, "
                            + "registrado_por, ocurrido_en) SELECT tenant_id, pedido_id, id, 'ENTREGADO', 1, now() FROM pedidos.pedidos_eventos LIMIT 1"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(e -> assertThat(((java.sql.SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }
}
