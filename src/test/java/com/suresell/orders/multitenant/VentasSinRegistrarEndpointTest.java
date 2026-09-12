package com.suresell.orders.multitenant;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La cola de «vendidos sin registrar»: líneas con product_id `sin-registrar:`
 * y el nombre (y el código entre corchetes) en `instructions`, agrupadas.
 * El otro negocio no ve nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class VentasSinRegistrarEndpointTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-sin-registrar";
    static final String OTRO = "otro-sin-registrar";

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
        return "Bearer " + Jwts.builder().claim("tenant_id", tenant)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private void linea(Statement s, String tenant, long orden, String productId, String instrucciones, int precio) throws Exception {
        // La orden y su línea van unidas por el UUID además del número (así las lee la entidad).
        UUID uuid = UUID.nameUUIDFromBytes((tenant + "-" + orden).getBytes(StandardCharsets.UTF_8));
        s.execute("INSERT INTO orders (uuid_id, tenant_id, id_order, total, status, payment_method, created_at) VALUES ('" + uuid
                + "','" + tenant + "'," + orden + "," + precio + ",'pagado','CASH', now()) ON CONFLICT DO NOTHING");
        // Sin created_at en la línea, como llegan las ventas reales del POS (medido en staging):
        // la cola tiene que fecharlas por la orden.
        s.execute("INSERT INTO order_item (uuid_id, tenant_id, order_id, order_uuid_id, product_id, quantity, unit_price, total_price, instructions, precio_origen) "
                + "VALUES ('" + UUID.randomUUID() + "','" + tenant + "'," + orden + ",'" + uuid + "','" + productId + "',1," + precio + "," + precio
                + "," + (instrucciones == null ? "NULL" : "'" + instrucciones + "'") + ", 'POS')");
    }

    @Test
    @DisplayName("🔴 agrupa por nombre, código y precio; cuenta las veces; el otro negocio no ve nada; una línea normal no entra")
    void colaAgrupada() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String t : new String[]{TENANT, OTRO}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','S','pro') ON CONFLICT (id) DO NOTHING");
            }
            linea(s, TENANT, 9001, "sin-registrar:a1", "Galleta importada [7702001234561]", 3500);
            linea(s, TENANT, 9002, "sin-registrar:a2", "Galleta importada [7702001234561]", 3500);
            linea(s, TENANT, 9003, "sin-registrar:a3", "Bolsa hielo", 2000);
            linea(s, TENANT, 9004, "P-NORMAL", "sin cebolla", 15000);
            linea(s, OTRO, 9005, "sin-registrar:b1", "Ajeno", 100);
        }

        mockMvc.perform(get("/api/menu/sin-registrar").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].codigo").value("7702001234561"))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].veces").value(2))
                .andExpect(jsonPath("$[?(@.nombre == 'Galleta importada')].precio").value(3500.0))
                .andExpect(jsonPath("$[?(@.nombre == 'Bolsa hielo')].veces").value(1));

        // Y al leer la orden, la línea sin registrar sale con el nombre tecleado, no con el id.
        mockMvc.perform(get("/orders/9003").header("Authorization", bearer(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("sin-registrar:a3"))
                .andExpect(jsonPath("$.items[0].nameProduct").value("Bolsa hielo"));

        mockMvc.perform(get("/api/menu/sin-registrar?dias=7").header("Authorization", bearer(OTRO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value("Ajeno"));
    }

    @Test
    @DisplayName("🔴 V55: fuera las líneas cuyo código ya es un código vigente de un producto del negocio; el retirado, el ajeno y el sin código se quedan")
    void fueraLoQueYaTieneDuenno() throws Exception {
        String negocio = "codigos-sin-registrar";
        String ajeno = "otro-codigos-sin-registrar";
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String t : new String[]{negocio, ajeno}) {
                s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + t + "','S','pro') ON CONFLICT (id) DO NOTHING");
                s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES ('P-" + t
                        + "','" + t + "','Registrado',3500,true)");
            }
            // Ya registrado en este negocio (vigente): sale de la cola.
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) VALUES ('"
                    + negocio + "','7700000000011','P-" + negocio + "','pos')");
            // Retirado en este negocio: ya no es de nadie, se queda.
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente, retirado_en) VALUES ('"
                    + negocio + "','7700000000028','P-" + negocio + "','panel', now())");
            // Vigente pero en OTRO negocio: no cuenta para este.
            s.execute("INSERT INTO codigos_de_producto (tenant_id, codigo, producto_id, fuente) VALUES ('"
                    + ajeno + "','7700000000035','P-" + ajeno + "','panel')");

            linea(s, negocio, 9101, "sin-registrar:c1", "Ya registrado [7700000000011]", 3500);
            linea(s, negocio, 9102, "sin-registrar:c2", "Retirado [7700000000028]", 3500);
            linea(s, negocio, 9103, "sin-registrar:c3", "De otro negocio [7700000000035]", 3500);
            linea(s, negocio, 9104, "sin-registrar:c4", "Sin codigo", 3500);
        }

        mockMvc.perform(get("/api/menu/sin-registrar").header("Authorization", bearer(negocio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.nombre == 'Ya registrado')]", hasSize(0)))
                .andExpect(jsonPath("$[?(@.nombre == 'Retirado')].codigo").value("7700000000028"))
                .andExpect(jsonPath("$[?(@.nombre == 'De otro negocio')].codigo").value("7700000000035"))
                .andExpect(jsonPath("$[?(@.nombre == 'Sin codigo')].veces").value(1))
                // La forma de siempre, campo por campo.
                .andExpect(jsonPath("$[0].nombre").exists())
                .andExpect(jsonPath("$[0].precio").exists())
                .andExpect(jsonPath("$[0].veces").exists())
                .andExpect(jsonPath("$[0].ultimaVenta").exists())
                .andExpect(jsonPath("$[0].ultimaOrden").exists());
    }
    /**
     * 🔴 El camino REAL: la venta entra por donde entra en staging
     * (`POST /orders/create`, como la manda el POS con F4), no con un INSERT
     * a mano. Es la única forma de ver lo que el POS y -mt hacen de verdad
     * con la línea «sin registrar».
     */
    @Test
    @DisplayName("🔴 una venta cobrada por el POS con F4 aparece en la cola (camino real, no INSERT a mano)")
    void ventaDelPosLlegaALaCola() throws Exception {
        String negocio = "e2e-sin-registrar";
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + negocio + "','S','pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES ('P-" + negocio
                    + "','" + negocio + "','Gaseosa',5000,true)");
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES ('" + negocio
                    + "','Principal','PRINCIPAL','DIRECTO',true)");
        }

        // Tal cual lo arma el POS: `sin-registrar:<uuid>` y el nombre (con el
        // código leído entre corchetes) en `instructions`.
        String cuerpo = "{\"paymentMethod\":\"CASH\",\"items\":["
                + "{\"productId\":\"P-" + negocio + "\",\"quantity\":1,\"unitPrice\":5000},"
                + "{\"productId\":\"sin-registrar:" + UUID.randomUUID()
                + "\",\"quantity\":1,\"unitPrice\":3500,\"instructions\":\"Galleta importada [7702009999999]\"}],"
                + "\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}";
        mockMvc.perform(post("/orders/create").header("Authorization", bearer(negocio))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/menu/sin-registrar").header("Authorization", bearer(negocio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value("Galleta importada"))
                .andExpect(jsonPath("$[0].codigo").value("7702009999999"))
                .andExpect(jsonPath("$[0].veces").value(1))
                .andExpect(jsonPath("$[0].ultimaVenta").exists())
                .andExpect(jsonPath("$[0].ultimaOrden").exists());
    }
    /**
     * 🔴 La relación OrderItem→Order es por UUID, no por el número de orden
     * (V1__multitenant_baseline.sql:45, y {@code @JoinColumn("order_uuid_id")}).
     * Hay un camino de escritura que solo pone el UUID
     * ({@code PostgresOrderCloudSyncAdapter.upsertOrderItems}: la venta que
     * sincroniza una caja local). Esas líneas están vendidas y cobradas; la
     * cola tiene que verlas igual.
     */
    @Test
    @DisplayName("🔴 una línea unida a su orden solo por UUID (order_id nulo) también entra en la cola")
    void lineaUnidaPorUuidEntra() throws Exception {
        String negocio = "uuid-sin-registrar";
        UUID uuid = UUID.nameUUIDFromBytes((negocio + "-9201").getBytes(StandardCharsets.UTF_8));
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + negocio + "','S','pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO orders (uuid_id, tenant_id, id_order, total, status, payment_method, created_at) VALUES ('"
                    + uuid + "','" + negocio + "',9201,4000,'pagado','CASH', now())");
            // Como la escribe el sincronizador: sin order_id, solo el UUID.
            s.execute("INSERT INTO order_item (uuid_id, tenant_id, order_uuid_id, product_id, quantity, unit_price, total_price, instructions, precio_origen) "
                    + "VALUES ('" + UUID.randomUUID() + "','" + negocio + "','" + uuid
                    + "','sin-registrar:u1',1,4000,4000,'Pila AA [7702008888888]','POS')");
        }

        mockMvc.perform(get("/api/menu/sin-registrar").header("Authorization", bearer(negocio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value("Pila AA"))
                .andExpect(jsonPath("$[0].codigo").value("7702008888888"))
                .andExpect(jsonPath("$[0].ultimaOrden").value(9201));
    }
}
