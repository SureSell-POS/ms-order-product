package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Plan de mayoristas, F0.3: las rutas de la vertical exigen su módulo en el
 * servidor. Se mide qué pasa la cadena y qué no, sin levantar el contexto.
 */
class ModuleAccessFilterTest {

    private static final String SECRET = "clave-de-prueba-suficientemente-larga-256bits!";
    private final ModuleAccessFilter filtro = new ModuleAccessFilter(new JwtTenantResolver(SECRET));

    private static String token(List<String> modules) {
        JwtBuilder b = Jwts.builder().subject("duena@negocio.invalid").claim("tenant_id", "negocio");
        if (modules != null) {
            b.claim("modules", modules);
        }
        return "Bearer " + b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private record Resultado(boolean pasa, MockHttpServletResponse respuesta) {}

    private Resultado pedir(String metodo, String ruta, List<String> modules) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, ruta);
        req.addHeader("Authorization", token(modules));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(req, res, cadena);
        return new Resultado(cadena.getRequest() != null, res);
    }

    @Test
    @DisplayName("🔴 /api/mayorista sin el módulo: 403 con código estable y el cuerpo de la casa")
    void mayoristaSinElModulo() throws Exception {
        Resultado r = pedir("GET", "/api/mayorista/listas", List.of("ventas", "cartera"));
        assertThat(r.pasa()).isFalse();
        assertThat(r.respuesta().getStatus()).isEqualTo(403);
        assertThat(r.respuesta().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"codigo\":\"MODULO_NO_INCLUIDO\"")
                .contains("\"error\":\"MODULO_NO_INCLUIDO\"")
                .contains("\"mensaje\":")
                .contains("\"modulo\":\"mayorista\"");
    }

    @Test
    @DisplayName("/api/mayorista con el módulo pasa")
    void mayoristaConElModulo() throws Exception {
        assertThat(pedir("POST", "/api/mayorista/clientes", List.of("mayorista")).pasa()).isTrue();
    }

    @Test
    @DisplayName("🔴 guarda estricta: sin el claim `modules` no pasa (la compatible sí dejaba)")
    void sinClaimNoPasa() throws Exception {
        assertThat(pedir("GET", "/api/mayorista/listas", null).respuesta().getStatus()).isEqualTo(403);
        assertThat(pedir("GET", "/api/cartera/resumen", null).pasa()).isFalse();
    }

    @Test
    @DisplayName("🔴 guarda estricta: un claim vacío (negocio sin módulos) no es «token viejo»")
    void claimVacioNoPasa() throws Exception {
        assertThat(pedir("GET", "/api/mayorista/listas", List.of()).pasa()).isFalse();
    }

    @Test
    @DisplayName("cartera, pedidos y ruta: cada una con su módulo")
    void lasOtrasRutasDeLaVertical() throws Exception {
        assertThat(pedir("GET", "/api/cartera/clientes", List.of("mayorista")).pasa()).isFalse();
        assertThat(pedir("GET", "/api/cartera/clientes", List.of("cartera")).pasa()).isTrue();
        assertThat(pedir("GET", "/api/pedidos", List.of("cartera")).pasa()).isFalse();
        assertThat(pedir("GET", "/api/pedidos", List.of("mayorista")).pasa()).isTrue();
        assertThat(pedir("GET", "/api/ruta/hoy", List.of("mayorista", "cartera")).pasa()).isFalse();
        assertThat(pedir("GET", "/api/ruta/hoy", List.of("ruta")).pasa()).isTrue();
    }

    @Test
    @DisplayName("la estricta compara por segmento: /api/rutas-x no es /api/ruta")
    void porSegmentoCompleto() throws Exception {
        assertThat(pedir("GET", "/api/rutas-x", List.of("ventas")).pasa()).isTrue();
        assertThat(pedir("GET", "/api/mayoristas", List.of("ventas")).pasa()).isTrue();
    }

    @Test
    @DisplayName("🔴 la venta del POS (y su outbox) no pasa por la guarda estricta: sin claim sigue vendiendo")
    void laVentaNoSeToca() throws Exception {
        assertThat(pedir("POST", "/orders/create", null).pasa()).isTrue();
        assertThat(pedir("POST", "/orders/create", List.of()).pasa()).isTrue();
    }

    @Test
    @DisplayName("la guarda compatible no cambia: token viejo pasa, sin el módulo no, /api/waiter-sales sigue guardada")
    void laCompatibleNoCambia() throws Exception {
        assertThat(pedir("GET", "/api/discounts", null).pasa()).isTrue();
        assertThat(pedir("GET", "/api/discounts", List.of("ventas")).respuesta().getStatus()).isEqualTo(403);
        assertThat(pedir("GET", "/api/waiter-sales", List.of("ventas")).pasa()).isFalse();
        assertThat(pedir("GET", "/api/waiter-sales", List.of("meseros")).pasa()).isTrue();
    }

    @Test
    @DisplayName("un preflight CORS pasa siempre")
    void preflightPasa() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/mayorista/listas");
        req.addHeader("Origin", "https://pos.invalid");
        req.addHeader("Access-Control-Request-Method", "GET");
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(req, new MockHttpServletResponse(), cadena);
        assertThat(cadena.getRequest()).isNotNull();
    }
}
