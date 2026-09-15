package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suresell.orders.application.usecase.NormalizacionDeBusqueda;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F5.8e: {@code GET /api/menu/products/buscar}. La aplicación corre con el DUEÑO de la base (RLS saltada), así que el
 * otro negocio solo queda fuera por el filtro escrito.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class BusquedaDeProductosTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "busqueda-propio";
    static final String OTRO = "busqueda-ajeno";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
        r.add("security.jwt.secret", () -> SECRET);
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    static boolean sembrado = false;

    private static String bearer(String tenant) {
        return "Bearer " + Jwts.builder().subject("admin@" + tenant + ".invalid").claim("tenant_id", tenant).claim("role", "admin")
                .claim("modules", List.of("ventas", "mayorista"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() throws Exception {
        if (sembrado) {
            return;
        }
        sembrado = true;
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String t : new String[] {T, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','B','pro') ON CONFLICT (id) DO NOTHING");
            }
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES "
                    + "('bp-cafe','" + T + "','Café molido x500',18000,true),"
                    + "('bp-cafeina','" + T + "','Bebida con cafeína',5000,true),"
                    + "('bp-descafe','" + T + "','Descafeinado',9000,true),"
                    + "('bp-nino','" + T + "','Pañal Niño etapa 3',30000,true),"
                    + "('bp-inactivo','" + T + "','Café viejo inactivo',1000,false),"
                    + "('bp-codigo','" + T + "','Arroz',4000,true),"
                    + "('bp-prefijo','" + T + "','Azúcar',3000,true),"
                    + "('ba-cafe','" + OTRO + "','Café ajeno',1,true)");
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) VALUES "
                    + "('" + T + "','7701234','bp-codigo','panel'), ('" + T + "','77012345','bp-prefijo','panel'), ('" + T + "','7709999','bp-inactivo','panel'), ('" + OTRO + "','7701234','ba-cafe','panel')");
            // 60 «Tornillo» para el tope.
            StringBuilder b = new StringBuilder("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES ");
            for (int i = 1; i <= 60; i++) {
                b.append(i > 1 ? "," : "").append("('bp-t").append(String.format("%02d", i)).append("','").append(T)
                        .append("','Tornillo ").append(String.format("%02d", i)).append("',100,true)");
            }
            s.execute(b.toString());
        }
    }

    private ResultActions buscar(String query) throws Exception {
        return mockMvc.perform(get("/api/menu/products/buscar" + query).header("Authorization", bearer(T)));
    }

    private List<String> ids(String query) throws Exception {
        JsonNode r = json.readTree(buscar(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        r.forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("🔴 F5.8e: por nombre sin tildes ni mayúsculas; prefijo de nombre antes que «contiene»; sin inactivos ni otro negocio")
    void porNombre() throws Exception {
        assertThat(ids("?q=CAFE")).containsExactly("bp-cafe", "bp-cafeina", "bp-descafe");
        assertThat(ids("?q=nino")).containsExactly("bp-nino");
        assertThat(ids("?q=pañal")).containsExactly("bp-nino");
    }

    @Test
    @DisplayName("🔴 F5.8e: el código exacto va primero, luego el prefijo de código; el código del otro negocio no aparece")
    void porCodigo() throws Exception {
        assertThat(ids("?q=7701234")).containsExactly("bp-codigo", "bp-prefijo");
        assertThat(ids("?q=7709999")).as("el código de un producto inactivo no lo trae").isEmpty();
        buscar("?q=7701234").andExpect(jsonPath("$[0].nombre").value("Arroz")).andExpect(jsonPath("$[0].codigos[0].codigo").value("7701234"))
                .andExpect(jsonPath("$[0].precio").doesNotExist());
    }

    @Test
    @DisplayName("🔴 F5.8e: limit 20 por defecto y 50 como máximo, en orden estable por nombre")
    void tope() throws Exception {
        List<String> defecto = ids("?q=tornillo");
        assertThat(defecto).hasSize(20).startsWith("bp-t01").endsWith("bp-t20");
        assertThat(ids("?q=tornillo&limit=500")).hasSize(50).endsWith("bp-t50");
        assertThat(ids("?q=tornillo&limit=5")).containsExactly("bp-t01", "bp-t02", "bp-t03", "bp-t04", "bp-t05");
    }

    @Test
    @DisplayName("🔴 F5.8e: q de menos de 3 caracteres → 400 campo q; comodines de LIKE no son comodines")
    void validaciones() throws Exception {
        buscar("?q=ca").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("q"));
        buscar("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("q"));
        assertThat(ids("?q=%25%25%25")).isEmpty();
        assertThat(ids("?q=___")).isEmpty();
    }

    @Test
    @DisplayName("control negativo: lo que no existe no devuelve nada, y el otro negocio encuentra solo lo suyo")
    void controles() throws Exception {
        assertThat(ids("?q=inexistente")).isEmpty();
        JsonNode ajeno = json.readTree(mockMvc.perform(get("/api/menu/products/buscar?q=cafe").header("Authorization", bearer(OTRO)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ajeno).hasSize(1);
        assertThat(ajeno.get(0).get("id").asText()).isEqualTo("ba-cafe");
    }

    @Test
    @DisplayName("🔴 F5.8e: la normalización de Java y la expresión SQL dan lo mismo para nombres con tildes y ñ")
    void javaYSqlNormalizanIgual() throws Exception {
        List<String> nombres = List.of("Café", "CAFÉ", "Pañal Niño", "ÑANDÚ", "Azúcar Morena", "Pingüino", "Ágil Éxito Íntimo Óvalo Último",
                "àèìòù ÀÈÌÒÙ", "Ärger Öl Über ëï", "Tornillo 3/8 x 1\"", "sin marcas");
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT " + NormalizacionDeBusqueda.sql("?::text"))) {
            for (String n : nombres) {
                ps.setString(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).as(n).isEqualTo(NormalizacionDeBusqueda.normalizar(n));
                }
            }
        }
        assertThat(NormalizacionDeBusqueda.CON_MARCA).hasSameSizeAs(NormalizacionDeBusqueda.SIN_MARCA);
        assertThat(NormalizacionDeBusqueda.normalizar("Pañal NIÑO")).isEqualTo("panal nino");
    }
}
