package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F5.8e, regla «RLS no es lógica de negocio»: las lecturas del catálogo y el menú del mesero llevan el negocio ESCRITO.
 *
 * <p>La aplicación corre aquí con el DUEÑO de la base, que se salta RLS: lo único que separa a un negocio de otro es el
 * filtro del código. Con otro negocio sembrado, cada lectura que dependiera solo de RLS lo mostraría.
 *
 * <p>El menú del mesero (lo usa Shark en producción) se compara entero, forma y orden, con lo que devolvía antes:
 * categorías por {@code display_order} y luego nombre, productos por nombre dentro de cada una.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CatalogoConFiltroDeNegocioTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "catalogo-propio";
    static final String OTRO = "catalogo-ajeno";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // El dueño: RLS no aplica. Solo el filtro escrito aísla.
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

    private static String bearer(String tenant) {
        return "Bearer " + Jwts.builder().subject("caja@" + tenant + ".invalid").claim("tenant_id", tenant).claim("role", "admin")
                .claim("modules", List.of("ventas", "meseros", PlanCatalog.MESEROS))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    static boolean sembrado = false;

    @BeforeEach
    void sembrar() throws Exception {
        if (sembrado) {
            return;
        }
        sembrado = true;
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String t : new String[] {T, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','C','pro') ON CONFLICT (id) DO NOTHING");
            }
            // Propio: dos categorías (nombre y display_order en órdenes distintos) e ids de producto fuera del orden por nombre.
            s.execute("INSERT INTO menu_categories (id_category, tenant_id, name_category, display_order) VALUES "
                    + "('cp-bebidas','" + T + "','Bebidas',1), ('cp-almuerzos','" + T + "','Almuerzos',2), ('cp-postres','" + T + "','Postres',NULL)");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, category_id) VALUES "
                    + "('cp-3','" + T + "','Agua',2000,true,'cp-bebidas'), ('cp-1','" + T + "','Jugo',5000,true,'cp-bebidas'),"
                    + "('cp-2','" + T + "','Bandeja',15000,true,'cp-almuerzos'), ('cp-4','" + T + "','Arroz con leche',4000,false,'cp-almuerzos')");
            s.execute("UPDATE menu_products SET creado_en_caja_en = now(), creado_en_caja_por = 'Caja' WHERE id_product = 'cp-1'");
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) VALUES ('" + T + "','7700000000001','cp-1','panel')");
            // Ajeno: su categoría y su producto (nacido en caja), un código para el MISMO producto_id propio y una venta sin
            // registrar. (Un producto ajeno no puede colgar de una categoría propia: lo impide la FK (tenant_id, category_id).)
            s.execute("INSERT INTO menu_categories (id_category, tenant_id, name_category, display_order) VALUES ('ca-cafe','" + OTRO + "','Café',0)");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active, category_id) VALUES "
                    + "('ca-1','" + OTRO + "','Tinto ajeno',1500,true,'ca-cafe')");
            s.execute("UPDATE menu_products SET creado_en_caja_en = now(), creado_en_caja_por = 'Otra' WHERE id_product = 'ca-1'");
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) VALUES ('" + OTRO + "','7799999999999','cp-1','panel')");
            linea(s, T, 9201, "sin-registrar:p1", "Propio [770111]", 1000);
            linea(s, OTRO, 9202, "sin-registrar:a1", "Ajeno [770222]", 1000);
        }
    }

    private static void linea(Statement s, String tenant, long orden, String productId, String instrucciones, int precio) throws Exception {
        UUID uuid = UUID.nameUUIDFromBytes((tenant + "-" + orden).getBytes(StandardCharsets.UTF_8));
        s.execute("INSERT INTO orders (uuid_id, tenant_id, id_order, total, status, payment_method, created_at) VALUES ('" + uuid
                + "','" + tenant + "'," + orden + "," + precio + ",'pagado','CASH', now())");
        s.execute("INSERT INTO order_item (uuid_id, tenant_id, order_id, order_uuid_id, product_id, quantity, unit_price, total_price, instructions, precio_origen) "
                + "VALUES ('" + UUID.randomUUID() + "','" + tenant + "'," + orden + ",'" + uuid + "','" + productId + "',1," + precio + "," + precio
                + ",'" + instrucciones + "', 'POS')");
    }

    private JsonNode leer(String url) throws Exception {
        return json.readTree(mockMvc.perform(get(url).header("Authorization", bearer(T)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> valores(JsonNode lista, String campo) {
        List<String> r = new ArrayList<>();
        lista.forEach(n -> r.add(n.get(campo).asText()));
        return r;
    }

    @Test
    @DisplayName("🔴 F5.8e /api/menu/products: solo el negocio propio, por nombre, con sus códigos y no los del otro")
    void productos() throws Exception {
        JsonNode p = leer("/api/menu/products");
        assertThat(valores(p, "idProduct")).containsExactly("cp-3", "cp-4", "cp-2", "cp-1");
        JsonNode jugo = p.get(3);
        assertThat(valores(jugo.get("codigos"), "codigo")).containsExactly("7700000000001");
    }

    @Test
    @DisplayName("🔴 F5.8e /api/menu/categories-with-products: categorías propias por nombre, productos por id (el orden de Dexie), nada ajeno")
    void categoriasConProductos() throws Exception {
        JsonNode c = leer("/api/menu/categories-with-products");
        assertThat(valores(c, "idCategory")).containsExactly("cp-almuerzos", "cp-bebidas", "cp-postres");
        assertThat(valores(c.get(0).get("products"), "idProduct")).containsExactly("cp-2", "cp-4");
        assertThat(valores(c.get(1).get("products"), "idProduct")).containsExactly("cp-1", "cp-3");
        assertThat(c.get(2).get("products")).isEmpty();
        assertThat(valores(c.get(1).get("products").get(0).get("codigos"), "codigo")).containsExactly("7700000000001");
    }

    @Test
    @DisplayName("🔴 F5.8e /api/menu/products/{id}/codigos: solo los códigos del negocio propio para ese producto")
    void codigosDeUnProducto() throws Exception {
        assertThat(valores(leer("/api/menu/products/cp-1/codigos"), "codigo")).containsExactly("7700000000001");
    }

    @Test
    @DisplayName("🔴 F5.8e /api/menu/products/registrados-en-caja: solo el negocio propio")
    void registradosEnCaja() throws Exception {
        assertThat(valores(leer("/api/menu/products/registrados-en-caja"), "productoId")).containsExactly("cp-1");
    }

    @Test
    @DisplayName("🔴 F5.8e /api/menu/sin-registrar: solo las ventas sin registrar del negocio propio")
    void sinRegistrar() throws Exception {
        assertThat(valores(leer("/api/menu/sin-registrar"), "nombre")).containsExactly("Propio");
    }

    @Test
    @DisplayName("🔴 F5.8e menú del mesero: idéntico en forma y orden al de siempre (display_order, nombre; productos por nombre) y sin nada ajeno")
    void menuDelMesero() throws Exception {
        String esperado = """
                [{"id":"cp-bebidas","name":"Bebidas","icon":null,"products":[
                    {"id":"cp-3","name":"Agua","price":2000,"active":true},{"id":"cp-1","name":"Jugo","price":5000,"active":true}]},
                 {"id":"cp-almuerzos","name":"Almuerzos","icon":null,"products":[
                    {"id":"cp-4","name":"Arroz con leche","price":4000,"active":false},{"id":"cp-2","name":"Bandeja","price":15000,"active":true}]},
                 {"id":"cp-postres","name":"Postres","icon":null,"products":[]}]""";
        assertThat(leer("/api/waiter/mobile/menu")).isEqualTo(json.readTree(esperado));
    }
}
