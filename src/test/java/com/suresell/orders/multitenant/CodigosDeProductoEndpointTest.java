package com.suresell.orders.multitenant;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V51 por la API real: el catálogo del POS trae `codigos` dentro de cada
 * producto (aditivo: los campos de siempre siguen igual), y los tres
 * endpoints mínimos de gestión responden con el contrato de error de la ola 4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CodigosDeProductoEndpointTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-codigos";
    static final String OTRO = "otro-negocio-codigos";

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

    private String bearer(String tenant) {
        return "Bearer " + Jwts.builder()
                .subject("cajera@" + tenant)
                .claim("tenant_id", tenant)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM codigos_de_producto");
            s.execute("DELETE FROM menu_products WHERE tenant_id IN ('" + TENANT + "','" + OTRO + "')");
            s.execute("DELETE FROM menu_categories WHERE tenant_id IN ('" + TENANT + "','" + OTRO + "')");
            for (String t : new String[]{TENANT, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','C','pro') ON CONFLICT (id) DO NOTHING");
                s.execute("INSERT INTO menu_categories (id_category, tenant_id, name_category) VALUES ('CAT-" + t + "','" + t + "','Granos')");
                s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, category_id) "
                        + "VALUES ('ARROZ-" + t + "','" + t + "','Arroz Diana 500 g',2500,true,'CAT-" + t + "')");
            }
        }
    }

    @Test
    @DisplayName("🔴 el catálogo trae `codigos` por producto: vacío sin códigos, con ellos después, y lo de siempre no cambia")
    void catalogoConCodigos() throws Exception {
        // Sin códigos: el campo existe y está vacío; los campos viejos siguen ahí.
        mockMvc.perform(get("/api/menu/categories-with-products").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idCategory").value("CAT-" + TENANT))
                .andExpect(jsonPath("$[0].products[0].idProduct").value("ARROZ-" + TENANT))
                .andExpect(jsonPath("$[0].products[0].nameProduct").value("Arroz Diana 500 g"))
                .andExpect(jsonPath("$[0].products[0].price").value(2500))
                .andExpect(jsonPath("$[0].products[0].codigos", hasSize(0)));

        // Se añade un EAN y un PLU de caja.
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\" 7702001234567 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigo").value("7702001234567"))
                .andExpect(jsonPath("$.cantidad").value(1))
                .andExpect(jsonPath("$.tipo").value("EAN"));
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"17702001234564\",\"cantidad\":12,\"tipo\":\"ean\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cantidad").value(12))
                .andExpect(jsonPath("$.tipo").value("EAN"));

        // El catálogo los trae dentro del producto; el otro negocio no los ve.
        mockMvc.perform(get("/api/menu/categories-with-products").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].products[0].codigos", hasSize(2)))
                .andExpect(jsonPath("$[0].products[0].codigos[0].codigo").value("7702001234567"))
                .andExpect(jsonPath("$[0].products[0].codigos[1].cantidad").value(12));
        mockMvc.perform(get("/api/menu/products").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigos", hasSize(2)));
        mockMvc.perform(get("/api/menu/categories-with-products").header("Authorization", bearer(OTRO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].products[0].codigos", hasSize(0)));

        // El mismo EAN en el otro negocio: 201, son dos filas.
        mockMvc.perform(post("/api/menu/products/ARROZ-" + OTRO + "/codigos")
                        .header("Authorization", bearer(OTRO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"7702001234567\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("🔴 los rechazos llevan el contrato {error, message, campo?}: repetido 409, tipo malo 400, producto ajeno 404")
    void rechazosConContrato() throws Exception {
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"11\",\"tipo\":\"PLU\"}"))
                .andExpect(status().isCreated());

        // Repetido en el mismo negocio: 409 YA_EXISTE (23505 de la base).
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"11\",\"tipo\":\"PLU\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("YA_EXISTE"))
                .andExpect(jsonPath("$.message").isString());

        // Tipo fuera del enum: 400 con el campo.
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"12\",\"tipo\":\"OTRO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAMPO_INVALIDO"))
                .andExpect(jsonPath("$.campo").value("tipo"));

        // Código vacío: 400 con el campo (validación del cuerpo).
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("codigo"));

        // El producto del OTRO negocio no existe para este: 404, y no se crea nada.
        mockMvc.perform(post("/api/menu/products/ARROZ-" + OTRO + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"13\",\"tipo\":\"PLU\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_EXISTE"));
        mockMvc.perform(get("/api/menu/products/ARROZ-" + OTRO + "/codigos").header("Authorization", bearer(OTRO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("🔴 retirar cierra el código: deja de salir en el catálogo, retirarlo otra vez es 404, y se puede volver a dar de alta")
    void retirarCierraNoBorra() throws Exception {
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"7702009999990\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/menu/products/ARROZ-" + TENANT + "/codigos/7702009999990")
                        .header("Authorization", bearer(TENANT)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/menu/products/ARROZ-" + TENANT + "/codigos/7702009999990")
                        .header("Authorization", bearer(TENANT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/menu/products/ARROZ-" + TENANT + "/codigos").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // La fila sigue en la base (cerrada), y el código se puede reasignar.
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT count(*) FROM codigos_de_producto WHERE codigo = '7702009999990' AND retirado_en IS NOT NULL");
            rs.next();
            org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isEqualTo(1);
        }
        mockMvc.perform(post("/api/menu/products/ARROZ-" + TENANT + "/codigos")
                        .header("Authorization", bearer(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"7702009999990\"}"))
                .andExpect(status().isCreated());
    }
}
