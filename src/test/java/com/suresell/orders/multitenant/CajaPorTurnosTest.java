package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V55 — Caja por turnos, por la API real y contra la cadena Flyway entera.
 *
 * <p>Lo que fija (contrato CAJA-POR-TURNOS-Y-REGISTRO-EN-CAJA §3):
 * <ul>
 *   <li>Dos cierres el mismo día son turnos 1 y 2; ya no hay «la caja de hoy
 *       ya está cerrada».
 *   <li>El segundo turno arranca donde cerró el primero —ventana y base— aunque
 *       haya sido hace un minuto.
 *   <li>La base del cierre: la declarada manda; sin declarar, {@code base_caja}
 *       del negocio; sin ella, 0.
 * </ul>
 *
 * <p>El QR se concilia contra ms-core-app; aquí esa URL apunta a un puerto
 * cerrado, así que el cierre queda como {@code fallo_integracion} con su
 * motivo — que es exactamente lo que haría en producción sin el core, y no
 * cambia nada de lo que se mide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("cloud")
@Testcontainers
class CajaPorTurnosTest {

    static final String SECRET = "clave-de-prueba-multitenant-min-32-bytes!!";
    static final String TENANT = "negocio-turnos";

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
        // Puerto 9 (discard) cerrado: la conciliación de QR falla en el acto.
        r.add("sync.cloud.core-url", () -> "http://127.0.0.1:9/api/core");
    }

    @Autowired MockMvc mockMvc;
    final ObjectMapper json = new ObjectMapper();

    private String bearer() {
        return "Bearer " + Jwts.builder()
                .subject("cajera@" + TENANT)
                .claim("tenant_id", TENANT)
                .claim("role", "cajero")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @BeforeEach
    void sembrar() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM daily_closures WHERE tenant_id = '" + TENANT + "'");
            s.execute("DELETE FROM sites WHERE tenant_id = '" + TENANT + "'");
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + TENANT + "','T','pro') ON CONFLICT (id) DO NOTHING");
            s.execute("INSERT INTO sites (tenant_id, name, code, flujo_de_venta, is_default) "
                    + "VALUES ('" + TENANT + "', 'Principal', 'PRINCIPAL', 'DIRECTO', true)");
        }
    }

    private void baseDelNegocio(String base) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("UPDATE sites SET base_caja = " + base + " WHERE tenant_id = '" + TENANT + "'");
        }
    }

    /** Cierra con dos billetes de 50k contados (el conteo en ceros no pasa) y la base indicada. */
    private JsonNode cerrar(String baseForNextDay) throws Exception {
        String base = baseForNextDay == null ? "" : ",\"baseForNextDay\":" + baseForNextDay;
        String cuerpo = "{\"cashDetail\":{\"bill100k\":0,\"bill50k\":2,\"bill20k\":0,\"bill10k\":0,\"bill5k\":0,"
                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                + "\"countedCard\":0,\"countedQr\":0,\"notes\":\"turno\",\"pettyCashExpenses\":[]" + base + "}";
        MvcResult r = mockMvc.perform(post("/api/closures")
                        .header("Authorization", bearer())
                        .header("X-User-Name", "Cajero 1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode preview() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/closures/preview").header("Authorization", bearer())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private record Fila(int turno, LocalDateTime apertura, LocalDateTime cierre, BigDecimal base,
                        BigDecimal esperadoEfectivo) {
    }

    private List<Fila> cierres() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT turno, opening_time, closing_time, base_balance_for_next_day, "
                     + "total_expected_cash FROM daily_closures WHERE tenant_id = '" + TENANT + "' ORDER BY closing_time")) {
            while (rs.next()) {
                filas.add(new Fila(rs.getInt(1), rs.getTimestamp(2).toLocalDateTime(),
                        rs.getTimestamp(3).toLocalDateTime(), rs.getBigDecimal(4), rs.getBigDecimal(5)));
            }
        }
        return filas;
    }

    // =====================================================================

    @Test
    @DisplayName("🔴 dos cierres el mismo día son turnos 1 y 2; el segundo arranca con la base y la hora del primero")
    void dosTurnosElMismoDia() throws Exception {
        JsonNode antes = preview();
        assertThat(antes.get("turno").asInt()).isEqualTo(1);
        assertThat(antes.get("cierresHoy").asInt()).isZero();
        assertThat(antes.has("alreadyClosed")).as("el contrato lo retira").isFalse();

        JsonNode primero = cerrar("150000");
        assertThat(primero.get("turno").asInt()).isEqualTo(1);
        assertThat(primero.get("baseToKeep").decimalValue()).isEqualByComparingTo("150000");

        // El preview ya describe el turno 2, abierto desde el cierre del 1 y con su base.
        JsonNode entre = preview();
        assertThat(entre.get("turno").asInt()).isEqualTo(2);
        assertThat(entre.get("cierresHoy").asInt()).isEqualTo(1);
        assertThat(entre.get("baseInicial").decimalValue()).isEqualByComparingTo("150000");
        assertThat(entre.get("previousBaseBalance").decimalValue())
                .as("se conserva para los POS que ya lo leían").isEqualByComparingTo("150000");

        // Antes de V55 esto era un 409 «la caja de hoy ya fue cerrada».
        JsonNode segundo = cerrar("100000");
        assertThat(segundo.get("turno").asInt()).isEqualTo(2);

        List<Fila> filas = cierres();
        assertThat(filas).extracting(Fila::turno).containsExactly(1, 2);
        Fila uno = filas.get(0);
        Fila dos = filas.get(1);
        // La ventana del turno 2 empieza donde cerró el 1, aunque sea de hoy.
        assertThat(dos.apertura()).isEqualTo(uno.cierre());
        assertThat(LocalDateTime.parse(entre.get("abiertoDesde").asText()).truncatedTo(ChronoUnit.SECONDS))
                .as("abiertoDesde = closing_time del último cierre de hoy")
                .isEqualTo(uno.cierre().truncatedTo(ChronoUnit.SECONDS));
        // Y su base inicial es la que dejó el 1: sin ventas, lo esperado en
        // efectivo es exactamente esa base.
        assertThat(dos.esperadoEfectivo()).isEqualByComparingTo("150000");
        assertThat(dos.base()).isEqualByComparingTo("100000");

        assertThat(preview().get("turno").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 la base declarada por el cajero manda sobre la del negocio")
    void laBaseDeclaradaManda() throws Exception {
        baseDelNegocio("200000");

        JsonNode r = cerrar("50000");

        assertThat(r.get("baseToKeep").decimalValue()).isEqualByComparingTo("50000");
        assertThat(r.get("amountToDeposit").decimalValue())
                .as("contado 100.000 − base 50.000").isEqualByComparingTo("50000");
        assertThat(cierres().get(0).base()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("🔴 sin base declarada se usa base_caja; y el primer turno de la historia arranca con ella")
    void sinBaseDeclaradaUsaLaDelNegocio() throws Exception {
        baseDelNegocio("200000");

        JsonNode antes = preview();
        assertThat(antes.get("baseSugerida").decimalValue()).isEqualByComparingTo("200000");
        assertThat(antes.get("baseInicial").decimalValue())
                .as("sin cierre anterior, la base inicial es la configurada").isEqualByComparingTo("200000");

        JsonNode r = cerrar(null);

        assertThat(r.get("baseToKeep").decimalValue()).isEqualByComparingTo("200000");
        Fila fila = cierres().get(0);
        assertThat(fila.base()).isEqualByComparingTo("200000");
        assertThat(fila.esperadoEfectivo()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("sin base declarada ni configurada, la base es 0 (lo de siempre)")
    void sinNingunaBaseEsCero() throws Exception {
        JsonNode antes = preview();
        assertThat(antes.get("baseSugerida").decimalValue()).isEqualByComparingTo("0");
        assertThat(antes.get("baseInicial").decimalValue()).isEqualByComparingTo("0");

        assertThat(cerrar(null).get("baseToKeep").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("una base negativa es 400 con campo baseForNextDay, y no deja fila")
    void baseNegativa() throws Exception {
        String cuerpo = "{\"cashDetail\":{\"bill100k\":0,\"bill50k\":2,\"bill20k\":0,\"bill10k\":0,\"bill5k\":0,"
                + "\"bill2k\":0,\"coin1000\":0,\"coin500\":0,\"coin200\":0,\"coin100\":0,\"coin50\":0},"
                + "\"countedCard\":0,\"countedQr\":0,\"baseForNextDay\":-1}";
        MvcResult r = mockMvc.perform(post("/api/closures")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode err = json.readTree(r.getResponse().getContentAsString());
        assertThat(err.get("campo").asText()).isEqualTo("baseForNextDay");
        assertThat(err.get("mensaje").asText()).isNotBlank();
        assertThat(cierres()).isEmpty();
    }
}
