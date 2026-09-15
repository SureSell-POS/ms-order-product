package com.suresell.orders.pedidos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * F5.7b: el almacén es Supabase Storage, bucket privado {@code entregas}, por su API REST con la clave de servicio.
 * Las variables {@code SUPABASE_STORAGE_URL} y {@code SUPABASE_STORAGE_KEY} son distintas por entorno; sin ellas el
 * almacén no está configurado. Sin SDK nuevo: {@link HttpClient} del JDK, con tiempo de espera.
 */
@Component
public class AlmacenSupabaseStorage implements AlmacenDePruebas {

    static final String BUCKET = "entregas";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    private final String url;
    private final String clave;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(ESPERA).build();

    public AlmacenSupabaseStorage(@Value("${SUPABASE_STORAGE_URL:}") String url, @Value("${SUPABASE_STORAGE_KEY:}") String clave,
                                  ObjectMapper json) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.clave = clave == null ? "" : clave;
        this.json = json;
    }

    @Override
    public boolean configurado() {
        return !url.isBlank() && !clave.isBlank();
    }

    @Override
    public void subir(String ruta, byte[] contenido, String tipo) {
        enviar(base("/storage/v1/object/" + BUCKET + "/" + ruta).header("Content-Type", tipo).header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(contenido)).build(), "subir " + ruta);
    }

    @Override
    public String firmar(String ruta, int segundos) {
        String cuerpo = enviar(base("/storage/v1/object/sign/" + BUCKET + "/" + ruta).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"expiresIn\":" + segundos + "}")).build(), "firmar " + ruta);
        try {
            JsonNode r = json.readTree(cuerpo);
            String firmada = r.hasNonNull("signedURL") ? r.get("signedURL").asText() : r.path("signedUrl").asText("");
            if (firmada.isBlank()) {
                throw new NoDisponible("El almacén no devolvió la URL firmada de " + ruta, null);
            }
            return firmada.startsWith("http") ? firmada : url + "/storage/v1" + (firmada.startsWith("/") ? "" : "/") + firmada;
        } catch (NoDisponible e) {
            throw e;
        } catch (Exception e) {
            throw new NoDisponible("Respuesta del almacén ilegible al firmar " + ruta, e);
        }
    }

    @Override
    public void borrar(List<String> rutas) {
        if (rutas == null || rutas.isEmpty()) {
            return;
        }
        try {
            String cuerpo = json.writeValueAsString(Map.of("prefixes", rutas));
            enviar(base("/storage/v1/object/" + BUCKET).header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(cuerpo)).build(), "borrar " + rutas.size() + " objetos");
        } catch (NoDisponible e) {
            throw e;
        } catch (Exception e) {
            throw new NoDisponible("No se pudo pedir el borrado al almacén", e);
        }
    }

    private HttpRequest.Builder base(String camino) {
        return HttpRequest.newBuilder(URI.create(url + camino)).timeout(ESPERA)
                .header("Authorization", "Bearer " + clave).header("apikey", clave);
    }

    private String enviar(HttpRequest peticion, String que) {
        try {
            HttpResponse<String> r = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new NoDisponible("El almacén respondió " + r.statusCode() + " al " + que, null);
            }
            return r.body();
        } catch (NoDisponible e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoDisponible("Interrumpido al " + que, e);
        } catch (Exception e) {
            throw new NoDisponible("El almacén no respondió al " + que, e);
        }
    }
}
