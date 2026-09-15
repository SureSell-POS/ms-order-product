package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Plan de mayoristas F10.3: el comportamiento de pago de un cliente, por la API real y
 * con {@code app_user}. Los abonos se registran por {@code /api/cartera/recibos}, como
 * en la caja, para que las aplicaciones sean las de verdad.
 *
 * <pre>
 *   factura  fecha     vence     pago                                   resultado
 *   F1       hoy-100   hoy-90    100.000 el hoy-92                       a tiempo, 8 días
 *   F2       hoy-80    hoy-70    20.000 el hoy-75 y 30.000 el hoy-60     tarde (10), 20 días
 *   F3       hoy-50    hoy-40    40.000 el hoy-45, recibo ANULADO        vencida sin pagar (40)
 *   F4       hoy-10    hoy+5     —                                       por vencer (no mide)
 *   F5       hoy-30    sin plazo 20.000 el hoy-20                        pagada sin plazo, 10 días
 *   F6       hoy-400   hoy-390   pagada el hoy-395                       fuera de la ventana de 365
 * </pre>
 *
 * Resumen esperado: 5 facturas, 3 pagadas, 12,7 días de pago, 33,3 % a tiempo (1 de 3
 * medibles), 10,0 días de atraso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class ComportamientoDePagoTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-comportamiento";
    static final String ADMIN = "admin@qa-comportamiento.invalid";
    static final String CAJA = "caja@qa-comportamiento.invalid";
    static final String ANA = "ana@qa-comportamiento.invalid";
    static final String PEDRO = "pedro@qa-comportamiento.invalid";
    static final String TIENDA = "900";
    static final String SIN_FACTURAS = "800";
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

    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate dueno;
    private final LocalDate hoy = LocalDate.now(BOGOTA);
    private final UUID f1 = UUID.randomUUID();
    private final UUID f2 = UUID.randomUUID();
    private final UUID f3 = UUID.randomUUID();
    private final UUID f5 = UUID.randomUUID();
    private final UUID f6 = UUID.randomUUID();
    private String cuentaTienda;

    private static String bearer(String email, String role) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", T).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach
    void sembrar() throws Exception {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        dueno.update("DELETE FROM cartera_aplicaciones WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NOT NULL", T);
        dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM contadores_de_recibos WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM clientes WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM daily_closures WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM sites WHERE tenant_id = ?", T);
        dueno.update("DELETE FROM users WHERE tenant_id = ?", T);
        dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Comportamiento', 'pro') ON CONFLICT (id) DO NOTHING", T);
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        usuario(ADMIN, "admin");
        usuario(CAJA, "cajero");
        long ana = usuario(ANA, "vendedor");
        usuario(PEDRO, "vendedor");
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda', ?, 10, 's'), (?, ?, 'Sin facturas', ?, 10, 's')", T, TIENDA, ana, T, SIN_FACTURAS, ana);
        cuentaTienda = cuenta(TIENDA, 1000000, 339999);
        cuenta(SIN_FACTURAS, 1000000, 0);
        debito(f6, 99999, hoy.minusDays(400), hoy.minusDays(390));
        debito(f1, 100000, hoy.minusDays(100), hoy.minusDays(90));
        debito(f2, 50000, hoy.minusDays(80), hoy.minusDays(70));
        debito(f3, 40000, hoy.minusDays(50), hoy.minusDays(40));
        debito(UUID.randomUUID(), 30000, hoy.minusDays(10), hoy.plusDays(5));
        debito(f5, 20000, hoy.minusDays(30), null);

        abonar(f6, 99999, 395, "r6");
        abonar(f1, 100000, 92, "r1");
        abonar(f2, 20000, 75, "r2");
        String r4 = abonar(f3, 40000, 45, "r4");
        abonar(f2, 30000, 60, "r3");
        abonar(f5, 20000, 20, "r5");
        mockMvc.perform(post("/api/cartera/recibos/" + r4 + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"CHEQUE_DEVUELTO\"}"))
                .andExpect(status().isCreated());
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
    }

    private long usuario(String email, String rol) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) VALUES (?, '!', ?, ?, ?) RETURNING id",
                Long.class, email, T, rol, email.substring(0, email.indexOf('@')));
    }

    private String cuenta(String doc, int cupo, int deuda) {
        String id = UUID.randomUUID().toString();
        dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, customer_name, status, total_debt, updated_at) "
                + "VALUES (?, ?, now(), ?, ?, 'Cliente', 'ACTIVE', ?, now())", id, T, cupo, doc, deuda);
        return id;
    }

    private void debito(UUID orden, int monto, LocalDate fecha, LocalDate vence) {
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el, order_uuid) "
                + "VALUES (?, ?, ?, ?, now(), 'Venta a credito', ?, 'DEBIT', ?, ?)", UUID.randomUUID().toString(), T, cuentaTienda, monto,
                java.sql.Date.valueOf(fecha), vence == null ? null : java.sql.Date.valueOf(vence), orden);
    }

    /** Un abono aplicado a una factura, ocurrido hace {@code diasAtras} días al mediodía de Bogotá. Devuelve el id del recibo. */
    private String abonar(UUID factura, int monto, int diasAtras, String clave) throws Exception {
        String ocurrido = hoy.minusDays(diasAtras).atTime(12, 0).atZone(BOGOTA).toOffsetDateTime().toString();
        JsonNode r = leer(mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(CAJA, "cajero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clienteDocumento\":\"" + TIENDA + "\",\"monto\":" + monto + ",\"medio\":\"EFECTIVO\","
                                + "\"aplicaciones\":[{\"orderUuid\":\"" + factura + "\",\"monto\":" + monto + "}],"
                                + "\"ocurridoEn\":\"" + ocurrido + "\",\"idempotencyKey\":\"" + clave + "\"}"))
                .andExpect(status().isCreated()));
        return r.get("id").asText();
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions comportamiento(String quien, String rol, String doc, String... params) throws Exception {
        var peticion = get("/api/cartera/clientes/" + doc + "/comportamiento-de-pago").header("Authorization", bearer(quien, rol));
        for (int i = 0; i + 1 < params.length; i += 2) {
            peticion.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(peticion);
    }

    @Test
    @DisplayName("🔴 F10.3: días de pago, % a tiempo y atraso salen del libro; el recibo anulado no paga y lo que no vence no mide")
    void comportamiento() throws Exception {
        JsonNode c = leer(comportamiento(ADMIN, "admin", TIENDA).andExpect(status().isOk()));
        assertThat(c.get("facturas").asInt()).isEqualTo(5);
        assertThat(c.get("pagadas").asInt()).isEqualTo(3);
        assertThat(c.get("diasPromedioDePago").decimalValue()).isEqualByComparingTo("12.7");
        assertThat(c.get("porcentajeATiempo").decimalValue()).isEqualByComparingTo("33.3");
        assertThat(c.get("diasPromedioDeAtraso").decimalValue()).isEqualByComparingTo("10.0");
        assertThat(c.get("porEstado").get("PAGADA_A_TIEMPO").asInt()).isEqualTo(1);
        assertThat(c.get("porEstado").get("PAGADA_TARDE").asInt()).isEqualTo(1);
        assertThat(c.get("porEstado").get("PAGADA_SIN_PLAZO").asInt()).isEqualTo(1);
        assertThat(c.get("porEstado").get("VENCIDA_SIN_PAGAR").asInt()).isEqualTo(1);
        assertThat(c.get("porEstado").get("POR_VENCER").asInt()).isEqualTo(1);
        assertThat(c.get("abonosSinFacturaAsignada").decimalValue()).isEqualByComparingTo("0");
        assertThat(c.get("detalleCompleto").asBoolean()).isTrue();
        JsonNode segunda = null;
        JsonNode tercera = null;
        for (JsonNode f : c.get("detalle")) {
            if (f.get("orderUuid").asText().equals(f2.toString())) {
                segunda = f;
            }
            if (f.get("orderUuid").asText().equals(f3.toString())) {
                tercera = f;
            }
        }
        assertThat(segunda.get("pagadaEl").asText()).as("pagada el día del abono que la completa").isEqualTo(hoy.minusDays(60).toString());
        assertThat(segunda.get("diasParaPagar").asInt()).isEqualTo(20);
        assertThat(segunda.get("diasDeAtraso").asInt()).isEqualTo(10);
        assertThat(tercera.get("estado").asText()).as("el recibo anulado no paga").isEqualTo("VENCIDA_SIN_PAGAR");
        assertThat(tercera.get("diasDeAtraso").asInt()).isEqualTo(40);

        // Con la ventana ampliada entra F6, pagada a tiempo.
        comportamiento(ADMIN, "admin", TIENDA, "desde", hoy.minusDays(500).toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.facturas").value(6)).andExpect(jsonPath("$.porcentajeATiempo").value(50.0));
    }

    @Test
    @DisplayName("🔴 F10.3: sin facturas que midan es «sin dato», no 0; el abono del panel viejo se dice aparte; vendedor solo lo suyo")
    void sinDatoYVisibilidad() throws Exception {
        comportamiento(ADMIN, "admin", SIN_FACTURAS).andExpect(status().isOk())
                .andExpect(jsonPath("$.facturas").value(0))
                .andExpect(jsonPath("$.porcentajeATiempo").doesNotExist())
                .andExpect(jsonPath("$.diasPromedioDePago").doesNotExist());

        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type) "
                + "VALUES (?, ?, ?, 5000, now(), 'Abono del panel viejo', ?, 'CREDIT')", UUID.randomUUID().toString(), T, cuentaTienda,
                java.sql.Date.valueOf(hoy.minusDays(15)));
        comportamiento(ADMIN, "admin", TIENDA).andExpect(status().isOk()).andExpect(jsonPath("$.abonosSinFacturaAsignada").value(5000.0));

        comportamiento(ANA, "vendedor", TIENDA).andExpect(status().isOk()).andExpect(jsonPath("$.facturas").value(5));
        comportamiento(PEDRO, "vendedor", TIENDA).andExpect(status().isBadRequest());
        comportamiento(ADMIN, "admin", TIENDA, "desde", hoy.toString(), "hasta", hoy.minusDays(1).toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("desde"));
    }
}
