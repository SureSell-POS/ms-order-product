package com.suresell.orders.cartera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
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
 * Plan de mayoristas F4.12 por la API real, con {@code app_user} (RLS y permisos de verdad), sobre V74:
 * el saldo a favor derivado, que se aplica solo a la venta a crédito, se devuelve (solo admin) y sale del
 * cajón en el cierre. Los dos escenarios de aceptación aprobados por ECM el 2026-09-15:
 * <ul>
 *   <li>(i) 120.000 sobre 100.000 → 20.000 a favor → venta a crédito de 50.000 → debe 30.000, «20.000 aplicados»;</li>
 *   <li>(ii) 120.000 sobre 100.000 → devolver 20.000 en efectivo → a favor 0, libro 0, y el cierre muestra el egreso.</li>
 * </ul>
 * Y la insolvencia según el concepto jurídico (ECM, 2026-09-15): compensar es ineficaz, así que nada se cruza por ninguna vía;
 * la devolución sí procede.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class SaldoAFavorTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String T = "qa-saldo-a-favor";
    static final String OTRO = "qa-saldo-a-favor-otro";
    static final String ADMIN = "admin@qa-saldo-a-favor.invalid";
    static final String ADMIN_OTRO = "admin@qa-saldo-a-favor-otro.invalid";
    static final String CAJA = "caja@qa-saldo-a-favor.invalid";
    static final String VENDEDOR = "ana@qa-saldo-a-favor.invalid";
    static final String TIENDA = "901";
    static final String VECINA = "902";
    static final String PRODUCTO = "aceite-x12-" + T;
    static final UUID TERMINAL = UUID.fromString("5a1e0000-0000-4000-8000-00000000f412");

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
    private long admin;
    private final LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));

    private static String bearer(String email, String role, String tenant) {
        return "Bearer " + Jwts.builder().subject(email).claim("tenant_id", tenant).claim("role", role)
                .claim("modules", List.of("ventas", "mayorista", "cartera"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String bearer(String email, String role) {
        return bearer(email, role, T);
    }

    @BeforeEach
    void sembrar() {
        dueno = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String t : new String[] {T, OTRO}) {
            dueno.update("DELETE FROM cartera_aplicaciones_revertidas WHERE tenant_id = ?", t);
            ProcesoDeInsolvenciaTest.limpiarInsolvencia(dueno, t);
            dueno.update("DELETE FROM cartera_aplicaciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM egresos_de_cartera_mercancia WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM egresos_de_cartera WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM contadores_de_egresos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM ventas_a_insolvente_resoluciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM ventas_a_insolvente WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM debt_transactions WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NOT NULL", t);
            dueno.update("DELETE FROM recibos_de_caja WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM contadores_de_recibos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM accounts_receivable WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM inventario_intenciones WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_delivery_tracking WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_item WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM order_payments WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM orders WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM tenant_order_counters WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM terminals WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM clientes WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM daily_closures WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM menu_products WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM sites WHERE tenant_id = ?", t);
            dueno.update("DELETE FROM users WHERE tenant_id = ?", t);
            dueno.update("INSERT INTO tenants (id, name, plan) VALUES (?, ?, 'pro') ON CONFLICT (id) DO NOTHING", t, t);
        }
        dueno.update("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                + "VALUES (?, 'Principal', 'PRINCIPAL', 'DIRECTO', true)", T);
        admin = usuario(ADMIN, T, "admin", "Admin");
        usuario(CAJA, T, "cajero", "Caja");
        long ana = usuario(VENDEDOR, T, "vendedor", "Ana");
        usuario(ADMIN_OTRO, OTRO, "admin", "Admin del otro");
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) "
                + "VALUES (?, ?, 'Aceite x12', 25000, true)", PRODUCTO, T);
        dueno.update("INSERT INTO clientes (tenant_id, documento, nombre, vendedor_id, plazo_dias, creado_por) VALUES "
                + "(?, ?, 'Tienda', ?, 8, 's'), (?, ?, 'Vecina', ?, 8, 's')", T, TIENDA, ana, T, VECINA, ana);
        // La Tienda debe 100.000 de una factura vencida; la Vecina no debe nada.
        String cuenta = cuenta(TIENDA, "Tienda", 100000);
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, "
                + "transaction_date, type, vence_el, order_uuid) VALUES (?, ?, ?, 100000, now(), 'Venta a credito', ?, 'DEBIT', ?, ?)",
                UUID.randomUUID().toString(), T, cuenta, java.sql.Date.valueOf(hoy.minusDays(20)),
                java.sql.Date.valueOf(hoy.minusDays(12)), UUID.randomUUID());
        cuenta(VECINA, "Vecina", 0);
        dueno.update("DELETE FROM clientes_eventos WHERE tenant_id = ?", T);
    }

    private long usuario(String email, String tenant, String rol, String nombre) {
        return dueno.queryForObject("INSERT INTO users (email, password_hash, tenant_id, role, nombre) "
                + "VALUES (?, '!', ?, ?, ?) RETURNING id", Long.class, email, tenant, rol, nombre);
    }

    private String cuenta(String doc, String nombre, int deuda) {
        String id = UUID.randomUUID().toString();
        dueno.update("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document, "
                + "customer_name, status, total_debt, updated_at) VALUES (?, ?, now(), 1000000, ?, ?, 'ACTIVE', ?, now())",
                id, T, doc, nombre, deuda);
        return id;
    }

    // ---------------------------------------------------------------- ayudas

    private ResultActions abonar(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/recibos").header("Authorization", bearer(CAJA, "cajero"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static String abono(String doc, int monto, String clave, String excedente) {
        return "{\"clienteDocumento\":\"" + doc + "\",\"monto\":" + monto + ",\"medio\":\"EFECTIVO\",\"idempotencyKey\":\"" + clave + "\""
                + (excedente == null ? "" : ",\"excedente\":\"" + excedente + "\"") + "}";
    }

    /** El abono de 120.000 sobre la deuda de 100.000 de la Tienda, con el resto a su favor. */
    private String abonarConSaldoAFavor(String clave) throws Exception {
        return leer(abonar(abono(TIENDA, 120000, clave, "SALDO_A_FAVOR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavor").value(20000))).get("id").asText();
    }

    private ResultActions devolver(String email, String rol, String doc, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/clientes/" + doc + "/saldo-a-favor/devolver").header("Authorization", bearer(email, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private static String devolucion(int monto, String motivo, String referencia, String clave) {
        return "{\"monto\":" + monto + ",\"medio\":\"EFECTIVO\",\"motivo\":\"" + motivo + "\","
                + (referencia == null ? "" : "\"referencia\":\"" + referencia + "\",") + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    private static int seq = 0;

    private static String ventaACredito(String doc, String clave, boolean conCaja) {
        seq++;
        String procedencia = conCaja
                ? "\"terminalId\":\"" + TERMINAL + "\",\"epoch\":1,\"seq\":" + seq + ",\"ocurridoEn\":\"2026-09-15T08:00:00-05:00\","
                : "";
        return "{\"pagerColor\":\"MESA\",\"pagerNumber\":\"" + (seq % 150 + 1) + "\",\"paymentMethod\":\"CREDITO\","
                + "\"clienteDocumento\":\"" + doc + "\"," + procedencia
                + "\"items\":[{\"productId\":\"" + PRODUCTO + "\",\"quantity\":2,\"unitPrice\":25000}],"
                + "\"idempotencyKey\":\"" + clave + "\"}";
    }

    private ResultActions vender(String cuerpo) throws Exception {
        return mockMvc.perform(post("/orders/create").header("Authorization", bearer(CAJA, "cajero"))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    /** Marca con la fecha (endpoint de F4.4) o, con null, levanta informando la etapa (F4.13 b: el null ya no levanta). */
    private void insolvencia(String desde) throws Exception {
        if (desde == null) {
            levantar().andExpect(status().isCreated());
            return;
        }
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"desde\":\"" + desde + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions levantar() throws Exception {
        return mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"etapa\":\"CORRECCION_DE_ERROR\",\"fecha\":\"" + hoy + "\",\"documento\":\"Se marco por error\",\"informadoPor\":\"admin\"}"));
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode estadoDeCuenta(String doc) throws Exception {
        return leer(mockMvc.perform(get("/api/cartera/clientes/" + doc + "/estado-de-cuenta").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk()));
    }

    private JsonNode preview() throws Exception {
        return leer(mockMvc.perform(get("/api/closures/preview").header("Authorization", bearer(CAJA, "cajero")))
                .andExpect(status().isOk()));
    }

    private BigDecimal libro(String doc) {
        return dueno.queryForObject("SELECT COALESCE(sum(CASE WHEN d.type = 'DEBIT' THEN d.amount ELSE -d.amount END), 0) "
                + "FROM debt_transactions d JOIN accounts_receivable a ON a.id = d.account_id "
                + "WHERE a.tenant_id = ? AND a.customer_document = ?", BigDecimal.class, T, doc);
    }

    private BigDecimal totalDebt(String doc) {
        return dueno.queryForObject("SELECT total_debt FROM accounts_receivable WHERE tenant_id = ? AND customer_document = ?",
                BigDecimal.class, T, doc);
    }

    private BigDecimal saldoDeLaVenta(String clave) {
        return dueno.queryForObject("SELECT v.saldo FROM v_cartera_por_documento v JOIN orders o ON o.uuid_id = v.order_uuid "
                + "WHERE o.tenant_id = ? AND o.idempotency_key = ?", BigDecimal.class, T, clave);
    }

    private BigDecimal facturasVivas(String doc) {
        return dueno.queryForObject("SELECT COALESCE(sum(saldo), 0) FROM v_cartera_por_documento WHERE tenant_id = ? "
                + "AND cliente_documento = ? AND saldo > 0", BigDecimal.class, T, doc);
    }

    private int contar(String sql, Object... args) {
        return dueno.queryForObject(sql, Integer.class, args);
    }

    // ---------------------------------------------------------------- aceptación

    @Test
    @DisplayName("🔴 F4.12 (i): 120.000 sobre 100.000 deja 20.000 a favor; la venta a crédito de 50.000 queda debiendo 30.000 y dice «20.000 aplicados»")
    void escenarioI() throws Exception {
        abonarConSaldoAFavor("i-abono");
        JsonNode antes = estadoDeCuenta(TIENDA);
        assertThat(antes.get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
        assertThat(antes.get("saldoAFavorCongelado").asBoolean()).isFalse();
        assertThat(libro(TIENDA)).isEqualByComparingTo("-20000");
        assertThat(leer(mockMvc.perform(get("/api/cartera/resumen").header("Authorization", bearer(ADMIN, "admin"))))
                .get("saldoAFavorDeClientes").decimalValue()).isEqualByComparingTo("20000");

        vender(ventaACredito(TIENDA, "i-venta", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(20000))
                .andExpect(jsonPath("$.saldoAFavorRestante").value(0))
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(false));

        assertThat(saldoDeLaVenta("i-venta")).isEqualByComparingTo("30000");
        assertThat(facturasVivas(TIENDA)).isEqualByComparingTo("30000");
        assertThat(libro(TIENDA)).isEqualByComparingTo("30000");
        assertThat(totalDebt(TIENDA)).isEqualByComparingTo("30000");
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isZero();
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO' "
                + "AND monto = 20000", T)).isEqualTo(1);

        // El outbox reintenta: misma respuesta, sin aplicar otra vez.
        vender(ventaACredito(TIENDA, "i-venta", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(20000));
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO'", T))
                .isEqualTo(1);
        assertThat(saldoDeLaVenta("i-venta")).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("🔴 F4.12 (ii): devolver 20.000 en efectivo deja a favor 0 y libro 0, sin factura nueva; el cierre lo resta del cajón y cuadra con el preview")
    void escenarioII() throws Exception {
        JsonNode previewAntes = preview();
        assertThat(previewAntes.get("devolucionesSaldoAFavorEfectivo").decimalValue()).isZero();
        BigDecimal efectivoAntes = previewAntes.get("totalExpectedCash").decimalValue();

        abonarConSaldoAFavor("ii-abono");
        // 120.000 en efectivo sobre 100.000: el esperado sube 120.000, y 20.000 van en su propia línea (ya dentro del recaudo).
        JsonNode trasElAbono = preview();
        assertThat(trasElAbono.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("120000");
        assertThat(trasElAbono.get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isEqualByComparingTo("20000");
        assertThat(trasElAbono.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("120000")));
        JsonNode egreso = leer(devolver(ADMIN, "admin", TIENDA, devolucion(20000, "CLIENTE_LO_PIDIO", null, "ii-devolucion"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(1))
                .andExpect(jsonPath("$.monto").value(20000))
                .andExpect(jsonPath("$.medio").value("EFECTIVO"))
                .andExpect(jsonPath("$.pagadoPorId").value(admin))
                .andExpect(jsonPath("$.pagadoPor").value("Admin"))
                .andExpect(jsonPath("$.saldoAFavorQueda").value(0)));

        assertThat(libro(TIENDA)).isEqualByComparingTo("0");
        assertThat(totalDebt(TIENDA)).isEqualByComparingTo("0");
        assertThat(facturasVivas(TIENDA)).isZero();
        JsonNode estado = estadoDeCuenta(TIENDA);
        assertThat(estado.get("saldoAFavor").decimalValue()).isZero();
        assertThat(estado.get("egresos")).hasSize(1);
        assertThat(estado.get("egresos").get(0).get("id").asText()).isEqualTo(egreso.get("id").asText());
        // El DEBIT del egreso no es una factura: no aparece entre los documentos del periodo.
        String debitoDelEgreso = dueno.queryForObject("SELECT debito_tx_id FROM egresos_de_cartera WHERE tenant_id = ?", String.class, T);
        assertThat(estado.get("documentos").findValuesAsText("debitoTxId")).doesNotContain(debitoDelEgreso);

        // El reintento devuelve el mismo egreso; la misma clave con otro monto choca.
        devolver(ADMIN, "admin", TIENDA, devolucion(20000, "CLIENTE_LO_PIDIO", null, "ii-devolucion"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.numero").value(1));
        devolver(ADMIN, "admin", TIENDA, devolucion(10000, "CLIENTE_LO_PIDIO", null, "ii-devolucion"))
                .andExpect(status().isConflict()).andExpect(ConflictoConMensaje.de("IDEMPOTENCIA_REUTILIZADA"));
        // No más de lo que tiene a favor (ya 0).
        devolver(ADMIN, "admin", TIENDA, devolucion(1, "CLIENTE_LO_PIDIO", null, "ii-de-mas"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("monto")).andExpect(jsonPath("$.maximo").value(0));
        assertThat(contar("SELECT count(*) FROM egresos_de_cartera WHERE tenant_id = ?", T)).isEqualTo(1);

        // El cajón: entraron 120.000 del abono y salieron 20.000 de la devolución.
        JsonNode antesDeCerrar = preview();
        assertThat(antesDeCerrar.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("120000");
        assertThat(antesDeCerrar.get("devolucionesSaldoAFavorEfectivo").decimalValue()).isEqualByComparingTo("20000");
        assertThat(antesDeCerrar.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("100000")));

        // Se cierra contando exactamente 100.000: cuadra, y la respuesta trae la misma devolución que el preview.
        String cuerpo = "{\"cashDetail\":{\"bill100k\":1,\"bill50k\":0,\"bill20k\":0,\"bill10k\":0,\"bill5k\":0,"
                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                + "\"countedCard\":0,\"countedQr\":0,\"notes\":\"turno\",\"pettyCashExpenses\":[],\"baseForNextDay\":0}";
        JsonNode cierre = leer(mockMvc.perform(post("/api/closures").header("Authorization", bearer(CAJA, "cajero"))
                        .header("X-User-Name", "Caja").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk()));
        assertThat(cierre.get("devolucionesSaldoAFavorEfectivo").decimalValue())
                .isEqualByComparingTo(antesDeCerrar.get("devolucionesSaldoAFavorEfectivo").decimalValue());
        assertThat(cierre.get("recaudoCarteraEfectivo").decimalValue())
                .isEqualByComparingTo(antesDeCerrar.get("recaudoCarteraEfectivo").decimalValue());
        assertThat(cierre.get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isEqualByComparingTo("20000")
                .isEqualByComparingTo(antesDeCerrar.get("recaudoComoSaldoAFavorEfectivo").decimalValue());
        assertThat(cierre.get("shortages").has("Efectivo")).as(cierre.toString()).isFalse();
        assertThat(dueno.queryForObject("SELECT total_expected_cash FROM daily_closures WHERE tenant_id = ?", BigDecimal.class, T))
                .isEqualByComparingTo(antesDeCerrar.get("totalExpectedCash").decimalValue());
        // El turno siguiente arranca sin la devolución del anterior.
        assertThat(preview().get("devolucionesSaldoAFavorEfectivo").decimalValue()).isZero();
    }

    @Test
    @DisplayName("🔴 cierre: sin excedente la línea «recibido como saldo a favor» es 0; con excedente lleva lo que sobró; anularlo la resta; el esperado cuadra siempre")
    void recibidoComoSaldoAFavorEnElCierre() throws Exception {
        BigDecimal efectivoAntes = preview().get("totalExpectedCash").decimalValue();
        abonar(abono(TIENDA, 100000, "rc-exacto", null)).andExpect(status().isCreated());
        JsonNode exacto = preview();
        assertThat(exacto.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("100000");
        assertThat(exacto.get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isZero();
        assertThat(exacto.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("100000")));

        String anticipo = leer(abonar(abono(TIENDA, 30000, "rc-anticipo", "SALDO_A_FAVOR")).andExpect(status().isCreated())).get("id").asText();
        JsonNode conAnticipo = preview();
        assertThat(conAnticipo.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("130000");
        assertThat(conAnticipo.get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isEqualByComparingTo("30000");
        assertThat(conAnticipo.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("130000")));

        // Aplicado después a una venta sigue contando como recibido a favor: la línea dice cómo entró el dinero, no qué pasó luego.
        vender(ventaACredito(TIENDA, "rc-venta", false)).andExpect(jsonPath("$.saldoAFavorAplicado").value(30000));
        assertThat(preview().get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isEqualByComparingTo("30000");

        mockMvc.perform(post("/api/cartera/recibos/" + anticipo + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        JsonNode anulado = preview();
        assertThat(anulado.get("recaudoCarteraEfectivo").decimalValue()).isEqualByComparingTo("100000");
        assertThat(anulado.get("recaudoComoSaldoAFavorEfectivo").decimalValue()).isZero();
        assertThat(anulado.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes.add(new BigDecimal("100000")));
    }

    @Test
    @DisplayName("🔴 R10: sin «excedente», un abono mayor que la deuda sigue siendo el 400 de siempre, con el mismo texto y el mismo máximo")
    void sinExcedenteNadaCambia() throws Exception {
        abonar(abono(TIENDA, 120000, "r10-sin", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("monto"))
                .andExpect(jsonPath("$.message").value("El cliente debe $100.000; no se puede abonar más."))
                .andExpect(jsonPath("$.maximo").value(100000));
        // Con excedente y facturas elegidas, no: el saldo a favor se aplica solo a lo que debe.
        abonar("{\"clienteDocumento\":\"" + TIENDA + "\",\"monto\":120000,\"medio\":\"EFECTIVO\",\"idempotencyKey\":\"r10-elegidas\","
                + "\"excedente\":\"SALDO_A_FAVOR\",\"aplicaciones\":[{\"orderUuid\":\"" + UUID.randomUUID() + "\",\"monto\":100000}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("aplicaciones"));
        assertThat(contar("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ?", T)).isZero();
        assertThat(libro(TIENDA)).isEqualByComparingTo("100000");
    }

    // ---------------------------------------------------------------- insolvencia: nada se cruza

    @Test
    @DisplayName("🔴 insolvencia: una cuenta en proceso con saldo a favor y una factura anterior no cruza nada por ninguna vía")
    void enInsolvenciaNadaSeCruza() throws Exception {
        // Paga la factura de 100.000 con un recibo y deja 20.000 a favor con otro.
        String pago = leer(abonar(abono(TIENDA, 100000, "ins-pago", null)).andExpect(status().isCreated())).get("id").asText();
        abonar(abono(TIENDA, 20000, "ins-anticipo", "SALDO_A_FAVOR")).andExpect(status().isCreated()).andExpect(jsonPath("$.saldoAFavor").value(20000));
        insolvencia(hoy.toString());
        // La factura anterior al inicio del proceso, viva.
        String cuenta = dueno.queryForObject("SELECT id FROM accounts_receivable WHERE tenant_id = ? AND customer_document = ?", String.class, T, TIENDA);
        UUID anterior = UUID.randomUUID();
        dueno.update("INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, description, transaction_date, type, vence_el, order_uuid) "
                + "VALUES (?, ?, ?, 40000, now(), 'Venta a credito', ?, 'DEBIT', ?, ?)", UUID.randomUUID().toString(), T, cuenta,
                java.sql.Date.valueOf(hoy.minusDays(30)), java.sql.Date.valueOf(hoy.minusDays(22)), anterior);

        // Vía 1: abono con facturas elegidas a una factura anterior al inicio → 409 ABONO_A_DEUDA_ANTERIOR (F4.13 b), sin recibo.
        abonar("{\"clienteDocumento\":\"" + TIENDA + "\",\"monto\":20000,\"medio\":\"EFECTIVO\",\"idempotencyKey\":\"ins-elegida\","
                + "\"aplicaciones\":[{\"orderUuid\":\"" + anterior + "\",\"monto\":20000}]}")
                .andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("ABONO_A_DEUDA_ANTERIOR"));
        // Vía 2: venta nueva a crédito desde la caja → entra marcada y no se aplica el saldo.
        vender(ventaACredito(TIENDA, "ins-venta", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionPorInsolvencia").value(true))
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(0))
                .andExpect(jsonPath("$.saldoAFavorRestante").value(20000));
        // Vía 3: levantar la insolvencia no aplica nada en ese momento.
        JsonNode levantada = leer(levantar().andExpect(status().isCreated()));
        assertThat(levantada.has("saldoAFavorAplicado")).as(levantada.toString()).isFalse();
        insolvencia(hoy.toString());
        // Vía 4: anular el recibo que pagó la factura la reabre; con la cuenta en proceso el saldo no se cruza.
        mockMvc.perform(post("/api/cartera/recibos/" + pago + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        assertThat(facturasVivas(TIENDA)).as("100.000 reabiertos + 40.000 anterior + 50.000 de la venta").isEqualByComparingTo("190000");

        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO'", T)).isZero();
        assertThat(contar("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NULL", T)).as("sin recibo nuevo por la vía 1").isEqualTo(2);
        assertThat(dueno.queryForObject("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND order_uuid = ?", BigDecimal.class, T, anterior))
                .isEqualByComparingTo("40000");
        assertThat(saldoDeLaVenta("ins-venta")).isEqualByComparingTo("50000");
        JsonNode estado = estadoDeCuenta(TIENDA);
        assertThat(estado.get("saldoAFavor").decimalValue()).as("queda visible y sin aplicar").isEqualByComparingTo("20000");
        assertThat(estado.get("saldoAFavorCongelado").asBoolean()).isTrue();
        assertThat(leer(mockMvc.perform(get("/api/cartera/resumen").header("Authorization", bearer(ADMIN, "admin"))))
                .get("saldoAFavorDeClientes").decimalValue()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("🔴 insolvencia: mientras el proceso sigue el saldo figura sin cruzar; levantada la marca, la venta siguiente vuelve a la regla normal (opción 1, límite en V74)")
    void despuesDeLevantarVuelveLaRegla() throws Exception {
        abonarConSaldoAFavor("lev-abono");
        insolvencia(hoy.toString());
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavorCongelado").asBoolean()).isTrue();
        insolvencia(null);
        vender(ventaACredito(TIENDA, "lev-venta", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(20000));
        assertThat(saldoDeLaVenta("lev-venta")).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("🔴 insolvencia: la devolución procede con la cuenta en proceso; al proceso exige referencia")
    void devolucionConLaCuentaEnProceso() throws Exception {
        abonarConSaldoAFavor("proc-abono");
        insolvencia(hoy.toString());
        devolver(ADMIN, "admin", TIENDA, devolucion(20000, "DEVUELTO_AL_PROCESO", null, "proc-sin-referencia"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("referencia"));
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "CLIENTE_LO_PIDIO", null, "proc-al-cliente"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorQueda").value(15000));
        devolver(ADMIN, "admin", TIENDA, devolucion(15000, "devuelto_al_proceso", "Oficio 77 del promotor", "proc-devolucion"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.motivo").value("DEVUELTO_AL_PROCESO"))
                .andExpect(jsonPath("$.referencia").value("Oficio 77 del promotor"))
                .andExpect(jsonPath("$.numero").value(2))
                .andExpect(jsonPath("$.saldoAFavorQueda").value(0));
        assertThat(libro(TIENDA)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("🟡 insolvencia (c): lo aplicado solo entre la fecha de inicio y el día en que se marcó se lista, no se deshace (F4.13)")
    void aplicacionesARevisar() throws Exception {
        abonarConSaldoAFavor("rev-abono");
        vender(ventaACredito(TIENDA, "rev-venta", false)).andExpect(jsonPath("$.saldoAFavorAplicado").value(20000));
        insolvencia(hoy.minusDays(1).toString());

        JsonNode estado = estadoDeCuenta(TIENDA);
        assertThat(estado.get("aplicacionesARevisarPorInsolvencia")).hasSize(1);
        assertThat(estado.get("aplicacionesARevisarPorInsolvencia").get(0).get("monto").decimalValue()).isEqualByComparingTo("20000");
        assertThat(estado.get("aplicacionesARevisarPorInsolvencia").get(0).get("idOrder").asLong()).as("el número de la venta")
                .isEqualTo(dueno.queryForObject("SELECT id_order FROM orders WHERE tenant_id = ? AND idempotency_key = 'rev-venta'", Long.class, T));
        assertThat(saldoDeLaVenta("rev-venta")).as("no se deshace solo").isEqualByComparingTo("30000");
    }

    // ---------------------------------------------------------------- anular

    @Test
    @DisplayName("🔴 anular un recibo del que ya se devolvió saldo a favor: 409 SALDO_A_FAVOR_YA_DEVUELTO, y nada cambia")
    void anularConDevolucion() throws Exception {
        String recibo = abonarConSaldoAFavor("an-abono");
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "CLIENTE_LO_PIDIO", null, "an-devolucion")).andExpect(status().isCreated());
        BigDecimal libroAntes = libro(TIENDA);

        mockMvc.perform(post("/api/cartera/recibos/" + recibo + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isConflict())
                .andExpect(ConflictoConMensaje.de("SALDO_A_FAVOR_YA_DEVUELTO"));
        assertThat(contar("SELECT count(*) FROM recibos_de_caja WHERE tenant_id = ? AND anula_recibo_id IS NOT NULL", T)).isZero();
        assertThat(libro(TIENDA)).isEqualByComparingTo(libroAntes);
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("🔴 anular el recibo que pagó una factura la reabre, y el saldo a favor de otro recibo se aplica en esa misma llamada")
    void anularReabreYAplica() throws Exception {
        String pago = leer(abonar(abono(TIENDA, 100000, "re-pago", null)).andExpect(status().isCreated())).get("id").asText();
        // Ya no debe nada: todo el segundo abono queda a su favor.
        abonar(abono(TIENDA, 30000, "re-anticipo", "SALDO_A_FAVOR"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.saldoAFavor").value(30000));

        mockMvc.perform(post("/api/cartera/recibos/" + pago + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        assertThat(facturasVivas(TIENDA)).isEqualByComparingTo("70000");
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isZero();
        assertThat(libro(TIENDA)).isEqualByComparingTo("70000");
    }

    // ---------------------------------------------------------------- caras restrictivas

    @Test
    @DisplayName("🔴 devolver es solo del admin; el admin de otro negocio no encuentra al cliente")
    void soloAdmin() throws Exception {
        abonarConSaldoAFavor("adm-abono");
        devolver(CAJA, "cajero", TIENDA, devolucion(20000, "CLIENTE_LO_PIDIO", null, "adm-caja")).andExpect(status().isForbidden());
        devolver(VENDEDOR, "vendedor", TIENDA, devolucion(20000, "CLIENTE_LO_PIDIO", null, "adm-vendedor")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/saldo-a-favor/devolver")
                        .header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO)).contentType(MediaType.APPLICATION_JSON)
                        .content(devolucion(20000, "CLIENTE_LO_PIDIO", null, "adm-otro")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("clienteDocumento"));
        assertThat(contar("SELECT count(*) FROM egresos_de_cartera WHERE tenant_id IN (?, ?)", T, OTRO)).isZero();
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("control negativo: la venta a crédito de la Vecina, sin saldo a favor, no toca el saldo de la Tienda (0 aplicado, factura entera)")
    void elSaldoDeOtroClienteNoSeAplica() throws Exception {
        abonarConSaldoAFavor("ctl-abono");
        vender(ventaACredito(VECINA, "ctl-venta", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(0))
                .andExpect(jsonPath("$.saldoAFavorRestante").value(0));
        assertThat(saldoDeLaVenta("ctl-venta")).isEqualByComparingTo("50000");
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO'", T)).isZero();
    }

    @Test
    @DisplayName("control: una venta de contado no dice nada de saldo a favor (null), aunque el cliente lo tenga")
    void ventaDeContado() throws Exception {
        abonarConSaldoAFavor("cont-abono");
        vender(ventaACredito(TIENDA, "cont-venta", false).replace("\"CREDITO\"", "\"CASH\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").doesNotExist())
                .andExpect(jsonPath("$.saldoAFavorRestante").doesNotExist());
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
    }

    // ---------------------------------------------------------------- F4.13 (d): a quién y cómo

    private static String mercancia(int monto, String clave, String renglones) {
        return "{\"monto\":" + monto + ",\"motivo\":\"CLIENTE_LO_PIDIO\",\"forma\":\"MERCANCIA\",\"idempotencyKey\":\"" + clave + "\","
                + "\"mercancia\":[" + renglones + "]}";
    }

    private void etapaDeLaTienda(String etapa) throws Exception {
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"etapa\":\"" + etapa + "\",\"fecha\":\"" + hoy + "\",\"documento\":\"Auto\",\"informadoPor\":\"abogado\""
                                + ("INICIO".equals(etapa) ? ",\"numeroProceso\":\"2026-0913\"" : "") + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("🔴 F4.13 (d): devolver en mercancía (producto inactivo y sin precio) deja su documento, no toca ninguna venta y el cierre de caja no la cuenta")
    void devolverEnMercancia() throws Exception {
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, 'Aceite viejo', 0, false)",
                "aceite-viejo-" + T, T);
        abonarConSaldoAFavor("m-abono");
        BigDecimal efectivoAntes = preview().get("totalExpectedCash").decimalValue();

        JsonNode egreso = leer(devolver(ADMIN, "admin", TIENDA, mercancia(15000, "m-devolucion",
                        "{\"productoId\":\"aceite-viejo-" + T + "\",\"cantidad\":3,\"valorUnitario\":5000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.forma").value("MERCANCIA"))
                .andExpect(jsonPath("$.medio").value("MERCANCIA"))
                .andExpect(jsonPath("$.beneficiario").value("DEUDOR"))
                .andExpect(jsonPath("$.mercancia[0].productoNombre").value("Aceite viejo"))
                .andExpect(jsonPath("$.mercancia[0].valor").value(15000))
                .andExpect(jsonPath("$.saldoAFavorQueda").value(5000)));
        String fecha = hoy.format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", java.util.Locale.forLanguageTag("es-CO")));
        assertThat(egreso.get("documentoDeSatisfaccion").asText()).isEqualTo(fecha
                + " · Entrega de mercancía como pago del saldo a favor, comprobante de egreso N.º 1, al cliente Tienda (" + TIENDA
                + "): 3 × Aceite viejo a $5.000 = $15.000. Total $15.000. No es una venta ni se descuenta de una venta. "
                + "El descuento de inventario se registra aparte.");
        JsonNode estado = estadoDeCuenta(TIENDA);
        assertThat(estado.get("egresos").get(0).get("mercancia")).hasSize(1);
        assertThat(estado.get("egresos").get(0).get("documentoDeSatisfaccion").asText()).startsWith(fecha);

        // Documental: ni orden, ni venta, ni intención de inventario; el cierre sigue contando solo efectivo.
        assertThat(contar("SELECT count(*) FROM orders WHERE tenant_id = ?", T)).isZero();
        assertThat(contar("SELECT count(*) FROM inventario_intenciones WHERE tenant_id = ?", T)).isZero();
        JsonNode cierre = preview();
        assertThat(cierre.get("devolucionesSaldoAFavorEfectivo").decimalValue()).isZero();
        assertThat(cierre.get("totalExpectedCash").decimalValue()).isEqualByComparingTo(efectivoAntes);
        assertThat(libro(TIENDA)).isEqualByComparingTo("-5000");
    }

    @Test
    @DisplayName("🔴 F4.13 (d): la mercancía se valida: suma igual al monto, producto del negocio, cantidad y valor mayores que 0; dinero sin productos")
    void mercanciaValidada() throws Exception {
        dueno.update("INSERT INTO menu_products (id_product, tenant_id, name_product, price, active) VALUES (?, ?, 'Del otro', 1000, true)",
                "del-otro-" + T, OTRO);
        abonarConSaldoAFavor("mv-abono");
        devolver(ADMIN, "admin", TIENDA, mercancia(15000, "mv-1", "{\"productoId\":\"" + PRODUCTO + "\",\"cantidad\":1,\"valorUnitario\":5000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("mercancia"))
                .andExpect(jsonPath("$.message").value("Los productos suman $5.000 y la devolución es de $15.000: tienen que ser iguales."));
        devolver(ADMIN, "admin", TIENDA, mercancia(1000, "mv-2", "{\"productoId\":\"del-otro-" + T + "\",\"cantidad\":1,\"valorUnitario\":1000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("mercancia[0].productoId"));
        devolver(ADMIN, "admin", TIENDA, mercancia(1000, "mv-3", "{\"productoId\":\"" + PRODUCTO + "\",\"cantidad\":0,\"valorUnitario\":1000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("mercancia[0].cantidad"));
        devolver(ADMIN, "admin", TIENDA, mercancia(1000, "mv-4", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("mercancia"));
        devolver(ADMIN, "admin", TIENDA, devolucion(1000, "CLIENTE_LO_PIDIO", null, "mv-5").replace("}", ",\"forma\":\"TRUEQUE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("forma"))
                .andExpect(jsonPath("$.message").value("La forma de la devolución es dinero o mercancía."));
        devolver(ADMIN, "admin", TIENDA, devolucion(1000, "CLIENTE_LO_PIDIO", null, "mv-6")
                        .replace("}", ",\"mercancia\":[{\"productoId\":\"" + PRODUCTO + "\",\"cantidad\":1,\"valorUnitario\":1000}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("mercancia"));
        assertThat(contar("SELECT count(*) FROM egresos_de_cartera WHERE tenant_id = ?", T)).isZero();
    }

    @Test
    @DisplayName("🔴 F4.13 (d): en liquidación solo el liquidador recibe (409 al cliente), con nombre y documento; y el liquidador solo en liquidación")
    void enLiquidacionAlLiquidador() throws Exception {
        abonarConSaldoAFavor("liq-abono");
        String liquidador = ",\"beneficiario\":\"LIQUIDADOR\",\"beneficiarioNombre\":\"Liquidadora S.A.S.\",\"beneficiarioDocumento\":\"900123456\"}";
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "CLIENTE_LO_PIDIO", null, "liq-antes").replace("}", liquidador))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("beneficiario"));
        etapaDeLaTienda("INICIO");
        etapaDeLaTienda("LIQUIDACION");
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-cliente"))
                .andExpect(ConflictoConMensaje.de("BENEFICIARIO_DEBE_SER_EL_LIQUIDADOR"))
                .andExpect(jsonPath("$.message").value("En liquidación el saldo a favor se entrega al liquidador, no al cliente. No se registró nada."));
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-sin-doc")
                        .replace("}", ",\"beneficiario\":\"LIQUIDADOR\",\"beneficiarioNombre\":\"Liquidadora\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("beneficiarioDocumento"));
        // F4.13f (B16): sin el auto que designa al liquidador, 400; con fecha futura, 400.
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-sin-auto").replace("}", liquidador))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("autoDesignacionLiquidador.numero"))
                .andExpect(jsonPath("$.message").value("Falta el número del auto que designa al liquidador."));
        String conAuto = liquidador.replace("}", ",\"autoDesignacionLiquidador\":{\"numero\":\"400-77\"}}");
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-sin-fecha").replace("}", conAuto))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("autoDesignacionLiquidador.fecha"))
                .andExpect(jsonPath("$.message").value("Falta la fecha del auto que designa al liquidador."));
        String futuro = liquidador.replace("}", ",\"autoDesignacionLiquidador\":{\"numero\":\"400-77\",\"fecha\":\"" + hoy.plusDays(1) + "\"}}");
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-futuro").replace("}", futuro))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("autoDesignacionLiquidador.fecha"))
                .andExpect(jsonPath("$.message").value("La fecha del auto que designa al liquidador no puede ser posterior a hoy. No se registró nada."));
        String bueno = liquidador.replace("}", ",\"autoDesignacionLiquidador\":{\"numero\":\"400-77\",\"fecha\":\"" + hoy.minusDays(1) + "\"}}");
        devolver(ADMIN, "admin", TIENDA, devolucion(5000, "DEVUELTO_AL_PROCESO", "Oficio 9", "liq-ok").replace("}", bueno))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.beneficiario").value("LIQUIDADOR"))
                .andExpect(jsonPath("$.beneficiarioNombre").value("Liquidadora S.A.S."))
                .andExpect(jsonPath("$.beneficiarioDocumento").value("900123456"))
                .andExpect(jsonPath("$.etapaAlDevolver").value("LIQUIDACION"))
                .andExpect(jsonPath("$.autoDesignacionLiquidador.numero").value("400-77"))
                .andExpect(jsonPath("$.autoDesignacionLiquidador.fecha").value(hoy.minusDays(1).toString()))
                .andExpect(jsonPath("$.documentoDeSatisfaccion").isEmpty());
        assertThat(contar("SELECT count(*) FROM egresos_de_cartera WHERE tenant_id = ?", T)).isEqualTo(1);
        // En mercancía, el documento de satisfacción nombra el auto de designación.
        String fechaLargaAuto = hoy.minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", java.util.Locale.forLanguageTag("es-CO")));
        JsonNode enMercancia = leer(devolver(ADMIN, "admin", TIENDA, mercancia(5000, "liq-mercancia",
                        "{\"productoId\":\"" + PRODUCTO + "\",\"cantidad\":1,\"valorUnitario\":5000}").replace("\"motivo\":\"CLIENTE_LO_PIDIO\"", "\"motivo\":\"DEVUELTO_AL_PROCESO\",\"referencia\":\"Oficio 9\"")
                        .replace("}]}", "}]" + bueno))
                .andExpect(status().isCreated()));
        assertThat(enMercancia.get("documentoDeSatisfaccion").asText())
                .contains("al liquidador Liquidadora S.A.S. (900123456), designado por auto N.º 400-77 del " + fechaLargaAuto + ": 1 × ");
        JsonNode egresos = estadoDeCuenta(TIENDA).get("egresos");
        assertThat(egresos.findValuesAsText("etapaAlDevolver")).containsOnly("LIQUIDACION");
        assertThat(egresos.findValues("regimenAlDevolver")).allSatisfy(x -> assertThat(x.isNull()).as("la Tienda no informó régimen").isTrue());
    }

    @Test
    @DisplayName("🔴 F4.13 (d): una venta descontada con el saldo a favor, disfrazada, no pasa por ninguna vía en proceso; fuera de proceso la regla normal sí aplica")
    void ventaDescontadaDisfrazada() throws Exception {
        abonarConSaldoAFavor("dis-abono");
        etapaDeLaTienda("INICIO");
        mockMvc.perform(put("/api/cartera/clientes/" + TIENDA + "/insolvencia/credito-posterior").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"habilitado\":true,\"motivo\":\"Acuerdo\"}"))
                .andExpect(status().isOk());
        // Vía 1: la venta a crédito habilitada no se lleva el saldo a favor.
        vender(ventaACredito(TIENDA, "dis-venta", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(0)).andExpect(jsonPath("$.ventaPosteriorAlInicio").value(true));
        String debitoDeLaVenta = dueno.queryForObject("SELECT d.id FROM debt_transactions d JOIN orders o ON o.uuid_id = d.order_uuid "
                + "WHERE o.tenant_id = ? AND o.idempotency_key = 'dis-venta' AND d.type = 'DEBIT'", String.class, T);
        // Vía 2: una aplicación de devolución escrita directo contra la venta, como app_user: la base la rechaza.
        String recibo = dueno.queryForObject("SELECT recibo_id::text FROM v_saldo_a_favor_por_recibo WHERE tenant_id = ? LIMIT 1", String.class, T);
        String credito = dueno.queryForObject("SELECT id FROM debt_transactions WHERE recibo_id = ?::uuid", String.class, recibo);
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), "app_user", "app_pw"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.execute((org.springframework.jdbc.core.ConnectionCallback<Object>) c -> {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + T + "', true)");
                st.execute("INSERT INTO cartera_aplicaciones (tenant_id, recibo_id, credito_tx_id, debito_tx_id, monto, regla, usuario_id) VALUES ('"
                        + T + "', '" + recibo + "', '" + credito + "', '" + debitoDeLaVenta + "', 1000, 'DEVOLUCION_DE_SALDO_A_FAVOR', " + admin + ")");
            } finally {
                c.rollback();
            }
            return null;
        })).rootCause().hasMessageContaining("no una venta");
        // Vía 3: devolver en mercancía y vender: la venta sigue debiendo entera.
        devolver(ADMIN, "admin", TIENDA, mercancia(20000, "dis-mercancia", "{\"productoId\":\"" + PRODUCTO + "\",\"cantidad\":1,\"valorUnitario\":20000}"))
                .andExpect(status().isCreated());
        assertThat(saldoDeLaVenta("dis-venta")).isEqualByComparingTo("50000");
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND debito_tx_id = ?", T, debitoDeLaVenta)).isZero();

        // Control positivo, fuera de proceso: la Vecina con saldo a favor compra a crédito y la regla normal lo aplica.
        abonar(abono(VECINA, 10000, "dis-vecina-abono", "SALDO_A_FAVOR")).andExpect(status().isCreated());
        vender(ventaACredito(VECINA, "dis-vecina-venta", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(10000));
    }

    // ---------------------------------------------------------------- F4.13e: lo anterior de un acuerdo cumplido

    /** La Tienda entra en proceso con su factura vieja viva, deja 20.000 a favor dentro del proceso y el proceso se cierra. */
    private void procesoCerradoCon(String cierre) throws Exception {
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"etapa\":\"INICIO\",\"fecha\":\"" + hoy.minusDays(10) + "\",\"documento\":\"Auto\",\"informadoPor\":\"abogado\",\"numeroProceso\":\"2026-0913\"}"))
                .andExpect(status().isCreated());
        // En proceso y sin deuda posterior, con excedente: todo a favor, sin tocar la vieja (F4.13 b).
        abonar(abono(TIENDA, 20000, cierre + "-anticipo", "SALDO_A_FAVOR")).andExpect(status().isCreated());
        if ("CUMPLIDO_TERMINADO".equals(cierre)) {
            etapaDeLaTienda("ACUERDO_CONFIRMADO");
        }
        etapaDeLaTienda(cierre);
        assertThat(estadoDeCuenta(TIENDA).get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("🔴 F4.13e: cerrado por CUMPLIDO_TERMINADO, el saldo a favor solo cubre la venta nueva; la factura anterior viva no se cruza")
    void acuerdoCumplidoNoCruzaLoAnterior() throws Exception {
        procesoCerradoCon("CUMPLIDO_TERMINADO");
        vender(ventaACredito(TIENDA, "e-venta", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(20000))
                .andExpect(jsonPath("$.saldoAFavorRestante").value(0));
        assertThat(saldoDeLaVenta("e-venta")).isEqualByComparingTo("30000");
        assertThat(dueno.queryForObject("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND order_uuid IS DISTINCT FROM "
                + "(SELECT uuid_id FROM orders WHERE tenant_id = ? AND idempotency_key = 'e-venta') AND cliente_documento = ?",
                BigDecimal.class, T, T, TIENDA)).as("la factura anterior sigue entera").isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("🔴 F4.13e control: cerrado por CORRECCION_DE_ERROR (no hubo proceso), la regla normal vuelve entera y el saldo va a la factura vieja")
    void marcadoPorErrorVuelveLaRegla() throws Exception {
        procesoCerradoCon("CORRECCION_DE_ERROR");
        vender(ventaACredito(TIENDA, "e-venta-error", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoAFavorAplicado").value(0));
        assertThat(saldoDeLaVenta("e-venta-error")).isEqualByComparingTo("50000");
        assertThat(dueno.queryForObject("SELECT saldo FROM v_cartera_por_documento WHERE tenant_id = ? AND cliente_documento = ? "
                + "AND order_uuid IS DISTINCT FROM (SELECT uuid_id FROM orders WHERE tenant_id = ? AND idempotency_key = 'e-venta-error')",
                BigDecimal.class, T, TIENDA, T)).as("la más vieja primero").isEqualByComparingTo("80000");
    }

    // ---------------------------------------------------------------- F4.13b: revertir con rastro

    /** Saldo a favor de 20.000 aplicado SOLO a una venta a crédito de 50.000, antes de saberse la insolvencia. Devuelve la aplicación. */
    private UUID saldoAplicadoALaVenta(String clave) throws Exception {
        abonarConSaldoAFavor(clave + "-abono");
        vender(ventaACredito(TIENDA, clave + "-venta", false)).andExpect(status().isCreated()).andExpect(jsonPath("$.saldoAFavorAplicado").value(20000));
        return dueno.queryForObject("SELECT id FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO'", UUID.class, T);
    }

    private ResultActions revertir(String email, String rol, UUID aplicacion, String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/cartera/aplicaciones/" + aplicacion + "/revertir").header("Authorization", bearer(email, rol))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private void inicioDeLaTienda(LocalDate fecha) throws Exception {
        mockMvc.perform(post("/api/cartera/clientes/" + TIENDA + "/insolvencia/etapas").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"etapa\":\"INICIO\",\"fecha\":\"" + fecha + "\",\"documento\":\"Auto\",\"informadoPor\":\"abogado\",\"numeroProceso\":\"2026-0913\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("🔴 F4.13b: revertir reabre la venta y devuelve el saldo a favor, con rastro; el reintento es 200; la foto no cambia y total_debt tampoco")
    void revertirConRastro() throws Exception {
        UUID aplicacion = saldoAplicadoALaVenta("rev");
        BigDecimal totalDebtAntes = totalDebt(TIENDA);
        inicioDeLaTienda(hoy.minusDays(5));
        JsonNode antes = estadoDeCuenta(TIENDA);
        assertThat(antes.get("aplicacionesARevisarPorInsolvencia")).hasSize(1);
        String huella = leer(mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia/foto").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())).get("huella").asText();

        revertir(CAJA, "cajero", aplicacion, "{\"motivo\":\"x\"}").andExpect(status().isForbidden());
        JsonNode cierreAntes = preview();
        JsonNode hecho = leer(revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"Concepto del abogado\",\"referencia\":\"Acta 12\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monto").value(20000))
                .andExpect(jsonPath("$.motivo").value("Concepto del abogado"))
                .andExpect(jsonPath("$.referencia").value("Acta 12"))
                .andExpect(jsonPath("$.revertidaPor").value("Admin"))
                .andExpect(jsonPath("$.saldoDeLaFactura").value(50000))
                .andExpect(jsonPath("$.saldoAFavor").value(20000)));
        assertThat(saldoDeLaVenta("rev-venta")).isEqualByComparingTo("50000");
        assertThat(totalDebt(TIENDA)).as("la aplicación automática nunca movió total_debt").isEqualByComparingTo(totalDebtAntes);
        revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"Otra vez\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicacionId").value(hecho.get("aplicacionId").asText()))
                .andExpect(jsonPath("$.motivo").value("Concepto del abogado"));
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones_revertidas WHERE tenant_id = ?", T)).isEqualTo(1);

        JsonNode despues = estadoDeCuenta(TIENDA);
        assertThat(despues.get("aplicacionesARevisarPorInsolvencia")).isEmpty();
        assertThat(despues.get("aplicacionesRevertidas")).hasSize(1);
        assertThat(despues.get("aplicacionesRevertidas").get(0).get("referencia").asText()).isEqualTo("Acta 12");
        assertThat(despues.get("saldoAFavor").decimalValue()).isEqualByComparingTo("20000");
        assertThat(leer(mockMvc.perform(get("/api/cartera/clientes/" + TIENDA + "/insolvencia/foto").header("Authorization", bearer(ADMIN, "admin")))
                .andExpect(status().isOk())).get("huella").asText()).as("la foto no cambia").isEqualTo(huella);
        // Los recibos del estado de cuenta marcan la aplicación revertida, con su fecha y el número de la venta.
        JsonNode marcada = null;
        for (JsonNode ap : despues.get("recibos").findValues("aplicaciones")) {
            for (JsonNode x : ap) {
                if (x.get("aplicacionId").asText().equals(aplicacion.toString())) {
                    marcada = x;
                }
            }
        }
        assertThat(marcada).as("la aplicación revertida en su recibo").isNotNull();
        assertThat(marcada.get("revertida").asBoolean()).isTrue();
        assertThat(marcada.get("revertidaEn").asText()).isEqualTo(despues.get("aplicacionesRevertidas").get(0).get("revertidaEn").asText());
        assertThat(marcada.get("idOrder").asLong()).isEqualTo(
                dueno.queryForObject("SELECT id_order FROM orders WHERE tenant_id = ? AND idempotency_key = 'rev-venta'", Long.class, T));

        // El cierre de caja no cambia: revertir no mueve efectivo. Lo que imprime la caja es la respuesta del POST de cierre.
        JsonNode cierreDespues = preview();
        for (String campo : List.of("recaudoCarteraEfectivo", "recaudoComoSaldoAFavorEfectivo", "devolucionesSaldoAFavorEfectivo")) {
            assertThat(cierreDespues.get(campo).decimalValue()).as(campo).isEqualByComparingTo(cierreAntes.get(campo).decimalValue());
        }
        JsonNode cierre = leer(mockMvc.perform(post("/api/closures").header("Authorization", bearer(CAJA, "cajero"))
                        .header("X-User-Name", "Caja").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cashDetail\":{\"bill100k\":1,\"bill50k\":0,\"bill20k\":0,\"bill10k\":0,\"bill5k\":0,"
                                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                                + "\"countedCard\":0,\"countedQr\":0,\"notes\":\"turno\",\"pettyCashExpenses\":[],\"baseForNextDay\":0}"))
                .andExpect(status().isOk()));
        for (String campo : List.of("recaudoCarteraEfectivo", "recaudoComoSaldoAFavorEfectivo", "devolucionesSaldoAFavorEfectivo")) {
            assertThat(cierre.get(campo).decimalValue()).as("POST de cierre: " + campo).isEqualByComparingTo(cierreAntes.get(campo).decimalValue());
        }
        // El saldo que volvió sigue congelado en el proceso: el cobro automático no lo cruza.
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'SALDO_A_FAVOR_AUTOMATICO'", T)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 F4.13b: no se revierte un abono, sin proceso en curso, lo anterior al corte (y B13 no lo lista), ni con el recibo anulado; sin motivo, 400")
    void noSeRevierte() throws Exception {
        UUID aplicacion = saldoAplicadoALaVenta("nor");
        UUID abono = dueno.queryForObject("SELECT id FROM cartera_aplicaciones WHERE tenant_id = ? AND regla = 'MAS_ANTIGUA_PRIMERO' LIMIT 1", UUID.class, T);

        revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("APLICACION_NO_REVERTIBLE"))
                .andExpect(jsonPath("$.message").value("El cliente no tiene un proceso de insolvencia en curso: no hay pagos que revertir por esa causa. No se revirtió nada."));
        // INICIO de HOY: el corte es el registro, después de la aplicación. Es anterior y B13 ya no la lista.
        inicioDeLaTienda(hoy);
        assertThat(estadoDeCuenta(TIENDA).get("aplicacionesARevisarPorInsolvencia")).as("B13 con la línea del corte").isEmpty();
        revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("APLICACION_NO_REVERTIBLE"))
                .andExpect(jsonPath("$.message").value("Ese pago se aplicó antes del inicio del proceso ("
                        + hoy.format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", java.util.Locale.forLanguageTag("es-CO")))
                        + "): ya está en la deuda al inicio, que se reclama dentro del proceso. No se revirtió nada."));
        revertir(ADMIN, "admin", abono, "{\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("APLICACION_NO_REVERTIBLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Aquí solo se revierten los pagos que el sistema aplicó solo")));
        revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campo").value("motivo"))
                .andExpect(jsonPath("$.message").value("Falta el motivo: escribe por qué se revierte. No se revirtió nada."));
        revertir(ADMIN, "admin", UUID.randomUUID(), "{\"motivo\":\"x\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("id"));
        mockMvc.perform(post("/api/cartera/aplicaciones/" + aplicacion + "/revertir").header("Authorization", bearer(ADMIN_OTRO, "admin", OTRO))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campo").value("id"));
        assertThat(contar("SELECT count(*) FROM cartera_aplicaciones_revertidas WHERE tenant_id IN (?, ?)", T, OTRO)).isZero();
    }

    @Test
    @DisplayName("🔴 F4.13b: con el recibo del saldo a favor anulado, la aplicación ya no cuenta y no se revierte")
    void reciboAnuladoNoSeRevierte() throws Exception {
        UUID aplicacion = saldoAplicadoALaVenta("anu");
        String recibo = dueno.queryForObject("SELECT recibo_id::text FROM cartera_aplicaciones WHERE id = ?", String.class, aplicacion);
        long numero = dueno.queryForObject("SELECT numero FROM recibos_de_caja WHERE id = ?::uuid", Long.class, recibo);
        inicioDeLaTienda(hoy.minusDays(5));
        mockMvc.perform(post("/api/cartera/recibos/" + recibo + "/anular").header("Authorization", bearer(ADMIN, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"ERROR_DE_MONTO\"}"))
                .andExpect(status().isCreated());
        revertir(ADMIN, "admin", aplicacion, "{\"motivo\":\"x\"}").andExpect(ConflictoConMensaje.de("APLICACION_NO_REVERTIBLE"))
                .andExpect(jsonPath("$.message").value("El recibo N.º " + numero + " de ese saldo a favor está anulado: ese pago ya no cuenta y no hay nada que revertir. No se revirtió nada."));
    }
}
