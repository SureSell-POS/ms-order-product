package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan de mayoristas F1.6 por la API real: el cliente ampliado (V62), su historia
 * con autor en {@code clientes_eventos}, y el vendedor viendo solo sus clientes,
 * filtrado en el servidor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class ClientesDelMayoristaTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-clientes-may";
    static final String ADMIN = "admin@qa-clientes-may.invalid";
    static final String ANA = "ana@qa-clientes-may.invalid";
    static final String PEDRO = "pedro@qa-clientes-may.invalid";
    static final String CAJA = "caja@qa-clientes-may.invalid";

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
    private JdbcTemplate dueno;
    private long admin;
    private long ana;
    private long pedro;

    private static String bearer(String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id IN (?, 'qa-clientes-may-otro')", T);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM users WHERE tenant_id = ?", T);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", T, T);
        admin = usuario(ADMIN, "admin");
        ana = usuario(ANA, "vendedor");
        pedro = usuario(PEDRO, "vendedor");
        usuario(CAJA, "cajero");
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, creado_por) VALUES "
                + "(?, '100', 'Tienda de Ana', ?, 's'), (?, '200', 'Tienda de Pedro', ?, 's'), (?, '300', 'Sin vendedor', NULL, 's')",
                T, ana, T, pedro, T);
    }

    private long usuario(String email, String rol) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role) VALUES (?, '!', ?, ?) RETURNING id",
                Long.class, email, T, rol);
    }

    @Test
    @DisplayName("🔴 un vendedor ve solo sus clientes, aunque pida los de otro; admin y cajero ven todos y filtran")
    void elVendedorVeLosSuyos() throws Exception {
        mockMvc.perform(get("/api/mayorista/clientes").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("100"));
        mockMvc.perform(get("/api/mayorista/clientes").param("vendedorId", String.valueOf(pedro))
                        .header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("100"));
        mockMvc.perform(get("/api/mayorista/clientes").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/mayorista/clientes").param("vendedorId", String.valueOf(pedro))
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documento").value("200"));
        // La ficha de un cliente de otro vendedor no existe para Ana.
        mockMvc.perform(get("/api/mayorista/clientes/200").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/mayorista/clientes/100").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Tienda de Ana"));
    }

    @Test
    @DisplayName("🔴 editar un cliente por la API deja un evento por campo cambiado, CON autor")
    void editarDejaHistoriaConAutor() throws Exception {
        mockMvc.perform(put("/api/mayorista/clientes/300").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Sin vendedor\",\"tipoCliente\":\"dulceria\",\"direccionEntrega\":\"Calle 1 # 2-3\",\"plazoDias\":8,"
                                + "\"vendedorId\":" + ana + ",\"exigeFactura\":true,\"municipioDane\":\"05001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo_cliente").value("DULCERIA"))
                .andExpect(jsonPath("$.direccion_entrega").value("Calle 1 # 2-3"))
                .andExpect(jsonPath("$.vendedor").doesNotExist());

        List<Map<String, Object>> eventos = dueno.queryForList(
                "SELECT e.campo, e.valor_anterior, e.valor_nuevo, e.usuario_id FROM clientes_eventos e "
                        + "JOIN clientes c ON c.id = e.cliente_id WHERE c.tenant_id = ? AND c.documento = '300' ORDER BY e.campo", T);
        assertThat(eventos).extracting(e -> e.get("campo"))
                .containsExactly("direccion_entrega", "exige_factura", "municipio_dane", "plazo_dias", "tipo_cliente", "vendedor_id");
        assertThat(eventos).allSatisfy(e -> assertThat(((Number) e.get("usuario_id")).longValue()).isEqualTo(admin));
        assertThat(eventos).filteredOn(e -> "plazo_dias".equals(e.get("campo")))
                .singleElement().satisfies(e -> assertThat(e.get("valor_nuevo")).isEqualTo("8"));

        // El POST de siempre sobre un cliente existente también deja autor.
        mockMvc.perform(post("/api/mayorista/clientes").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documento\":\"300\",\"nombre\":\"Sin vendedor renombrada\",\"plazoDias\":8}"))
                .andExpect(status().isCreated());
        assertThat(dueno.queryForObject("SELECT usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'nombre'",
                Long.class, T)).isEqualTo(admin);
    }

    @Test
    @DisplayName("desactivar no borra: activo=false con su evento; y solo el admin edita o desactiva")
    void desactivarYPermisos() throws Exception {
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ANA, "vendedor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plazoDias\":30}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes WHERE tenant_id = ? AND documento = '100'",
                Integer.class, T)).isEqualTo(1);
        assertThat(dueno.queryForObject("SELECT valor_nuevo FROM clientes_eventos WHERE tenant_id = ? AND campo = 'activo'",
                String.class, T)).isEqualTo("false");
    }

    @Test
    @DisplayName("🔴 datos fuera de catálogo: 400 con campo, y la fila no cambia")
    void catalogosCerrados() throws Exception {
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tipoCliente\":\"OTRO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("tipoCliente"));
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"municipioDane\":\"Medellin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("municipioDane"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 un cambio que llega sin app.user_id se guarda, queda sin autor y AVISA en el log de la base")
    void sinAutorAvisa() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_user", "app_pw");
             Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + T + "'");
            st.executeUpdate("UPDATE clientes SET plazo_dias = 15 WHERE documento = '100'");
            SQLWarning aviso = st.getWarnings();
            // SQLWarning es Throwable e Iterable a la vez: se afirma sobre su texto.
            String texto = aviso == null ? null : aviso.getMessage();
            assertThat(texto).isNotNull().contains("clientes_eventos sin autor");
        }
        assertThat(dueno.queryForObject("SELECT usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'plazo_dias'",
                Long.class, T)).isNull();
    }

    private static final String TODO_EL_FORMULARIO_DE_ANA = "{\"nombre\":\"Tienda de Ana\",\"telefono\":\"3001112233\","
            + "\"correo\":\"ana@tienda.invalid\",\"direccionEntrega\":\"Cra 7 # 1-1\",\"plazoDias\":15,"
            + "\"exigeFactura\":true,\"vendedorId\":%d}";

    @Test
    @DisplayName("🔴 F1.11: el PUT es reemplazo completo: null vacía (con su evento a NULL), nombre obligatorio, lista de otro negocio no")
    void putReemplazoCompleto() throws Exception {
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(TODO_EL_FORMULARIO_DE_ANA, ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("ana@tienda.invalid"))
                .andExpect(jsonPath("$.en_insolvencia_desde").isEmpty());
        // El mismo formulario sin correo, sin dirección, sin plazo y sin vendedor: quedan vacíos.
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Tienda de Ana\",\"telefono\":\"3001112233\",\"correo\":\"  \","
                                + "\"exigeFactura\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").isEmpty())
                .andExpect(jsonPath("$.direccion_entrega").isEmpty())
                .andExpect(jsonPath("$.plazo_dias").isEmpty())
                .andExpect(jsonPath("$.vendedor_id").isEmpty())
                .andExpect(jsonPath("$.telefono").value("3001112233"));
        List<Map<String, Object>> vaciados = dueno.queryForList(
                "SELECT campo, valor_nuevo FROM clientes_eventos WHERE tenant_id = ? AND valor_nuevo IS NULL ORDER BY campo", T);
        assertThat(vaciados).extracting(e -> e.get("campo"))
                .containsExactly("correo", "direccion_entrega", "plazo_dias", "vendedor_id");

        int antes = dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T);
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"telefono\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("nombre"));
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES ('qa-clientes-may-otro', 'otro', 'pro') ON CONFLICT (id) DO NOTHING");
        dueno.update("DELETE FROM listas_precio WHERE tenant_id = 'qa-clientes-may-otro' AND codigo = 'ajena'");
        String listaAjena = dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) "
                + "VALUES ('qa-clientes-may-otro', 'ajena', 'Ajena', 's') RETURNING id::text", String.class);
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Tienda de Ana\",\"listaPrecioId\":\"" + listaAjena + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("listaPrecioId"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T))
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("F1.11: reactivar es simétrico a desactivar; reactivar uno activo responde 200 sin evento; solo admin")
    void reactivar() throws Exception {
        mockMvc.perform(post("/api/mayorista/clientes/100/reactivar").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(false));
        mockMvc.perform(post("/api/mayorista/clientes/100/reactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
        mockMvc.perform(post("/api/mayorista/clientes/100/reactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
        assertThat(dueno.queryForList("SELECT valor_nuevo FROM clientes_eventos WHERE tenant_id = ? AND campo = 'activo' "
                + "ORDER BY ocurrido_en, valor_nuevo DESC", String.class, T)).containsExactly("false", "true");
        // La fecha de insolvencia viaja como fecha ISO (YYYY-MM-DD), en la lista y en la ficha: el POS la cachea así.
        dueno.update("UPDATE clientes SET en_insolvencia_desde = DATE '2026-09-01' WHERE tenant_id = ? AND documento = '100'", T);
        mockMvc.perform(get("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.en_insolvencia_desde").value("2026-09-01"));
        mockMvc.perform(get("/api/mayorista/clientes").param("q", "Tienda de Ana").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].en_insolvencia_desde").value("2026-09-01"));
        mockMvc.perform(post("/api/mayorista/clientes/999/reactivar").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
    }

    @Test
    @DisplayName("🔴 F1.11: el historial pagina por cursor sin saltar ni repetir filas del mismo instante, y no ve otro negocio")
    void historialPorCursor() throws Exception {
        // Un solo PUT escribe 6 eventos con el MISMO ocurrido_en: el caso en que un cursor por fecha sola falla.
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(TODO_EL_FORMULARIO_DE_ANA, pedro)))
                .andExpect(status().isOk());
        // Otro negocio con el mismo documento y su propia historia.
        String otro = "qa-clientes-may-otro";
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", otro);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ?", otro);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", otro, otro);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES (?, '100', 'Ajena', 's')", otro);
        dueno.update("UPDATE clientes SET nombre = 'Ajena 2', telefono = '9' WHERE tenant_id = ? AND documento = '100'", otro);

        java.util.Set<String> vistos = new java.util.HashSet<>();
        String cursor = null;
        int paginas = 0;
        do {
            var peticion = get("/api/mayorista/clientes/100/eventos").param("limite", "4")
                    .header("Authorization", bearer(ADMIN, "admin"));
            if (cursor != null) {
                peticion.param("antesDe", cursor);
            }
            String cuerpo = mockMvc.perform(peticion).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cuerpo);
            for (var e : json.get("eventos")) {
                assertThat(vistos.add(e.get("id").asText())).as("fila repetida entre páginas").isTrue();
                assertThat(e.get("usuario").asText()).isEqualTo(ADMIN);
                assertThat(e.has("campo") && e.has("valor_anterior") && e.has("valor_nuevo") && e.has("ocurrido_en")).isTrue();
            }
            cursor = json.get("siguiente").isNull() ? null : json.get("siguiente").asText();
            paginas++;
        } while (cursor != null && paginas < 10);
        assertThat(vistos).hasSize(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T));
        assertThat(vistos).hasSize(6);
        assertThat(paginas).isEqualTo(2);

        mockMvc.perform(get("/api/mayorista/clientes/100/eventos").header("Authorization", bearer(ANA, "vendedor")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mayorista/clientes/100/eventos").param("limite", "201")
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("limite"));
        mockMvc.perform(get("/api/mayorista/clientes/100/eventos").param("antesDe", "ayer")
                        .header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("antesDe"));
    }

    private org.springframework.test.web.servlet.ResultActions alta(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/mayorista/clientes").header("Authorization", bearer(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    @Test
    @DisplayName("🔴 F4.10: el NIT llega con o sin DV; se separa, se valida si vino y el DV sale en la lectura")
    void nitConDigitoDeVerificacion() throws Exception {
        // Con guion y sin tipo: se toma por NIT.
        alta("{\"documento\":\"800.197.268-4\",\"nombre\":\"DIAN\"}").andExpect(status().isCreated());
        mockMvc.perform(get("/api/mayorista/clientes/800197268").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documento").value("800197268"))
                .andExpect(jsonPath("$.dv").value(4))
                .andExpect(jsonPath("$.tipo_documento").value("NIT"));
        // Diez dígitos sin guion con tipo NIT, cuyo último dígito coincide con el DV de los nueve primeros (1):
        // NO se corta; se guarda entero y el DV se calcula sobre los diez.
        alta("{\"documento\":\"8999990681\",\"nombre\":\"Persona natural\",\"tipoDocumento\":\"NIT\"}")
                .andExpect(status().isCreated());
        // Sin DV: lo calcula el servidor.
        alta("{\"documento\":\"860034313\",\"nombre\":\"Bavaria\",\"tipoDocumento\":\"nit\"}").andExpect(status().isCreated());
        assertThat(dueno.queryForList("SELECT documento || '-' || dv FROM clientes WHERE tenant_id = ? AND dv IS NOT NULL ORDER BY documento",
                String.class, T)).containsExactly("800197268-4", "860034313-7", "8999990681-" + Nit.dv("8999990681"));

        // DV errado: 400 en documento, y nada escrito.
        alta("{\"documento\":\"890903938-5\",\"nombre\":\"Bancolombia\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("documento"))
                .andExpect(jsonPath("$.message").value("El dígito de verificación no corresponde"));
        // Un CC no lleva DV.
        alta("{\"documento\":\"1020304-5\",\"nombre\":\"Persona\",\"tipoDocumento\":\"CC\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes WHERE tenant_id = ? AND documento IN ('890903938', '1020304-5', '1020304')",
                Integer.class, T)).isZero();

        // PUT: marcar un cliente como NIT calcula su DV; volver a CC lo quita. El panel no manda `dv`.
        String formulario = "{\"nombre\":\"Sin vendedor\",\"tipoDocumento\":\"%s\"}";
        mockMvc.perform(put("/api/mayorista/clientes/300").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(formulario, "NIT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dv").value(Nit.dv("300")));
        mockMvc.perform(put("/api/mayorista/clientes/300").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(String.format(formulario, "CC")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dv").isEmpty());
        assertThat(dueno.queryForList("SELECT valor_nuevo FROM clientes_eventos e JOIN clientes c ON c.id = e.cliente_id "
                + "WHERE c.tenant_id = ? AND c.documento = '300' AND e.campo = 'dv' ORDER BY e.ocurrido_en", String.class, T))
                .containsExactly(String.valueOf(Nit.dv("300")), null);
    }

    @Test
    @DisplayName("🔴 un token sin rol no edita, no reactiva ni lee el historial de un cliente: 403 y nada escrito")
    void sinRol() throws Exception {
        String sinRol = "Bearer " + Jwts.builder().subject(ADMIN).claim("tenant_id", T)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        mockMvc.perform(put("/api/mayorista/clientes/100").header("Authorization", sinRol)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Cambiado\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/mayorista/clientes/100/desactivar").header("Authorization", sinRol)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/mayorista/clientes/100/reactivar").header("Authorization", sinRol)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mayorista/clientes/100/eventos").header("Authorization", sinRol)).andExpect(status().isForbidden());
        assertThat(dueno.queryForObject("SELECT nombre FROM clientes WHERE tenant_id = ? AND documento = '100'", String.class, T))
                .isEqualTo("Tienda de Ana");
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ?", Integer.class, T)).isZero();
    }

    private String lista(String negocio, String codigo) {
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", negocio, negocio);
        dueno.update("UPDATE clientes SET lista_precio_id = NULL WHERE lista_precio_id IN (SELECT id FROM listas_precio WHERE tenant_id = ? AND codigo = ?)", negocio, codigo);
        dueno.update("DELETE FROM listas_precio WHERE tenant_id = ? AND codigo = ?", negocio, codigo);
        return dueno.queryForObject("INSERT INTO listas_precio (tenant_id, codigo, nombre, creado_por) VALUES (?, ?, ?, 's') RETURNING id::text",
                String.class, negocio, codigo, codigo);
    }

    private org.springframework.test.web.servlet.ResultActions asignar(String quien, String rol, String documento, String listaId) throws Exception {
        return mockMvc.perform(put("/api/mayorista/clientes/" + documento + "/lista").header("Authorization", bearer(quien, rol))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listaPrecioId\":" + (listaId == null ? "null" : "\"" + listaId + "\"") + "}"));
    }

    @Test
    @DisplayName("🔴 asignar lista: toca solo esa columna (lo editado entre medias no se pierde), deja evento con autor, la misma → 200 sin evento")
    void asignarLista() throws Exception {
        String mayoreo = lista(T, "mayoreo");
        // Alguien edita el teléfono DESPUÉS de que la pantalla de la lista leyera la ficha.
        dueno.update("UPDATE clientes SET telefono = '3009990000' WHERE tenant_id = ? AND documento = '100'", T);
        asignar(ADMIN, "admin", "100", mayoreo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lista_precio_id").value(mayoreo))
                .andExpect(jsonPath("$.lista").value("mayoreo"))
                .andExpect(jsonPath("$.telefono").value("3009990000"));
        assertThat(dueno.queryForMap("SELECT valor_nuevo, usuario_id FROM clientes_eventos WHERE tenant_id = ? AND campo = 'lista_precio_id'", T))
                .containsEntry("valor_nuevo", mayoreo).containsEntry("usuario_id", admin);
        asignar(ADMIN, "admin", "100", mayoreo).andExpect(status().isOk());
        asignar(ADMIN, "admin", "100", null).andExpect(status().isOk()).andExpect(jsonPath("$.lista_precio_id").isEmpty());
        assertThat(dueno.queryForList("SELECT valor_nuevo FROM clientes_eventos WHERE tenant_id = ? AND campo = 'lista_precio_id' ORDER BY ocurrido_en",
                String.class, T)).containsExactly(mayoreo, null);
    }

    @Test
    @DisplayName("🔴 asignar lista: de otro negocio → 400, cliente inactivo → 409 CLIENTE_INACTIVO, no admin → 403; nada escrito")
    void asignarListaRestrictiva() throws Exception {
        String ajena = lista("qa-clientes-may-otro", "ajena-lista");
        String mia = lista(T, "mia");
        asignar(ADMIN, "admin", "100", ajena).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("listaPrecioId"));
        asignar(CAJA, "cajero", "100", mia).andExpect(status().isForbidden());
        asignar(ANA, "vendedor", "100", mia).andExpect(status().isForbidden());
        dueno.update("UPDATE clientes SET activo = false WHERE tenant_id = ? AND documento = '200'", T);
        asignar(ADMIN, "admin", "200", mia).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CLIENTE_INACTIVO"));
        asignar(ADMIN, "admin", "999", mia).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("documento"));
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes WHERE tenant_id = ? AND lista_precio_id IS NOT NULL", Integer.class, T)).isZero();
        assertThat(dueno.queryForObject("SELECT count(*) FROM clientes_eventos WHERE tenant_id = ? AND campo = 'lista_precio_id'", Integer.class, T)).isZero();
    }

    @Test
    @DisplayName("🔴 sin RLS (el dueño la salta): el filtro de negocio ESCRITO impide tocar al cliente de otro negocio con el mismo documento")
    void asignarListaSinRls() {
        String otro = "qa-clientes-may-otro";
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", otro, otro);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ? AND documento = '100'", otro);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, creado_por) VALUES (?, '100', 'La del otro', 's')", otro);
        String mia = lista(T, "mia-sin-rls");
        String ajena = lista(otro, "ajena-sin-rls");
        ListasDePrecio sinRls = new ListasDePrecio(dueno);
        sinRls.asignarLista(T, "100", java.util.UUID.fromString(mia), new ListasDePrecio.Autor(ADMIN, admin));
        assertThat(dueno.queryForObject("SELECT lista_precio_id::text FROM clientes WHERE tenant_id = ? AND documento = '100'", String.class, otro))
                .as("el cliente del otro negocio con el mismo documento no cambia").isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sinRls.asignarLista(T, "100", java.util.UUID.fromString(ajena), new ListasDePrecio.Autor(ADMIN, admin)))
                .isInstanceOf(com.suresell.orders.shared.exception.DatoInvalidoException.class);
        assertThat(dueno.queryForObject("SELECT lista_precio_id::text FROM clientes WHERE tenant_id = ? AND documento = '100'", String.class, T))
                .isEqualTo(mia);
    }
}
