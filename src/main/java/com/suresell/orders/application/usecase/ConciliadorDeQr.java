package com.suresell.orders.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.suresell.orders.domain.model.ResultadoQr;
import com.suresell.orders.infrastructure.web.TokenDeLaPeticion;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Resuelve el monto de QR de un día contra `ms-core-app`, diciendo SIEMPRE de
 * dónde salió el número.
 *
 * <h3>El incidente que originó esta clase</h3>
 *
 * El cierre consultaba `/qr-payments/by-date` con
 * {@code restTemplate.getForEntity(url, JsonNode.class)} — sin cabeceras. El
 * 2026-07-30 se añadió {@code JwtTenantFilter} a `ms-core-app` y esa ruta pasó a
 * exigir un JWT de negocio, así que empezó a devolver 401. El llamador atrapaba
 * toda excepción, registraba un {@code log.warn} que además culpaba a "posible
 * falta de internet", y **cuadraba el cierre con el valor manual del cajero**.
 *
 * Tres semanas de cierres cuadrados con otro número, y en la base de datos no
 * quedaba ni rastro de que hubiera pasado algo. Ese es el defecto real: no el
 * 401, sino que el dato resultante fuera indistinguible de uno conciliado.
 *
 * <h3>Qué hace distinto</h3>
 *
 * <ol>
 *   <li><b>Propaga el JWT</b> de la petición en curso ({@link TokenDeLaPeticion}).</li>
 *   <li><b>Tiene timeouts.</b> Sin ellos, un `ms-core-app` lento cuelga el cierre
 *       de caja indefinidamente: el cajero se queda con la pantalla bloqueada y
 *       el local sin poder cerrar.</li>
 *   <li><b>Nunca devuelve un número pelado.</b> Devuelve {@link ResultadoQr}, que
 *       lleva fuente y nivel de confianza (reglas 5 y 6 de
 *       LINEAMIENTOS_DESARROLLO_DATA_FIRST).</li>
 *   <li><b>Distingue "no hay nada" de "no pude saberlo".</b> Ver abajo.</li>
 * </ol>
 *
 * <h3>404 no es un fallo</h3>
 *
 * `ms-core-app` responde 404 cuando no hay pago QR registrado para esa fecha
 * (`QrPaymentController.java:35-37`, un {@code orElse(notFound())}). Eso NO es un
 * error de integración: es la respuesta correcta a "¿hay algo?" cuando no hay
 * nada. Se registra como {@code manual_cajero}, porque el número que acaba en el
 * cierre es el del cajero.
 *
 * No se registra como {@code conciliado_core} con monto cero: un 404 significa
 * "no hay registro", no "el registro dice cero". Afirmar una conciliación que no
 * ocurrió es exactamente lo que esta clase viene a impedir.
 *
 * <p>Hasta ahora los dos casos —404 y 401— caían en el mismo {@code catch} y
 * producían el mismo resultado. Separarlos es lo que hace el problema detectable.
 */
@Log4j2
@Component
public class ConciliadorDeQr {

    /**
     * Timeouts deliberadamente cortos. Esto corre dentro de la transacción del
     * cierre, con el cajero esperando: es preferible cerrar marcando
     * `fallo_integracion` que dejar el local sin poder cerrar la caja. El peor
     * caso posible es la suma de los dos, ~8 s.
     */
    static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(3);
    static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(5);

    private final RestTemplate restTemplate;
    private final TokenDeLaPeticion token;

    /**
     * En la nube sale SOLO de {@code SYNC_CLOUD_CORE_URL} ({@code application-cloud.yml}):
     * sin ella queda vacía, no en {@code localhost}. El {@code localhost} por defecto
     * fallaba en silencio en staging con «Connection refused» (DrDev002, 2026-09-14).
     * El {@code localhost} de aquí solo lo ve el perfil local.
     */
    @Value("${sync.cloud.core-url:http://localhost:8083/api/core}")
    private String coreApiUrl;

    static final String FALTA_LA_URL = "falta SYNC_CLOUD_CORE_URL: el servicio no sabe dónde está ms-core-app";

    /**
     * Sin URL el servicio ARRANCA igual: el cierre se completa sin conciliar, como
     * hoy. Negarse a arrancar tumbaría las ventas en cualquier entorno donde falte
     * la variable, por un fallo que no rompe nada. Queda un ERROR que la nombra.
     */
    @jakarta.annotation.PostConstruct
    void avisarSiFaltaLaUrl() {
        if (coreApiUrl == null || coreApiUrl.isBlank()) {
            log.error("Conciliación de QR sin destino: {}. Los cierres quedarán como fallo_integracion "
                    + "hasta que se ponga la variable.", FALTA_LA_URL);
        }
    }

    /**
     * El {@code @Autowired} es obligatorio: hay dos constructores y Spring solo
     * elige solo cuando hay uno. Sin esto el contexto no levanta.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ConciliadorDeQr(TokenDeLaPeticion token) {
        this(token, conTimeouts());
    }

    /** Para los tests: permite inyectar un RestTemplate con MockRestServiceServer. */
    ConciliadorDeQr(TokenDeLaPeticion token, RestTemplate restTemplate) {
        this.token = token;
        this.restTemplate = restTemplate;
    }

    /** Para los tests, que no pasan por la inyección de {@code @Value}. */
    void fijarUrlDeCore(String url) {
        this.coreApiUrl = url;
    }

    private static RestTemplate conTimeouts() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(TIMEOUT_CONEXION);
        fabrica.setReadTimeout(TIMEOUT_LECTURA);
        return new RestTemplate(fabrica);
    }

    /**
     * @param fecha           día que se está cerrando
     * @param valorDelCajero  lo que tecleó el cajero; es el respaldo cuando no
     *                        hay conciliación posible
     */
    /**
     * @param fecha          día que se está cerrando
     * @param valorDelCajero lo que tecleó el cajero
     * @param valorDelPos    suma de las ventas del día con `payment_method = 'QR'`.
     *                       El único de los tres que existe siempre
     */
    public ResultadoQr resolver(LocalDate fecha, BigDecimal valorDelCajero, BigDecimal valorDelPos) {
        if (coreApiUrl == null || coreApiUrl.isBlank()) {
            log.warn("Cierre: QR sin conciliar ({}). Se usa el valor del cajero.", FALTA_LA_URL);
            return ResultadoQr.fallo(valorDelCajero, valorDelPos, FALTA_LA_URL);
        }
        String url = coreApiUrl + "/qr-payments/by-date?date=" + fecha;
        try {
            ResponseEntity<JsonNode> respuesta = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(cabeceras()), JsonNode.class);

            if (!respuesta.getStatusCode().is2xxSuccessful() || respuesta.getBody() == null) {
                return ResultadoQr.fallo(valorDelCajero, valorDelPos,
                        "Respuesta inesperada de ms-core-app: HTTP " + respuesta.getStatusCode().value()
                                + (respuesta.getBody() == null ? " con cuerpo vacio" : ""));
            }

            JsonNode monto = respuesta.getBody().get("amount");
            if (monto == null || monto.isNull()) {
                return ResultadoQr.fallo(valorDelCajero, valorDelPos,
                        "ms-core-app respondio 200 sin el campo 'amount'");
            }

            BigDecimal deCore = new BigDecimal(monto.asText());

            // ⚠️ LA CUENTA DEL CAJERO MANDA. SIEMPRE.
            //
            // Antes esto tenía dos ramas: una regla dura para el caso de que
            // `ms-core-app` respondiera cero, y por defecto el valor de core
            // sustituyendo al del cajero. Esa segunda rama es el defecto: bastaba
            // que core respondiera un monto distinto de cero para que el total
            // del cierre dejara de ser lo que el cajero contó.
            //
            // El caso parcial es el que nadie cubría. Si alguien registra
            // $200.000 en `qr_payments` de un día de $600.000 reales, el cierre
            // mostraba un faltante de $400.000 que no existe. Y hay antecedente:
            // entre mayo y junio se registraron tres agregados MENSUALES en esa
            // tabla y después se dejó de hacer. Esa tabla no es un registro
            // transaccional y no puede gobernar un cuadre de caja.
            //
            // Así que el arreglo es quitar la rama, no añadir un umbral: no hay
            // diferencia "aceptable" a partir de la cual core deba mandar, porque
            // el problema no es el tamaño de la diferencia sino que la fuente no
            // es fiable para esto.
            //
            // `qr_conciliado_core` SE SIGUE GUARDANDO. Pierde la autoridad, no la
            // existencia: junto con `qr_pos` es la métrica de control interno que
            // permite ver si el registro del administrador va al día.
            if (esPositivo(valorDelCajero)) {
                if (deCore.compareTo(valorDelCajero) != 0) {
                    log.warn("Cierre {}: ms-core-app reporta {} en QR y el cajero contó {}. "
                            + "Manda el del cajero; el conciliado queda guardado como información.",
                            fecha, deCore, valorDelCajero);
                }
                return ResultadoQr.manualConConciliado(valorDelCajero, valorDelPos, deCore);
            }

            // Sin cuenta del cajero no hay nada a lo que el conciliado pueda
            // sobreponerse, así que aquí sí es la mejor fuente disponible. El
            // caso de los dos en cero cae aquí y es una conciliación legítima:
            // core dice que no hubo QR y el cajero tampoco contó nada.
            ResultadoQr conciliado = ResultadoQr.conciliado(deCore, valorDelPos, valorDelCajero);
            log.info("Cierre {}: sin cuenta del cajero; QR conciliado contra ms-core-app = {}",
                    fecha, conciliado.monto());
            return conciliado;

        } catch (HttpClientErrorException.NotFound e) {
            // Caso legítimo: no hay pago QR registrado ese día. No es un fallo —
            // y con `qr_payments` en tres filas históricas, es lo NORMAL.
            log.info("Cierre: ms-core-app no tiene pago QR para {}; se usa el valor del cajero", fecha);
            return sinConciliacion(valorDelCajero, valorDelPos);

        } catch (Exception e) {
            String detalle = describir(e);
            // WARN y no ERROR: el cierre se completa. Lo que hace este problema
            // detectable no es el log —que ya existía y no sirvió de nada— sino
            // la columna qr_fuente que queda en la fila.
            log.warn("Cierre: no se pudo conciliar el QR contra ms-core-app ({}). "
                    + "Se usa el valor del cajero y el cierre queda marcado como fallo_integracion.", detalle);
            return ResultadoQr.fallo(valorDelCajero, valorDelPos, detalle);
        }
    }

    /**
     * No hubo conciliación externa. Se elige entre el valor del cajero y el del
     * POS, y se deja constancia de cuál se usó.
     *
     * <p>Manda el del cajero cuando existe: es el que el negocio cuenta y con el
     * que cierra hoy, y este cambio NO altera el monto con el que cierra el
     * local. Si el cajero no puso nada, el del POS es mejor que cero — sale de
     * las ventas mismas.
     */
    private ResultadoQr sinConciliacion(BigDecimal valorDelCajero, BigDecimal valorDelPos) {
        if (esPositivo(valorDelCajero)) {
            return ResultadoQr.manual(valorDelCajero, valorDelPos);
        }
        if (esPositivo(valorDelPos)) {
            return ResultadoQr.delPos(valorDelPos, valorDelCajero);
        }
        // Ni cajero ni POS: no hubo QR ese dia. Se registra como manual en cero,
        // que es la verdad, y no como una conciliacion que no ocurrio.
        return ResultadoQr.manual(valorDelCajero, valorDelPos);
    }

    private static boolean esPositivo(BigDecimal valor) {
        return valor != null && valor.compareTo(BigDecimal.ZERO) > 0;
    }

    private HttpHeaders cabeceras() {
        HttpHeaders cabeceras = new HttpHeaders();
        // Sin token no se inventa nada: la llamada sale sin credencial, recibe
        // 401 y el cierre queda marcado como fallo_integracion, que es la verdad.
        token.cabeceraAuthorization()
                .ifPresent(valor -> cabeceras.set(HttpHeaders.AUTHORIZATION, valor));
        return cabeceras;
    }

    /**
     * Mensaje técnico REAL del fallo, con el código HTTP cuando lo hay. Nunca una
     * explicación inventada: el mensaje anterior decía "posible falta de
     * internet" y mandó a buscar el problema donde no estaba durante tres
     * semanas.
     *
     * <p>Se recorta a 500 caracteres: la columna es para diagnosticar, no para
     * volcar trazas.
     */
    private String describir(Exception e) {
        String texto;
        if (e instanceof HttpClientErrorException http) {
            HttpStatus estado = HttpStatus.resolve(http.getStatusCode().value());
            texto = "HTTP " + http.getStatusCode().value()
                    + (estado != null ? " " + estado.getReasonPhrase() : "")
                    + " de ms-core-app";
            if (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403) {
                texto += " (el JWT no llego o no es valido)";
            }
        } else {
            texto = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        return texto.length() > 500 ? texto.substring(0, 500) : texto;
    }
}
