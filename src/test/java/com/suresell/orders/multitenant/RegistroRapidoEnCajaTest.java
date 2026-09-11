package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.application.usecase.LimiteDeClavesDeRegistro;
import com.suresell.orders.application.usecase.RegistroRapidoEnCaja;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V55 — Registrar un producto desde la caja, y la configuración de caja que lo
 * gobierna, por la API real contra la cadena Flyway entera.
 *
 * <p>Lo que fija (contrato CAJA-POR-TURNOS-Y-REGISTRO-EN-CAJA §3):
 * <ul>
 *   <li>201 con la MISMA forma de producto que {@code categories-with-products}.
 *   <li>El código ya vigente es 409 {@code YA_EXISTE} con el {@code productoId}
 *       (el reintento es idempotente).
 *   <li>PIN malo → 403 {@code PIN_INCORRECTO}; sin PIN → 403 {@code SIN_PIN}.
 *   <li>El tope diario → 409 {@code LIMITE_DIARIO}.
 *   <li>Sin categorías, «General» se crea UNA vez.
 *   <li>La configuración de caja es solo del administrador; el PIN nunca viaja.
 *   <li>5 claves incorrectas en 10 minutos por negocio → 429 sin evaluar el PIN.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class RegistroRapidoEnCajaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-registro";
    static final String PIN = "4821";

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

    /**
     * Reloj que el test mueve a mano: la ventana de las claves incorrectas es
     * de 10 minutos y no se va a esperar de verdad. {@code LimiteDeClavesDeRegistro}
     * toma el {@code Clock} del contexto si hay uno; en producción no lo hay.
     */
    static final class RelojDePrueba extends Clock {
        volatile Instant ahora = Instant.now();

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @TestConfiguration
    static class ConReloj {
        @Bean
        RelojDePrueba reloj() {
            return new RelojDePrueba();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired RelojDePrueba reloj;
    @Autowired LimiteDeClavesDeRegistro limite;
    final ObjectMapper json = new ObjectMapper();

    private String bearer(String rol) {
        return "Bearer " + Jwts.builder()
                .subject(rol + "@" + TENANT)
                .claim("tenant_id", TENANT)
                .claim("role", rol)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @BeforeEach
    void sembrar() throws Exception {
        // El contador de claves incorrectas vive en memoria y el contexto se
        // comparte entre tests: cada uno empieza de cero y con el reloj en hora.
        limite.reiniciar(TENANT);
        reloj.ahora = Instant.now();
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM codigos_de_producto WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM menu_products WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM menu_categories WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + TENANT + "','R','pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                    + "VALUES ('" + TENANT + "', 'Principal', 'PRINCIPAL', 'DIRECTO', true)");
        }
    }

    private void categoria(String id, String nombre) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO menu_categories (id_category, tenant_id, name_category) VALUES ('"
                    + id + "','" + TENANT + "','" + nombre + "')");
        }
    }

    private MvcResult configurarCaja(String rol, String cuerpo) throws Exception {
        return mockMvc.perform(put("/account/sites/caja")
                        .header("Authorization", bearer(rol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
    }

    private void ponerPin() throws Exception {
        MvcResult r = configurarCaja("admin", "{\"pin\":\"" + PIN + "\"}");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    private MvcResult registrar(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/menu/products/registro-rapido")
                        .header("Authorization", bearer("cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
    }

    private static String cuerpo(String nombre, int precio, String codigo, String pin, String categoriaId) {
        return "{\"nombre\":\"" + nombre + "\",\"precio\":" + precio + ",\"codigo\":\"" + codigo + "\""
                + (pin == null ? "" : ",\"pin\":\"" + pin + "\"")
                + (categoriaId == null ? "" : ",\"categoriaId\":\"" + categoriaId + "\"") + "}";
    }

    private JsonNode leer(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int contar(String sql) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // =====================================================================

    @Test
    @DisplayName("🔴 feliz: 201 con el mismo producto que el catálogo, activo, con su código EAN, y ya sale en categories-with-products")
    void registroFeliz() throws Exception {
        categoria("CAT-granos", "Granos");
        ponerPin();

        MvcResult r = registrar(cuerpo("Arroz Diana 500 g", 3500, "7702001234567", PIN, "CAT-granos"));

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode p = leer(r);
        assertThat(p.get("idProduct").asText()).isEqualTo(TENANT + "-arroz-diana-500-g");
        assertThat(p.get("nameProduct").asText()).isEqualTo("Arroz Diana 500 g");
        assertThat(p.get("price").asInt()).isEqualTo(3500);
        assertThat(p.get("active").asBoolean()).isTrue();
        assertThat(p.get("categoryId").asText()).isEqualTo("CAT-granos");
        assertThat(p.get("categoryName").asText()).isEqualTo("Granos");
        assertThat(p.get("codigos")).hasSize(1);
        assertThat(p.get("codigos").get(0).get("codigo").asText()).isEqualTo("7702001234567");
        assertThat(p.get("codigos").get(0).get("tipo").asText()).isEqualTo("EAN");
        assertThat(p.get("codigos").get(0).get("cantidad").asInt()).isEqualTo(1);

        // La misma forma, campo por campo, que el catálogo que lee el POS.
        MvcResult cat = mockMvc.perform(get("/api/menu/categories-with-products")
                .header("Authorization", bearer("cajero"))).andReturn();
        JsonNode delCatalogo = leer(cat).get(0).get("products").get(0);
        assertThat(iterable(delCatalogo)).containsExactlyElementsOf(iterable(p));
        assertThat(delCatalogo.get("idProduct").asText()).isEqualTo(p.get("idProduct").asText());
        assertThat(delCatalogo.get("codigos").get(0).get("codigo").asText()).isEqualTo("7702001234567");

        // Quién y cuándo (regla 4), y de dónde (regla 6: `pos` = desde la caja en V51).
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT p.creado_en_caja_por, p.creado_en_caja_en, c.fuente, c.creado_por "
                     + "FROM menu_products p JOIN codigos_de_producto c ON c.producto_id = p.id_product "
                     + "WHERE p.id_product = '" + TENANT + "-arroz-diana-500-g'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("cajero@" + TENANT);
            assertThat(rs.getTimestamp(2)).isNotNull();
            assertThat(rs.getString(3)).isEqualTo("pos");
            assertThat(rs.getString(4)).isEqualTo("cajero@" + TENANT);
        }
    }

    private static java.util.List<String> iterable(JsonNode n) {
        java.util.List<String> campos = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(campos::add);
        return campos;
    }

    @Test
    @DisplayName("🔴 un código ya vigente es 409 YA_EXISTE con el productoId del que lo tiene, y no crea nada")
    void codigoRepetido() throws Exception {
        categoria("CAT-granos", "Granos");
        ponerPin();
        assertThat(registrar(cuerpo("Arroz Diana 500 g", 3500, "7702001234567", PIN, "CAT-granos"))
                .getResponse().getStatus()).isEqualTo(201);

        // El reintento (mismo cuerpo) y otro nombre con el mismo código: los dos 409 con el producto vigente.
        for (String nombre : new String[] {"Arroz Diana 500 g", "Otro arroz"}) {
            MvcResult r = registrar(cuerpo(nombre, 3600, "7702001234567", PIN, "CAT-granos"));
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
            JsonNode e = leer(r);
            assertThat(e.get("error").asText()).isEqualTo("YA_EXISTE");
            assertThat(e.get("codigo").asText()).isEqualTo("YA_EXISTE");
            assertThat(e.get("productoId").asText()).isEqualTo(TENANT + "-arroz-diana-500-g");
            assertThat(e.get("mensaje").asText()).contains("Arroz Diana 500 g");
        }
        assertThat(contar("SELECT count(*) FROM menu_products WHERE tenant_id = '" + TENANT + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 PIN incorrecto es 403 PIN_INCORRECTO; sin PIN configurado, 403 SIN_PIN; ninguno crea nada")
    void pinMaloYSinPin() throws Exception {
        // Sin PIN configurado: la caja no registra y lo dice.
        MvcResult sinPin = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", "0000", null));
        assertThat(sinPin.getResponse().getStatus()).isEqualTo(403);
        assertThat(leer(sinPin).get("codigo").asText()).isEqualTo("SIN_PIN");
        assertThat(leer(sinPin).get("error").asText()).isEqualTo("SIN_PIN");

        ponerPin();
        MvcResult malo = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", "1111", null));
        assertThat(malo.getResponse().getStatus()).isEqualTo(403);
        JsonNode e = leer(malo);
        assertThat(e.get("codigo").asText()).isEqualTo("PIN_INCORRECTO");
        assertThat(e.get("campo").asText()).isEqualTo("pin");
        assertThat(e.get("mensaje").asText()).isNotBlank();

        MvcResult vacio = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", null, null));
        assertThat(vacio.getResponse().getStatus()).isEqualTo(403);
        assertThat(leer(vacio).get("codigo").asText()).isEqualTo("PIN_INCORRECTO");

        assertThat(contar("SELECT count(*) FROM menu_products WHERE tenant_id = '" + TENANT + "'")).isZero();
        assertThat(contar("SELECT count(*) FROM menu_categories WHERE tenant_id = '" + TENANT + "'")).isZero();
    }

    @Test
    @DisplayName("🔴 el tope diario es 409 LIMITE_DIARIO, contado sobre creado_en_caja_en de hoy")
    void topeDiario() throws Exception {
        ponerPin();
        MvcResult tope = configurarCaja("admin", "{\"maxRegistrosCajaPorDia\":2}");
        assertThat(tope.getResponse().getStatus()).isEqualTo(200);
        // Uno de AYER no cuenta para hoy.
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, creado_en_caja_en) "
                    + "VALUES ('" + TENANT + "-de-ayer','" + TENANT + "','De ayer',1000,true, now() - interval '2 days')");
        }

        assertThat(registrar(cuerpo("Uno", 1000, "1001", PIN, null)).getResponse().getStatus()).isEqualTo(201);
        assertThat(registrar(cuerpo("Dos", 1000, "1002", PIN, null)).getResponse().getStatus()).isEqualTo(201);

        MvcResult tercero = registrar(cuerpo("Tres", 1000, "1003", PIN, null));
        assertThat(tercero.getResponse().getStatus()).isEqualTo(409);
        JsonNode e = leer(tercero);
        assertThat(e.get("codigo").asText()).isEqualTo("LIMITE_DIARIO");
        assertThat(e.get("mensaje").asText()).isEqualTo("Hoy ya se registraron 2 productos desde la caja.");
        assertThat(contar("SELECT count(*) FROM codigos_de_producto WHERE codigo = '1003'")).isZero();
    }

    @Test
    @DisplayName("🔴 sin categorías crea «General» una sola vez: dos registros, una categoría")
    void generalUnaSolaVez() throws Exception {
        ponerPin();

        JsonNode uno = leer(registrar(cuerpo("Tornillo 1/4", 300, "TOR-14", PIN, null)));
        JsonNode dos = leer(registrar(cuerpo("Tuerca 1/4", 200, "TUE-14", PIN, null)));

        assertThat(uno.get("categoryId").asText()).isEqualTo(TENANT + "-general");
        assertThat(uno.get("categoryName").asText()).isEqualTo("General");
        assertThat(dos.get("categoryId").asText()).isEqualTo(TENANT + "-general");
        assertThat(contar("SELECT count(*) FROM menu_categories WHERE tenant_id = '" + TENANT + "'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM menu_products WHERE tenant_id = '" + TENANT
                + "' AND category_id = '" + TENANT + "-general'")).isEqualTo(2);
    }

    @Test
    @DisplayName("el mismo nombre con otro código no pisa el producto: toma el id -2")
    void mismoNombreOtroCodigo() throws Exception {
        ponerPin();
        assertThat(leer(registrar(cuerpo("Gaseosa", 2500, "A1", PIN, null))).get("idProduct").asText())
                .isEqualTo(TENANT + "-gaseosa");
        assertThat(leer(registrar(cuerpo("Gaseosa", 2600, "A2", PIN, null))).get("idProduct").asText())
                .isEqualTo(TENANT + "-gaseosa-2");
    }

    @Test
    @DisplayName("los campos malos son 400 con `campo`: nombre vacío, precio ≤ 0, código vacío, con espacios o de más de 64")
    void camposInvalidos() throws Exception {
        ponerPin();
        String[][] casos = {
            {cuerpo("   ", 1000, "X1", PIN, null), "nombre"},
            {cuerpo("Algo", 0, "X1", PIN, null), "precio"},
            {cuerpo("Algo", -5, "X1", PIN, null), "precio"},
            {cuerpo("Algo", 1000, "  ", PIN, null), "codigo"},
            {cuerpo("Algo", 1000, "77 02", PIN, null), "codigo"},
            {cuerpo("Algo", 1000, "7".repeat(65), PIN, null), "codigo"},
        };
        for (String[] caso : casos) {
            MvcResult r = registrar(caso[0]);
            assertThat(r.getResponse().getStatus()).as(caso[0]).isEqualTo(400);
            assertThat(leer(r).get("campo").asText()).as(caso[0]).isEqualTo(caso[1]);
            assertThat(leer(r).get("mensaje").asText()).as(caso[0]).isNotBlank();
        }
        assertThat(contar("SELECT count(*) FROM menu_products WHERE tenant_id = '" + TENANT + "'")).isZero();
    }

    @Test
    @DisplayName("🔴 un cajero no puede cambiar ni ver la configuración de caja (403); el administrador sí, y el PIN nunca viaja")
    void cajeroNoConfiguraLaCaja() throws Exception {
        MvcResult putCajero = configurarCaja("cajero", "{\"baseCaja\":1,\"pin\":\"9999\"}");
        assertThat(putCajero.getResponse().getStatus()).isEqualTo(403);
        assertThat(leer(putCajero).get("codigo").asText()).isEqualTo("SOLO_ADMINISTRADOR");
        assertThat(mockMvc.perform(get("/account/sites/caja").header("Authorization", bearer("cajero")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(contar("SELECT count(*) FROM sites WHERE tenant_id = '" + TENANT
                + "' AND (base_caja IS NOT NULL OR pin_registro_caja_hash IS NOT NULL)")).isZero();

        MvcResult putAdmin = configurarCaja("admin", "{\"baseCaja\":200000,\"pin\":\"" + PIN + "\",\"maxRegistrosCajaPorDia\":40}");
        assertThat(putAdmin.getResponse().getStatus()).isEqualTo(200);
        String respuesta = putAdmin.getResponse().getContentAsString();
        assertThat(respuesta).doesNotContain(PIN);
        JsonNode caja = leer(putAdmin);
        assertThat(caja.get("baseCaja").decimalValue()).isEqualByComparingTo("200000");
        assertThat(caja.get("tienePin").asBoolean()).isTrue();
        assertThat(caja.get("maxRegistrosCajaPorDia").asInt()).isEqualTo(40);

        // Guardado con hash, no en claro.
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT pin_registro_caja_hash FROM sites WHERE tenant_id = '" + TENANT + "'")) {
            rs.next();
            assertThat(rs.getString(1)).isNotEqualTo(PIN).startsWith("$2");
        }

        // El POS lee baseCaja y tienePinRegistro en /mode; /account/sites no suelta el hash.
        JsonNode modo = leer(mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer("cajero"))).andReturn());
        assertThat(modo.get("baseCaja").decimalValue()).isEqualByComparingTo("200000");
        assertThat(modo.get("tienePinRegistro").asBoolean()).isTrue();
        String sedes = mockMvc.perform(get("/account/sites").header("Authorization", bearer("cajero")))
                .andReturn().getResponse().getContentAsString();
        assertThat(sedes).doesNotContain("pinRegistroCajaHash").doesNotContain("$2");

        // PIN corto: 400 con campo. PIN vacío: lo quita.
        MvcResult corto = configurarCaja("admin", "{\"pin\":\"12\"}");
        assertThat(corto.getResponse().getStatus()).isEqualTo(400);
        assertThat(leer(corto).get("campo").asText()).isEqualTo("pin");
        MvcResult quitar = configurarCaja("admin", "{\"pin\":\"\"}");
        assertThat(leer(quitar).get("tienePin").asBoolean()).isFalse();
        assertThat(leer(mockMvc.perform(get("/account/sites/mode").header("Authorization", bearer("cajero"))).andReturn())
                .get("tienePinRegistro").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("🔴 5 claves incorrectas en 10 minutos → la 6.ª petición es 429 DEMASIADOS_INTENTOS aunque el PIN sea correcto; pasada la ventana, vuelve a aceptar")
    void demasiadasClavesIncorrectas() throws Exception {
        ponerPin();
        for (int i = 1; i <= 5; i++) {
            MvcResult malo = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", "000" + i, null));
            assertThat(malo.getResponse().getStatus()).as("fallo " + i).isEqualTo(403);
            assertThat(leer(malo).get("codigo").asText()).isEqualTo("PIN_INCORRECTO");
            reloj.avanzar(Duration.ofSeconds(30));
        }

        // La 6.ª: con el PIN BUENO, y aun así 429 — el PIN ni se evalúa.
        MvcResult bloqueada = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", PIN, null));
        assertThat(bloqueada.getResponse().getStatus()).isEqualTo(429);
        JsonNode e = leer(bloqueada);
        assertThat(e.get("codigo").asText()).isEqualTo("DEMASIADOS_INTENTOS");
        assertThat(e.get("error").asText()).isEqualTo(LimiteDeClavesDeRegistro.MENSAJE);
        assertThat(e.get("mensaje").asText())
                .isEqualTo("Demasiadas claves incorrectas. Espera 10 minutos o pide al administrador que revise la clave");
        assertThat(bloqueada.getResponse().getHeader("Retry-After")).isNotBlank();
        // Cualquier petición de registro, incluso con el cuerpo mal hecho.
        assertThat(registrar(cuerpo("", 0, "", PIN, null)).getResponse().getStatus()).isEqualTo(429);
        assertThat(contar("SELECT count(*) FROM menu_products WHERE tenant_id = '" + TENANT + "'")).isZero();

        // Pasada la ventana (10 minutos desde el primer fallo), el PIN bueno vuelve a entrar.
        reloj.avanzar(Duration.ofMinutes(10));
        MvcResult despues = registrar(cuerpo("Jabón Rey", 2800, "7702310020011", PIN, null));
        assertThat(despues.getResponse().getStatus()).as(despues.getResponse().getContentAsString()).isEqualTo(201);
    }

    @Test
    @DisplayName("un acierto reinicia la cuenta de claves incorrectas, y un PIN nuevo del administrador también")
    void unAciertoReinicia() throws Exception {
        ponerPin();
        for (int i = 1; i <= 4; i++) {
            assertThat(registrar(cuerpo("X", 1000, "C" + i, "9999", null)).getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(registrar(cuerpo("Uno", 1000, "B1", PIN, null)).getResponse().getStatus()).isEqualTo(201);
        // Otros cuatro fallos: si el acierto no hubiera reiniciado, serían 8 y el siguiente sería 429.
        for (int i = 5; i <= 8; i++) {
            assertThat(registrar(cuerpo("X", 1000, "C" + i, "9999", null)).getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(registrar(cuerpo("Dos", 1000, "B2", PIN, null)).getResponse().getStatus()).isEqualTo(201);

        // Bloqueado del todo, el administrador pone un PIN nuevo y la caja vuelve a registrar.
        for (int i = 9; i <= 13; i++) {
            registrar(cuerpo("X", 1000, "C" + i, "9999", null));
        }
        assertThat(registrar(cuerpo("Tres", 1000, "B3", PIN, null)).getResponse().getStatus()).isEqualTo(429);
        assertThat(configurarCaja("admin", "{\"pin\":\"7310\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(registrar(cuerpo("Tres", 1000, "B3", "7310", null)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("🔴 GET registrados-en-caja: solo los del negocio nacidos en la caja, lo más reciente primero, con quién y cuándo, máximo 200")
    void registradosEnCaja() throws Exception {
        String otro = "otro-negocio-registro";
        ponerPin();
        assertThat(registrar(cuerpo("Uno", 1000, "R1", PIN, null)).getResponse().getStatus()).isEqualTo(201);
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + otro + "','O','pro') ON CONFLICT (id) DO NOTHING");
            s.execute("DELETE FROM menu_products WHERE tenant_id = '" + otro + "'");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, creado_en_caja_por, creado_en_caja_en) VALUES "
                    + "('" + TENANT + "-viejo','" + TENANT + "','Viejo',1000,true,'cajera@x', now() - interval '1 day'),"
                    + "('" + TENANT + "-del-panel','" + TENANT + "','Del panel',1000,true,NULL,NULL),"
                    + "('" + otro + "-ajeno','" + otro + "','Ajeno',1000,true,'cajera@otro', now())");
        }

        MvcResult r = mockMvc.perform(get("/api/menu/products/registrados-en-caja")
                .header("Authorization", bearer("cajero"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode lista = leer(r);
        assertThat(lista).hasSize(2);
        assertThat(lista.get(0).get("productoId").asText()).isEqualTo(TENANT + "-uno");
        assertThat(lista.get(0).get("nombre").asText()).isEqualTo("Uno");
        assertThat(lista.get(0).get("creadoEnCajaPor").asText()).isEqualTo("cajero@" + TENANT);
        assertThat(lista.get(0).get("creadoEnCajaEn").asText()).isNotBlank();
        assertThat(lista.get(1).get("productoId").asText()).isEqualTo(TENANT + "-viejo");
        assertThat(iterable(lista.get(0))).containsExactly("productoId", "nombre", "creadoEnCajaPor", "creadoEnCajaEn");

        // Tope de 200.
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, creado_en_caja_en) "
                    + "SELECT '" + TENANT + "-lote-' || g, '" + TENANT + "', 'Lote ' || g, 1000, true, now() - make_interval(hours => g) "
                    + "FROM generate_series(1, 205) g");
        }
        assertThat(leer(mockMvc.perform(get("/api/menu/products/registrados-en-caja")
                .header("Authorization", bearer("cajero"))).andReturn())).hasSize(200);
    }

    @Test
    @DisplayName("el id sigue la convención del panel (slugDe): sin tildes, minúsculas, guiones, con el negocio delante")
    void slugComoElPanel() {
        assertThat(RegistroRapidoEnCaja.slugDe("Café Águila 250 g", "shark-burger")).isEqualTo("shark-burger-cafe-aguila-250-g");
        assertThat(RegistroRapidoEnCaja.slugDe("  ¡Ñandú!  (x2) ", "qa-epsilon-v38")).isEqualTo("qa-epsilon-v38-nandu-x2");
        assertThat(RegistroRapidoEnCaja.slugDe("General", "negocio-registro")).isEqualTo("negocio-registro-general");
        // Recortes del panel: el nombre a 60 y el negocio a 30, sin volver a quitar el guion final.
        assertThat(RegistroRapidoEnCaja.slugDe("a".repeat(59) + " b", "x")).isEqualTo("x-" + "a".repeat(59) + "-");
        assertThat(RegistroRapidoEnCaja.slugDe("Pan", "n".repeat(40))).isEqualTo("n".repeat(30) + "-pan");
    }
}
