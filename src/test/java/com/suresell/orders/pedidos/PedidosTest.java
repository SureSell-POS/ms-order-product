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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Plan de mayoristas F5.3 por la API real (contrato §7.5), sobre la cadena {@code pedidos}
 * V2, con {@code app_user} (RLS y permisos de verdad). El caso del plan: un pedido de 20
 * líneas, ajustado a 18 confirmadas, con el precio congelado igual al de {@code /precios}
 * en el momento de la captura, y una lista que sube entre la captura y la confirmación
 * sin mover el precio (Q2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class PedidosTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-pedidos";
    static final String OTRO = "qa-pedidos-otro";
    static final String ADMIN = "admin@qa-pedidos.invalid";
    static final String CAJA = "caja@qa-pedidos.invalid";
    static final String ANA = "ana@qa-pedidos.invalid";
    static final String PEDRO = "pedro@qa-pedidos.invalid";
    static final String ADMIN_OTRO = "admin@qa-pedidos-otro.invalid";
    static final String TIENDA = "900";
    static final String CERRADA = "901";

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
    private long ana;
    private long pedro;
    private UUID lista;

    private static String bearer(String email, String role, String tenant, String... modulos) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", role)
                .claim("modules", modulos.length == 0 ? List.of("ventas", "mayorista", "cartera") : List.of(modulos))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(String email, String role) {
        return bearer(email, role, T);
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            dueno.update("DELETE FROM pedidos.pedidos_eventos_lineas WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos_lineas WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.pedidos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM pedidos.contadores_de_pedidos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM listas_precio_items WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM listas_precio WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        usuario(ADMIN, "admin", "Admin", T);
        usuario(CAJA, "cajero", "Caja", T);
        ana = usuario(ANA, "vendedor", "Ana", T);
        pedro = usuario(PEDRO, "vendedor", "Pedro", T);
        usuario(ADMIN_OTRO, "admin", "Otro", OTRO);
        for (int i = 1; i <= 20; i++) {
            dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, ?, ?, true)",
                    producto(i), T, "Producto " + i, 1000 + i);
        }
        lista = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) "
                + "VALUES (?, 'tiendas', 'Tiendas', 'semilla') RETURNING id", UUID.class, T);
        dueno.update("INSERT INTO listas_precio_items (tenant_id, lista_id, producto_id, cantidad_minima, precio, usuario_id, fuente, confianza, vigente_desde) "
                + "VALUES (?, ?, ?, 1, 800, 's', 'declarado_comerciante', 1, now() - interval '30 days')", T, lista, producto(1));
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, lista_precio_id, creado_por) "
                + "VALUES (?, ?, 'Tienda La Esquina', ?, 8, ?, 's')", T, TIENDA, ana, lista);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, activo, creado_por) VALUES (?, ?, 'Cerrada', false, 's')", T, CERRADA);
    }

    private long usuario(String email, String rol, String nombre, String tenant) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, tenant, rol, nombre);
    }

    private static String producto(int i) {
        return String.format("qa-ped-%02d", i);
    }

    private static String lineas(int cuantas, int cantidad) {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= cuantas; i++) {
            l.add("{\"productoId\":\"" + producto(i) + "\",\"cantidad\":" + cantidad + "}");
        }
        return "[" + String.join(",", l) + "]";
    }

    private ResultActions tomar(String quien, String rol, String clave, String cuerpoLineas, OffsetDateTime ocurrido) throws Exception {
        return mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"origen\":\"vendedor\",\"lineas\":" + cuerpoLineas
                        + ",\"ocurridoEn\":\"" + ocurrido + "\",\"idempotencyKey\":\"" + clave + "\"}"));
    }

    private ResultActions accion(String quien, String rol, UUID id, String que, String cuerpo) throws Exception {
        return accion(quien, rol, T, id, que, cuerpo);
    }

    private ResultActions accion(String quien, String rol, String negocio, UUID id, String que, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos/" + id + "/" + que).header("Authorization", bearer(quien, rol, negocio))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID nuevoDeAna(String clave) throws Exception {
        return UUID.fromString(leer(tomar(ANA, "vendedor", clave, lineas(3, 5), OffsetDateTime.now()).andExpect(status().isCreated()))
                .get("id").asText());
    }

    @Test
    @DisplayName("🔴 F5.3: 20 líneas, ajustado a 18 confirmadas, precio congelado = /precios en la captura; la lista que sube después no lo mueve (Q2)")
    void tomarAjustarYConfirmar() throws Exception {
        OffsetDateTime captura = OffsetDateTime.now(ZoneId.of("America/Bogota")).minusHours(2).withNano(0);
        JsonNode creado = leer(tomar(ANA, "vendedor", "ana-1", lineas(20, 10), captura)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ENVIADO"))
                .andExpect(jsonPath("$.vendedorId").value(ana))
                .andExpect(jsonPath("$.lineas.length()").value(20)));
        UUID id = UUID.fromString(creado.get("id").asText());
        assertThat(creado.get("lineas").get(0).get("precioVisto").decimalValue()).isEqualByComparingTo("800");
        assertThat(creado.get("lineas").get(0).get("precioOrigen").asText()).isEqualTo("LISTA");
        assertThat(creado.get("lineas").get(1).get("precioOrigen").asText()).isEqualTo("BASE");

        // El precio de la captura es el que da /precios en ese momento, línea por línea.
        JsonNode precios = leer(mockMvc.perform(post("/api/mayorista/precios").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"lineas\":" + lineas(20, 10) + ",\"momento\":\"" + captura + "\"}"))
                .andExpect(status().isOk()));
        for (int i = 0; i < 20; i++) {
            assertThat(precios.get("lineas").get(i).get("precio").decimalValue())
                    .as("línea %d", i + 1).isEqualByComparingTo(creado.get("lineas").get(i).get("precioVisto").decimalValue());
        }

        // El reintento devuelve el mismo pedido; la misma clave con otro contenido, 409.
        tomar(ANA, "vendedor", "ana-1", lineas(20, 10), captura).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        tomar(ANA, "vendedor", "ana-1", lineas(20, 11), captura).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_REUTILIZADA"));

        // La lista sube entre la captura y la confirmación: /precios de ahora ya lo dice.
        mockMvc.perform(post("/api/mayorista/listas/" + lista + "/lineas").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":\"" + producto(1) + "\",\"precio\":950}"))
                .andExpect(status().isCreated());
        JsonNode ahora = leer(mockMvc.perform(post("/api/mayorista/precios").header("Authorization", bearer(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"lineas\":[{\"productoId\":\"" + producto(1) + "\",\"cantidad\":10}]}")));
        assertThat(ahora.get("lineas").get(0).get("precio").decimalValue()).as("la lista de verdad subió").isEqualByComparingTo("950");

        // La caja confirma quitando dos líneas: AJUSTADO y CONFIRMADO, 18 confirmadas, precio de la captura.
        String linea19 = creado.get("lineas").get(18).get("lineaId").asText();
        String linea20 = creado.get("lineas").get(19).get("lineaId").asText();
        JsonNode confirmado = leer(accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + linea19 + "\",\"cantidad\":0},"
                + "{\"lineaId\":\"" + linea20 + "\",\"cantidad\":0}],\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"caja-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO")));
        List<String> tipos = new ArrayList<>();
        confirmado.get("eventos").forEach(e -> tipos.add(e.get("tipo").asText()));
        assertThat(tipos).containsExactly("ENVIADO", "AJUSTADO", "CONFIRMADO");
        int confirmadas = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode l : confirmado.get("lineas")) {
            assertThat(l.get("precioConfirmado").decimalValue()).isEqualByComparingTo(l.get("precioVisto").decimalValue());
            if (l.get("confirmada").asInt() > 0) {
                confirmadas++;
                total = total.add(l.get("precioConfirmado").decimalValue().multiply(BigDecimal.valueOf(l.get("confirmada").asInt())));
            }
        }
        assertThat(confirmadas).isEqualTo(18);
        assertThat(confirmado.get("lineas").get(0).get("precioConfirmado").decimalValue()).as("Q2").isEqualByComparingTo("800");
        assertThat(confirmado.get("total").decimalValue()).isEqualByComparingTo(total);
        assertThat(confirmado.get("eventos").get(1).get("motivo").asText()).isEqualTo("SIN_EXISTENCIA");
        assertThat(confirmado.get("eventos").get(2).get("usuario").asText()).isEqualTo("Caja");

        // El reintento de la confirmación no escribe otro evento.
        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + linea19 + "\",\"cantidad\":0},"
                + "{\"lineaId\":\"" + linea20 + "\",\"cantidad\":0}],\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"caja-1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventos.length()").value(3));
    }

    @Test
    @DisplayName("🔴 F5.3: quién ve y quién mueve — vendedor solo lo suyo, confirmar de caja, otro negocio no existe, sin módulo 403")
    void roles() throws Exception {
        UUID deAna = nuevoDeAna("ana-roles");

        accion(ANA, "vendedor", deAna, "confirmar", "{\"idempotencyKey\":\"a\"}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pedidos/" + deAna).header("Authorization", bearer(PEDRO, "vendedor")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("NO_EXISTE"));
        mockMvc.perform(get("/api/pedidos").header("Authorization", bearer(PEDRO, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(0));
        mockMvc.perform(get("/api/pedidos").param("vendedorId", String.valueOf(pedro)).header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1));
        mockMvc.perform(get("/api/pedidos/" + deAna).header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO)))
                .andExpect(status().isNotFound());
        accion(ADMIN_OTRO, "admin", OTRO, deAna, "rechazar", "{\"motivo\":\"DUPLICADO\",\"idempotencyKey\":\"otro\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("NO_EXISTE"));
        // Un usuario de otro negocio con un token de este: no es autor de nada aquí.
        accion(ADMIN_OTRO, "admin", deAna, "rechazar", "{\"motivo\":\"DUPLICADO\",\"idempotencyKey\":\"otro-2\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SIN_USUARIO"));
        assertThat(dueno.queryForObject("SELECT estado FROM pedidos.pedidos WHERE id = ?", String.class, deAna)).isEqualTo("ENVIADO");
        mockMvc.perform(get("/api/pedidos").header("Authorization", bearer(ADMIN, "admin", T, "ventas")))
                .andExpect(status().isForbidden());

        // Un vendedor no toma pedidos a nombre de otro.
        mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ANA, "vendedor")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"origen\":\"vendedor\",\"vendedorId\":" + pedro
                                + ",\"lineas\":" + lineas(1, 1) + ",\"idempotencyKey\":\"ana-pedro\"}"))
                .andExpect(status().isForbidden());

        // Lo que toma la caja nace confirmado.
        tomar(CAJA, "cajero", "caja-toma", lineas(2, 3), OffsetDateTime.now()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.lineas[0].precioConfirmado").value(800.0));

        // Bandeja del admin: los dos, filtrables por estado, cliente y día de captura.
        mockMvc.perform(get("/api/pedidos").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(2));
        mockMvc.perform(get("/api/pedidos").param("estado", "ENVIADO").param("clienteDocumento", TIENDA)
                        .param("fecha", java.time.LocalDate.now(ZoneId.of("America/Bogota")).toString())
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1))
                .andExpect(jsonPath("$.pedidos[0].id").value(deAna.toString()))
                .andExpect(jsonPath("$.pedidos[0].lineas").value(3))
                .andExpect(jsonPath("$.pedidos[0].vendedor").value("Ana"));
        JsonNode pagina1 = leer(mockMvc.perform(get("/api/pedidos").param("limite", "1").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1))
                .andExpect(jsonPath("$.siguiente").isString()));
        JsonNode pagina2 = leer(mockMvc.perform(get("/api/pedidos").param("limite", "1").param("despuesDe", pagina1.get("siguiente").asText())
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1))
                .andExpect(jsonPath("$.siguiente").doesNotExist()));
        assertThat(pagina2.get("pedidos").get(0).get("id").asText()).isNotEqualTo(pagina1.get("pedidos").get(0).get("id").asText());
        mockMvc.perform(get("/api/pedidos").param("despuesDe", "cualquier-cosa").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("despuesDe"));
        mockMvc.perform(get("/api/pedidos").param("estado", "INVENTADO").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("estado"));
    }

    @Test
    @DisplayName("🔴 F5.3: retener, liberar con porqué, rechazar con motivo, transición no permitida y la reversa pendiente de lo despachado")
    void lasOtrasAcciones() throws Exception {
        UUID uno = nuevoDeAna("ana-uno");
        accion(ADMIN, "admin", uno, "retener", "{\"motivo\":\"MORA\",\"idempotencyKey\":\"r1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RETENIDO"));
        accion(CAJA, "cajero", uno, "confirmar", "{\"idempotencyKey\":\"c-retenido\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TRANSICION_NO_PERMITIDA"));
        accion(ADMIN, "admin", uno, "liberar", "{\"nota\":\"Pagó la factura vencida\",\"idempotencyKey\":\"l1\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        accion(ADMIN, "admin", uno, "liberar", "{\"motivo\":\"MORA\",\"idempotencyKey\":\"l1\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        accion(ADMIN, "admin", uno, "liberar", "{\"motivo\":\"PAGO_RECIBIDO\",\"idempotencyKey\":\"l1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("LIBERADO"))
                .andExpect(jsonPath("$.eventos[2].motivo").value("PAGO_RECIBIDO"));
        // Liberado y con una referencia agotada: se confirma con una línea menos (V3 de pedidos: LIBERADO → AJUSTADO).
        String lineaUno = leer(mockMvc.perform(get("/api/pedidos/" + uno).header("Authorization", bearer(ADMIN, "admin"))))
                .get("lineas").get(2).get("lineaId").asText();
        accion(CAJA, "cajero", uno, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + lineaUno + "\",\"cantidad\":0}],"
                        + "\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"c1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.eventos[*].tipo").value(org.hamcrest.Matchers.contains("ENVIADO", "RETENIDO", "LIBERADO", "AJUSTADO", "CONFIRMADO")))
                .andExpect(jsonPath("$.lineas[2].confirmada").value(0));
        // Confirmado ya no se reajusta: el faltante va en el despacho.
        accion(CAJA, "cajero", uno, "ajustar", "{\"lineas\":[{\"lineaId\":\"" + lineaUno + "\",\"cantidad\":1}],\"idempotencyKey\":\"c1-re\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TRANSICION_NO_PERMITIDA"));

        UUID dos = nuevoDeAna("ana-dos");
        accion(ADMIN, "admin", dos, "rechazar", "{\"idempotencyKey\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        accion(CAJA, "cajero", dos, "rechazar", "{\"motivo\":\"SIN_EXISTENCIA\",\"idempotencyKey\":\"x\"}")
                .andExpect(status().isForbidden());
        accion(ADMIN, "admin", dos, "rechazar", "{\"motivo\":\"SIN_EXISTENCIA\",\"nota\":\"Sin arroz\",\"idempotencyKey\":\"x\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.eventos[1].nota").value("Sin arroz"));
        accion(ADMIN, "admin", dos, "cancelar", "{\"motivo\":\"DUPLICADO\",\"idempotencyKey\":\"y\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("TRANSICION_NO_PERMITIDA"));
        // Misma clave para otra acción: 409, sin escribir.
        UUID tres = nuevoDeAna("ana-tres");
        accion(ADMIN, "admin", tres, "rechazar", "{\"motivo\":\"DUPLICADO\",\"idempotencyKey\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_REUTILIZADA"));

        // Lo despachado no se cancela sin reversa. El despacho aún no tiene API (F5.5): va por la función.
        despachar(uno);
        accion(ADMIN, "admin", uno, "cancelar", "{\"motivo\":\"CLIENTE_DESISTIO\",\"idempotencyKey\":\"z\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("REVERSA_PENDIENTE"));
    }

    @Test
    @DisplayName("🔴 F5.3: lo que no entra — autoventa, cliente desactivado, producto ajeno, canal, hora del futuro, precio sin ser admin")
    void loQueNoEntra() throws Exception {
        String base = "{\"clienteDocumento\":\"%s\",\"origen\":\"%s\",\"modalidad\":\"%s\",\"lineas\":%s,\"ocurridoEn\":\"%s\",\"idempotencyKey\":\"%s\"}";
        String ahora = OffsetDateTime.now().toString();
        crearCon(String.format(base, TIENDA, "vendedor", "AUTOVENTA", lineas(1, 1), ahora, "k1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("AUTOVENTA_SIN_DESPACHO"));
        crearCon(String.format(base, CERRADA, "vendedor", "PREVENTA", lineas(1, 1), ahora, "k2"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CLIENTE_INACTIVO"));
        crearCon(String.format(base, TIENDA, "vendedor", "PREVENTA", "[{\"productoId\":\"de-otro\",\"cantidad\":1}]", ahora, "k3"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("lineas"));
        crearCon(String.format(base, TIENDA, "canal_app", "PREVENTA", lineas(1, 1), ahora, "k4"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("origen"));
        crearCon(String.format(base, TIENDA, "vendedor", "PREVENTA", lineas(1, 1), OffsetDateTime.now().plusHours(1), "k5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("ocurridoEn"));
        crearCon(String.format(base, "no-existe", "vendedor", "PREVENTA", lineas(1, 1), ahora, "k6"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("clienteDocumento"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.pedidos WHERE tenant_id = ?", Integer.class, T)).isZero();

        // El precio solo lo corrige un admin, con ERROR_DE_PRECIO, y la confirmación lo respeta.
        JsonNode p = leer(tomar(ANA, "vendedor", "ana-precio", lineas(2, 4), OffsetDateTime.now()).andExpect(status().isCreated()));
        UUID id = UUID.fromString(p.get("id").asText());
        String linea2 = p.get("lineas").get(1).get("lineaId").asText();
        String conPrecio = "{\"lineas\":[{\"lineaId\":\"" + linea2 + "\",\"cantidad\":4,\"precio\":700}],\"motivo\":\"ERROR_DE_PRECIO\",\"idempotencyKey\":\"%s\"}";
        accion(CAJA, "cajero", id, "ajustar", String.format(conPrecio, "p-caja")).andExpect(status().isForbidden());
        accion(ADMIN, "admin", id, "ajustar", "{\"lineas\":[{\"lineaId\":\"" + linea2 + "\",\"cantidad\":4,\"precio\":700}],\"idempotencyKey\":\"p-sin\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("motivo"));
        accion(ADMIN, "admin", id, "ajustar", String.format(conPrecio, "p-admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("AJUSTADO"));
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"p-confirma\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.lineas[1].precioConfirmado").value(700.0))
                .andExpect(jsonPath("$.lineas[0].precioConfirmado").value(800.0));
        accion(CAJA, "cajero", id, "confirmar", "{\"lineas\":[{\"lineaId\":\"" + UUID.randomUUID() + "\",\"cantidad\":1}],\"idempotencyKey\":\"p-ajena\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("lineas"));
    }

    @Test
    @DisplayName("🔴 F5.3: la bandeja va por entrega prometida (sin fecha al final) y número; entregaEl filtra y no es anterior a la captura")
    void porEntrega() throws Exception {
        java.time.LocalDate hoy = java.time.LocalDate.now(ZoneId.of("America/Bogota"));
        String base = "{\"clienteDocumento\":\"" + TIENDA + "\",\"origen\":\"televenta\",\"lineas\":" + lineas(1, 1) + ",%s\"idempotencyKey\":\"%s\"}";
        String sinFecha = leer(crearCon(String.format(base, "", "e-sin")).andExpect(status().isCreated())).get("id").asText();
        String manana = leer(crearCon(String.format(base, "\"entregaEl\":\"" + hoy.plusDays(1) + "\",", "e-manana")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.entregaEl").value(hoy.plusDays(1).toString()))).get("id").asText();
        String hoyId = leer(crearCon(String.format(base, "\"entregaEl\":\"" + hoy + "\",", "e-hoy")).andExpect(status().isCreated())).get("id").asText();
        String manana2 = leer(crearCon(String.format(base, "\"entregaEl\":\"" + hoy.plusDays(1) + "\",", "e-manana-2")).andExpect(status().isCreated())).get("id").asText();
        crearCon(String.format(base, "\"entregaEl\":\"" + hoy.minusDays(1) + "\",", "e-ayer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("entregaEl"));

        JsonNode bandeja = leer(mockMvc.perform(get("/api/pedidos").header("Authorization", bearer(ADMIN, "admin"))).andExpect(status().isOk()));
        List<String> orden = new ArrayList<>();
        bandeja.get("pedidos").forEach(p -> orden.add(p.get("id").asText()));
        assertThat(orden).containsExactly(hoyId, manana, manana2, sinFecha);

        // Por páginas de uno, el cursor recorre el mismo orden sin repetir ni saltar.
        List<String> recorrido = new ArrayList<>();
        String cursor = null;
        do {
            var peticion = get("/api/pedidos").param("limite", "1").header("Authorization", bearer(ADMIN, "admin"));
            if (cursor != null) {
                peticion.param("despuesDe", cursor);
            }
            JsonNode pagina = leer(mockMvc.perform(peticion).andExpect(status().isOk()));
            pagina.get("pedidos").forEach(p -> recorrido.add(p.get("id").asText()));
            cursor = pagina.get("siguiente").isNull() ? null : pagina.get("siguiente").asText();
        } while (cursor != null);
        assertThat(recorrido).containsExactly(hoyId, manana, manana2, sinFecha);

        mockMvc.perform(get("/api/pedidos").param("entregaEl", hoy.plusDays(1).toString()).header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(2));

        // Por origen: uno o varios, con los valores del enum.
        leer(tomar(ANA, "vendedor", "e-vendedor", lineas(1, 1), OffsetDateTime.now()).andExpect(status().isCreated()));
        mockMvc.perform(get("/api/pedidos").param("origen", "televenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(4))
                .andExpect(jsonPath("$.pedidos[*].origen", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("televenta"))));
        mockMvc.perform(get("/api/pedidos").param("origen", "vendedor").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(1));
        mockMvc.perform(get("/api/pedidos").param("origen", "vendedor, TELEVENTA,enlace").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pedidos.length()").value(5));
        mockMvc.perform(get("/api/pedidos").param("origen", "whatsapp").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("origen"));

        // Conteos por estado: todos presentes, con los filtros y la visibilidad de la bandeja.
        mockMvc.perform(get("/api/pedidos/conteos").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.length()").value(13))
                .andExpect(jsonPath("$.conteos.CONFIRMADO").value(4))
                .andExpect(jsonPath("$.conteos.ENVIADO").value(1))
                .andExpect(jsonPath("$.conteos.RETENIDO").value(0))
                .andExpect(jsonPath("$.conteos.CREADO_BORRADOR").doesNotExist());
        mockMvc.perform(get("/api/pedidos/conteos").param("origen", "televenta").param("entregaEl", hoy.plusDays(1).toString())
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conteos.CONFIRMADO").value(2)).andExpect(jsonPath("$.conteos.ENVIADO").value(0));
        mockMvc.perform(get("/api/pedidos/conteos").param("vendedorId", String.valueOf(pedro)).header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conteos.ENVIADO").value(1)).andExpect(jsonPath("$.conteos.CONFIRMADO").value(0));
        mockMvc.perform(get("/api/pedidos/conteos").header("Authorization", bearer(PEDRO, "vendedor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conteos.ENVIADO").value(0));
        mockMvc.perform(get("/api/pedidos/conteos").param("origen", "whatsapp").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("origen"));
    }

    @Test
    @DisplayName("🔴 F5.3: confirmar=false deja en ENVIADO lo que toma un admin o cajero; ausente confirma; a un vendedor no le cambia nada")
    void confirmarAlTomar() throws Exception {
        String base = "{\"clienteDocumento\":\"" + TIENDA + "\",\"origen\":\"televenta\",\"lineas\":" + lineas(1, 1) + ",%s\"idempotencyKey\":\"%s\"}";
        crearCon(String.format(base, "\"confirmar\":false,", "c-admin-no")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ENVIADO")).andExpect(jsonPath("$.eventos.length()").value(1));
        mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(CAJA, "cajero")).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, "\"confirmar\":false,", "c-caja-no")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ENVIADO"));
        mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(CAJA, "cajero")).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, "", "c-caja-si")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("CONFIRMADO"));
        mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ANA, "vendedor")).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(base, "\"confirmar\":true,", "c-ana-si")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ENVIADO"));
    }

    private ResultActions crearCon(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    /** DESPACHADO por la función, con la sesión del admin, en una sola transacción. */
    private void despachar(UUID id) throws Exception {
        long admin = dueno.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, ADMIN);
        try (Connection c = dueno.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true)")) {
                ps.setString(1, T);
                ps.setString(2, String.valueOf(admin));
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pedidos.fn_pedido_transicionar(?, 'DESPACHADO', NULL, NULL, NULL, now(), 'despacho-prueba')")) {
                ps.setObject(1, id);
                ps.executeQuery().close();
            }
            c.commit();
        }
    }
}
