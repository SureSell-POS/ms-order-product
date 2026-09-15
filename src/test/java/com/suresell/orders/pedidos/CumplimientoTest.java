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
 * Plan de mayoristas F5.11: el informe de cumplimiento por la API real, con {@code app_user}.
 *
 * <p>Tienda 900: P1 pide 10, se despachan 9 y se entregan 7; P2 pide 10 y el cliente desiste (no cuenta);
 * P3 pide 10 y se rechaza por existencias (cuenta, 0 entregado). Tienda 901: P4 pide 10 y se entregan 10.
 * Cumplimiento de la 900 = 7 / (10 + 10) = 0,35 en unidades y en valor (todo a 800); si el desistimiento
 * contara, sería 7 / 30.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CumplimientoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-cumplimiento";
    static final String ADMIN = "admin@qa-cumplimiento.invalid";
    static final String CAJA = "caja@qa-cumplimiento.invalid";
    static final String ANA = "ana@qa-cumplimiento.invalid";
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
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Cumplimiento', 'pro') ON CONFLICT (id) DO NOTHING", T);
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
        return String.format("qa-cump-%02d", i);
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
    @DisplayName("🔴 F5.11: el desistimiento del cliente no baja el cumplimiento; el rechazo por existencias sí; por cliente, vendedor y producto")
    void cumplimiento() throws Exception {
        cliente("901", 8);
        UUID p1 = despachado(TIENDA, "c-1", 9);
        accion(CAJA, "cajero", p1, "entregar", "{\"resultado\":\"ENTREGADO_CON_NOVEDAD\",\"motivo\":\"FALTANTE\",\"lineas\":[{\"lineaId\":\"" + linea(p1)
                + "\",\"cantidad\":7}],\"recibe\":{\"nombre\":\"Maria\",\"documento\":\"52\"},\"idempotencyKey\":\"c-1-e\"}").andExpect(status().isOk());
        UUID p2 = tomarYConfirmar(TIENDA, "c-2", 1);
        accion(ADMIN, "admin", p2, "cancelar", "{\"motivo\":\"CLIENTE_DESISTIO\",\"idempotencyKey\":\"c-2-x\"}").andExpect(status().isOk());
        UUID p3 = UUID.fromString(tomar(TIENDA, "c-3", 1).get("id").asText());
        accion(ADMIN, "admin", p3, "rechazar", "{\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"c-3-x\"}").andExpect(status().isOk());
        UUID p4 = despachado("901", "c-4", 10);
        accion(CAJA, "cajero", p4, "entregar", "{\"resultado\":\"ENTREGADO\",\"recibe\":{\"nombre\":\"Jose\",\"documento\":\"80\"},\"idempotencyKey\":\"c-4-e\"}")
                .andExpect(status().isOk());
        JsonNode r = leer(mockMvc.perform(get("/api/pedidos/cumplimiento").header("Authorization", bearer(ADMIN, "admin"))).andExpect(status().isOk()));
        JsonNode tienda = null;
        JsonNode otra = null;
        for (JsonNode f : r.get("filas")) {
            if (f.get("clave").asText().equals(TIENDA)) {
                tienda = f;
            }
            if (f.get("clave").asText().equals("901")) {
                otra = f;
            }
        }
        assertThat(tienda.get("pedidos").asInt()).as("P1 y P3; P2 no cuenta").isEqualTo(2);
        assertThat(tienda.get("pedidas").asInt()).isEqualTo(20);
        assertThat(tienda.get("despachadas").asInt()).isEqualTo(9);
        assertThat(tienda.get("entregadas").asInt()).isEqualTo(7);
        assertThat(tienda.get("cumplimientoUnidades").decimalValue()).as("7 / 20: el desistimiento no baja, el rechazo sí").isEqualByComparingTo("0.35");
        assertThat(tienda.get("cumplimientoValor").decimalValue()).isEqualByComparingTo("0.35");
        assertThat(tienda.get("rechazados").asInt()).isEqualTo(1);
        assertThat(tienda.get("canceladosPorElCliente").asInt()).isEqualTo(1);
        assertThat(tienda.get("canceladosPorElNegocio").asInt()).isZero();
        assertThat(otra.get("cumplimientoUnidades").decimalValue()).isEqualByComparingTo("1");
        assertThat(r.get("total").get("pedidas").asInt()).isEqualTo(30);
        assertThat(r.get("total").get("entregadas").asInt()).isEqualTo(17);

        mockMvc.perform(get("/api/pedidos/cumplimiento").param("agrupar", "producto").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filas.length()").value(1))
                .andExpect(jsonPath("$.filas[0].nombre").value("Producto 1"))
                .andExpect(jsonPath("$.filas[0].entregadas").value(17));
        mockMvc.perform(get("/api/pedidos/cumplimiento").param("agrupar", "vendedor").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filas.length()").value(1))
                .andExpect(jsonPath("$.filas[0].nombre").value("ana"));
        usuario("pedro@qa-cumplimiento.invalid", "vendedor");
        mockMvc.perform(get("/api/pedidos/cumplimiento").header("Authorization", bearer("pedro@qa-cumplimiento.invalid", "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.filas.length()").value(0))
                .andExpect(jsonPath("$.total.cumplimientoUnidades").doesNotExist());
        java.time.LocalDate hoy = java.time.LocalDate.now(BOGOTA);
        mockMvc.perform(get("/api/pedidos/cumplimiento").param("desde", hoy.minusDays(92).toString()).param("hasta", hoy.toString())
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("desde"));
        mockMvc.perform(get("/api/pedidos/cumplimiento").param("agrupar", "zona").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("agrupar"));
    }
}
