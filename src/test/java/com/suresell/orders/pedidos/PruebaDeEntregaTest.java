package com.suresell.orders.pedidos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F5.7b por la API real, con app_user y un almacén SIMULADO en memoria (el real, Supabase Storage, lo crea Santiago):
 * la foto y la firma de una entrega se registran UNA vez, con su rastro; 413/503/502; aislamiento por negocio y por
 * vendedor; URL firmadas en el detalle; y la retención de un año por evento, con constancia y sin reabrir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class PruebaDeEntregaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-prueba-entrega";
    static final String OTRO = "qa-prueba-entrega-otro";
    static final String ADMIN = "admin@qa-prueba-entrega.invalid";
    static final String CAJA = "caja@qa-prueba-entrega.invalid";
    static final String ANA = "ana@qa-prueba-entrega.invalid";
    static final String PEDRO = "pedro@qa-prueba-entrega.invalid";
    static final String ADMIN_OTRO = "admin@qa-prueba-entrega-otro.invalid";
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

    /** El almacén de mentira: guarda en memoria, firma con una URL reconocible y puede fallar a voluntad. */
    static class AlmacenSimulado implements AlmacenDePruebas {
        final Map<String, byte[]> objetos = new ConcurrentHashMap<>();
        final List<String> borrados = new ArrayList<>();
        volatile boolean configurado = true;
        volatile boolean fallaAlSubir = false;
        volatile boolean fallaAlBorrar = false;
        volatile int subidas = 0;

        @Override public boolean configurado() { return configurado; }
        @Override public void subir(String ruta, byte[] contenido, String tipo) {
            if (fallaAlSubir) {
                throw new NoDisponible("simulado: el almacén no responde", null);
            }
            subidas++;
            objetos.put(ruta, contenido);
        }
        @Override public String firmar(String ruta, int segundos) { return "https://almacen.simulado/" + ruta + "?expiresIn=" + segundos; }
        @Override public void borrar(List<String> rutas) {
            if (fallaAlBorrar) {
                throw new NoDisponible("simulado: no se pudo borrar", null);
            }
            borrados.addAll(rutas);
            rutas.forEach(objetos::remove);
        }
        void reiniciar() {
            objetos.clear(); borrados.clear(); configurado = true; fallaAlSubir = false; fallaAlBorrar = false; subidas = 0;
        }
    }

    @TestConfiguration
    static class ConAlmacenSimulado {
        @Bean @Primary AlmacenSimulado almacenSimulado() { return new AlmacenSimulado(); }
    }

    @Autowired MockMvc mockMvc;
    @Autowired AlmacenSimulado almacen;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate dueno;
    private long caja;

    private static String bearer(String email, String rol, String tenant) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", rol)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(String email, String rol) {
        return bearer(email, rol, T);
    }

    @BeforeEach
    void sembrar() {
        almacen.reiniciar();
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            for (String tabla : new String[] {"pedidos.entregas_purgadas", "pedidos.entregas", "pedidos.pedidos_eventos_lineas", "pedidos.pedidos_eventos",
                    "pedidos.pedidos_lineas", "pedidos.pedidos", "pedidos.contadores_de_pedidos", "public.inventario_intenciones", "cartera_aplicaciones",
                    "debt_transactions", "order_delivery_tracking", "order_item", "orders", "tenant_order_counters", "clientes_eventos",
                    "accounts_receivable", "clientes", "menu_products", "sites", "users"}) {
                dueno.update("DELETE FROM " + tabla + " WHERE tenant_id = ?", t);
            }
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Prueba de entrega', 'pro') ON CONFLICT (id) DO NOTHING", t);
        }
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        usuario(ADMIN, "admin", T);
        caja = usuario(CAJA, "cajero", T);
        long ana = usuario(ANA, "vendedor", T);
        usuario(PEDRO, "vendedor", T);
        usuario(ADMIN_OTRO, "admin", OTRO);
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES ('qa-pe-arroz', ?, 'Arroz', 5000, true)", T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES (?, '900', 'Tienda', ?, 8, 's')", T, ana);
    }

    private long usuario(String email, String rol, String tenant) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, ?, ?) RETURNING id",
                Long.class, email, tenant, rol, email.substring(0, email.indexOf('@')));
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Un pedido de Ana, confirmado, despachado y con entrega fallida: la entrega existe. Devuelve {pedido, entrega}. */
    private UUID[] entregaFallida(String clave) throws Exception {
        JsonNode t = leer(mockMvc.perform(post("/api/pedidos").header("Authorization", bearer(ANA, "vendedor")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"900\",\"origen\":\"vendedor\",\"lineas\":[{\"productoId\":\"qa-pe-arroz\",\"cantidad\":2}],"
                                + "\"ocurridoEn\":\"" + OffsetDateTime.now(BOGOTA).minusHours(2).withNano(0) + "\",\"idempotencyKey\":\"" + clave + "\"}"))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(t.get("id").asText());
        String linea = t.get("lineas").get(0).get("lineaId").asText();
        accion(CAJA, "cajero", id, "confirmar", "{\"idempotencyKey\":\"" + clave + "-c\"}").andExpect(status().isOk());
        accion(CAJA, "cajero", id, "despachar", "{\"lineas\":[{\"lineaId\":\"" + linea + "\",\"cantidad\":2}],\"idempotencyKey\":\"" + clave + "-d\"}")
                .andExpect(status().isOk());
        JsonNode entregado = leer(accion(CAJA, "cajero", id, "entregar", "{\"resultado\":\"ENTREGA_FALLIDA\",\"motivo\":\"CERRADO\",\"idempotencyKey\":\"" + clave + "-e\"}")
                .andExpect(status().isOk()));
        return new UUID[] {id, UUID.fromString(entregado.get("entrega").get("id").asText())};
    }

    private ResultActions accion(String quien, String rol, UUID id, String que, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos/" + id + "/" + que).header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static MockMultipartFile foto(int bytes) {
        return new MockMultipartFile("foto", "foto.jpg", "image/jpeg", new byte[bytes]);
    }

    private static MockMultipartFile firma(int bytes) {
        return new MockMultipartFile("firma", "firma.png", "image/png", new byte[bytes]);
    }

    private ResultActions subir(String email, String rol, String tenant, UUID pedido, UUID entrega, MockMultipartFile... partes) throws Exception {
        var peticion = multipart("/api/pedidos/" + pedido + "/entregas/" + entrega + "/prueba");
        for (MockMultipartFile p : partes) {
            peticion.file(p);
        }
        return mockMvc.perform(peticion.header("Authorization", bearer(email, rol, tenant)));
    }

    private Map<String, Object> fila(UUID entrega) {
        return dueno.queryForMap("SELECT * FROM pedidos.entregas WHERE id = ?", entrega);
    }

    @Test
    @DisplayName("🔴 F5.7b: foto y firma se registran con su ruta, quién y cuándo; el detalle trae URL firmadas de 15 minutos")
    void registraFotoYFirma() throws Exception {
        UUID[] e = entregaFallida("pe-1");
        String base = T + "/" + e[0] + "/" + e[1] + "/";
        subir(CAJA, "cajero", T, e[0], e[1], foto(20_000), firma(3_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entrega.fotoUrl").value("https://almacen.simulado/" + base + "foto.jpg?expiresIn=900"))
                .andExpect(jsonPath("$.entrega.firmaUrl").value("https://almacen.simulado/" + base + "firma.png?expiresIn=900"))
                .andExpect(jsonPath("$.entrega.fotoRegistradaEn").isNotEmpty());
        assertThat(almacen.objetos).containsOnlyKeys(base + "foto.jpg", base + "firma.png");
        Map<String, Object> f = fila(e[1]);
        assertThat(f).containsEntry("foto_asset_id", base + "foto.jpg").containsEntry("firma_asset_id", base + "firma.png");
        assertThat(((Number) f.get("foto_registrada_por")).longValue()).isEqualTo(caja);
        mockMvc.perform(get("/api/pedidos/" + e[0]).header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(jsonPath("$.entrega.fotoUrl").value("https://almacen.simulado/" + base + "foto.jpg?expiresIn=900"));
    }

    @Test
    @DisplayName("🔴 F5.7b: una prueba no se cambia: otra foto → 409 PRUEBA_YA_REGISTRADA sin tocar el almacén; la firma sí se añade después, una vez")
    void inmutable() throws Exception {
        UUID[] e = entregaFallida("pe-2");
        subir(CAJA, "cajero", T, e[0], e[1], foto(1_000)).andExpect(status().isOk());
        int subidas = almacen.subidas;
        subir(ADMIN, "admin", T, e[0], e[1], foto(2_000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PRUEBA_YA_REGISTRADA"));
        assertThat(almacen.subidas).as("el almacén no se tocó").isEqualTo(subidas);
        assertThat(almacen.objetos.get(T + "/" + e[0] + "/" + e[1] + "/foto.jpg")).hasSize(1_000);
        subir(CAJA, "cajero", T, e[0], e[1], firma(500)).andExpect(status().isOk());
        subir(CAJA, "cajero", T, e[0], e[1], firma(600))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PRUEBA_YA_REGISTRADA"));
    }

    @Test
    @DisplayName("🔴 F5.7b: más de 1 MB → 413; foto que no es JPEG o firma que no es PNG → 400; sin partes → 400")
    void validaciones() throws Exception {
        UUID[] e = entregaFallida("pe-3");
        subir(CAJA, "cajero", T, e[0], e[1], foto(1024 * 1024 + 1))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.codigo").value("ARCHIVO_DEMASIADO_GRANDE"));
        subir(CAJA, "cajero", T, e[0], e[1], new MockMultipartFile("foto", "foto.png", "image/png", new byte[10]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("foto"));
        subir(CAJA, "cajero", T, e[0], e[1], new MockMultipartFile("firma", "firma.jpg", "image/jpeg", new byte[10]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("firma"));
        subir(CAJA, "cajero", T, e[0], e[1]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("foto"));
        assertThat(almacen.subidas).isZero();
        assertThat(fila(e[1]).get("foto_asset_id")).isNull();
    }

    @Test
    @DisplayName("🔴 F5.7b: sin almacén → 503 y la entrega sigue igual; almacén que falla → 502 sin ruta, y el reintento entra")
    void sinAlmacenYAlmacenQueFalla() throws Exception {
        UUID[] e = entregaFallida("pe-4");
        almacen.configurado = false;
        subir(CAJA, "cajero", T, e[0], e[1], foto(100))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ALMACEN_NO_CONFIGURADO"));
        mockMvc.perform(get("/api/pedidos/" + e[0]).header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entrega.fotoUrl").doesNotExist());
        almacen.configurado = true;
        almacen.fallaAlSubir = true;
        subir(CAJA, "cajero", T, e[0], e[1], foto(100))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.codigo").value("ALMACEN_NO_DISPONIBLE"));
        assertThat(fila(e[1]).get("foto_asset_id")).isNull();
        almacen.fallaAlSubir = false;
        subir(CAJA, "cajero", T, e[0], e[1], foto(100)).andExpect(status().isOk());
        assertThat(fila(e[1]).get("foto_asset_id")).isNotNull();
    }

    @Test
    @DisplayName("🔴 F5.7b: otro negocio, una entrega de otro pedido o un vendedor ajeno → 404, sin subir nada")
    void aislamiento() throws Exception {
        UUID[] e = entregaFallida("pe-5");
        UUID[] otra = entregaFallida("pe-5b");
        subir(ADMIN_OTRO, "admin", OTRO, e[0], e[1], foto(100)).andExpect(status().isNotFound());
        subir(CAJA, "cajero", T, e[0], otra[1], foto(100)).andExpect(status().isNotFound());
        subir(PEDRO, "vendedor", T, e[0], e[1], foto(100)).andExpect(status().isNotFound());
        assertThat(almacen.subidas).isZero();
        subir(ANA, "vendedor", T, e[0], e[1], foto(100)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("🔴 F5.7b: retención de un año por EVENTO: una prueba nueva purga la vieja del negocio (objetos, rutas y constancia) y la vieja no se reabre")
    void purgaPorEvento() throws Exception {
        UUID[] vieja = entregaFallida("pe-6");
        subir(CAJA, "cajero", T, vieja[0], vieja[1], foto(100), firma(50)).andExpect(status().isOk());
        String base = T + "/" + vieja[0] + "/" + vieja[1] + "/";
        dueno.update("UPDATE pedidos.entregas SET registrado_en = now() - interval '13 months', ocurrido_en = now() - interval '13 months' WHERE id = ?", vieja[1]);

        UUID[] nueva = entregaFallida("pe-6b");
        subir(CAJA, "cajero", T, nueva[0], nueva[1], foto(100)).andExpect(status().isOk());

        Map<String, Object> f = fila(vieja[1]);
        assertThat(f.get("foto_asset_id")).isNull();
        assertThat(f.get("firma_asset_id")).isNull();
        assertThat(f.get("prueba_purgada_en")).isNotNull();
        assertThat(almacen.borrados).containsExactlyInAnyOrder(base + "foto.jpg", base + "firma.png");
        assertThat(almacen.objetos).doesNotContainKeys(base + "foto.jpg", base + "firma.png");
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.entregas_purgadas WHERE tenant_id = ? AND conteo = 1 AND entregas = ARRAY[?::uuid]",
                Integer.class, T, vieja[1].toString())).isEqualTo(1);
        assertThat(fila(nueva[1]).get("foto_asset_id")).as("la prueba nueva no se toca").isNotNull();
        subir(CAJA, "cajero", T, vieja[0], vieja[1], foto(100))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("PRUEBA_YA_REGISTRADA"));
    }

    @Test
    @DisplayName("F5.7b: si el almacén no deja borrar, la prueba nueva se guarda igual y la purga queda para la siguiente")
    void purgaQueFallaNoTumbaLaPrueba() throws Exception {
        UUID[] vieja = entregaFallida("pe-7");
        subir(CAJA, "cajero", T, vieja[0], vieja[1], foto(100)).andExpect(status().isOk());
        dueno.update("UPDATE pedidos.entregas SET registrado_en = now() - interval '13 months', ocurrido_en = now() - interval '13 months' WHERE id = ?", vieja[1]);
        almacen.fallaAlBorrar = true;
        UUID[] nueva = entregaFallida("pe-7b");
        subir(CAJA, "cajero", T, nueva[0], nueva[1], foto(100)).andExpect(status().isOk());
        assertThat(fila(nueva[1]).get("foto_asset_id")).isNotNull();
        assertThat(fila(vieja[1]).get("foto_asset_id")).isNotNull();
        assertThat(dueno.queryForObject("SELECT count(*) FROM pedidos.entregas_purgadas WHERE tenant_id = ?", Integer.class, T)).isZero();
    }
}
