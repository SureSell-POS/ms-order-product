package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F6.0a: el servidor comprime lo que pasa de 2 KB cuando el cliente lo pide, y a quien no lo pide le responde como siempre.
 * Con Tomcat de verdad (MockMvc no pasa por la compresión) y un cliente HTTP que NO descomprime solo, para ver la cabecera
 * y los bytes tal como viajan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("cloud")
@Testcontainers
class CompresionDeRespuestasTest {

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
        r.add("security.jwt.secret", () -> "clave-de-prueba-multitenant-min-32-bytes!!");
        r.add("auth.reset.link-base", () -> "https://pos-de-prueba.invalid");
    }

    @LocalServerPort int puerto;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private HttpResponse<byte[]> pedir(String ruta, String acceptEncoding) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta)).GET();
        if (acceptEncoding != null) {
            b.header("Accept-Encoding", acceptEncoding);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("💰 F6.0a: una respuesta JSON grande viaja en gzip si el cliente lo pide, y es la misma al descomprimirla")
    void comprimeLoGrandeSiSePide() throws Exception {
        HttpResponse<byte[]> crudo = pedir("/v3/api-docs", null);
        assertThat(crudo.statusCode()).isEqualTo(200);
        assertThat(crudo.headers().firstValue("Content-Encoding")).as("sin Accept-Encoding, como siempre").isEmpty();
        assertThat(crudo.body().length).as("la prueba necesita una respuesta de más de 2 KB").isGreaterThan(2048);

        HttpResponse<byte[]> gzip = pedir("/v3/api-docs", "gzip");
        assertThat(gzip.statusCode()).isEqualTo(200);
        assertThat(gzip.headers().firstValue("Content-Encoding")).hasValue("gzip");
        byte[] descomprimido;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip.body()))) {
            descomprimido = in.readAllBytes();
        }
        assertThat(new String(descomprimido, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(new String(crudo.body(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(gzip.body().length).as("comprimida pesa menos de la mitad").isLessThan(crudo.body().length / 2);
        System.out.printf("── F6.0a: /v3/api-docs %d B en crudo, %d B en gzip ──%n", crudo.body().length, gzip.body().length);
    }

    @Test
    @DisplayName("F6.0a: sin Accept-Encoding nunca se comprime; con él, lo pequeño sin Content-Length también (así es Tomcat)")
    void loPequeno() throws Exception {
        HttpResponse<byte[]> sinPedir = pedir("/actuator/health", null);
        assertThat(sinPedir.body().length).as("la prueba necesita una respuesta de menos de 2 KB").isLessThan(2048);
        assertThat(sinPedir.headers().firstValue("Content-Encoding")).isEmpty();
        // El mínimo de 2 KB solo se aplica con Content-Length conocido; esta respuesta no lo trae y viaja en gzip igual.
        HttpResponse<byte[]> pidiendo = pedir("/actuator/health", "gzip");
        assertThat(pidiendo.headers().firstValue("Content-Length")).isEmpty();
        assertThat(pidiendo.headers().firstValue("Content-Encoding")).hasValue("gzip");
        System.out.printf("── F6.0a: /actuator/health %d B en crudo, %d B en gzip ──%n", sinPedir.body().length, pidiendo.body().length);
    }
}
