package com.suresell.orders.multitenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las tres formas en que una migración rompe producción, atrapadas en el build.
 *
 * <p>No es un test de estilo. Cada comprobación viene de algo que pasó o que va
 * a pasar en cuanto haya dos personas escribiendo migraciones a la vez.
 *
 * <h2>1 · Editar una migración ya aplicada tumba el servicio</h2>
 *
 * Pasó el 2026-09-02. Se corrigió {@code V18} después de que se hubiera aplicado
 * en producción; Flyway guarda una suma de comprobación al aplicar cada archivo,
 * vio que no coincidía y <b>el servicio se negó a arrancar</b>. Hace bien — pero
 * el síntoma es un servicio caído, no un aviso.
 *
 * <p>Aquí se detecta antes de desplegar, que es donde se puede arreglar barato.
 *
 * <h2>2 · Dos personas pueden crear el mismo número sin que git se queje</h2>
 *
 * {@code V23__una_cosa.sql} y {@code V23__otra_cosa.sql} son archivos
 * <b>distintos</b>: git los fusiona sin conflicto y la revisión no ve nada raro.
 * Flyway se niega a arrancar con <i>«Found more than one migration with version
 * 23»</i> — y eso se descubre desplegando.
 *
 * <p>Es el defecto favorito del desarrollo en paralelo: no falla en el momento
 * de crearlo, falla en el peor momento posible.
 *
 * <h2>3 · Una migración fuera de su rango es una colisión futura</h2>
 *
 * Los rangos reservados viven en {@code MIGRACIONES.txt}. Escribir fuera del
 * rango propio funciona hoy y colisiona el día que la otra vertical llegue a ese
 * número.
 */
public class GuardaDeMigracionesTest {

    /**
     * La carpeta de la cadena que se guarda. La cadena {@code pedidos} (plan de
     * mayoristas, F0.9) hereda esta misma guarda sobrescribiéndola; una copia
     * del test se habría desincronizado a la primera corrección.
     */
    protected Path carpeta() {
        return Paths.get("src/main/resources/db/migration");
    }

    /** Dónde se deja el manifiesto sugerido; uno por cadena para que no se pisen. */
    protected Path ayuda() {
        return Paths.get("build/MIGRACIONES.txt.nuevo");
    }

    private Path manifiesto() {
        return carpeta().resolve("MIGRACIONES.txt");
    }
    private static final Pattern NOMBRE = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");
    private static final Pattern RANGO = Pattern.compile("^@rango\\s+(\\d+)-(\\d+)\\s+(\\S+)\\s*$");

    private record Rango(int desde, int hasta, String duenno) {}

    // =====================================================================

    private List<Path> migraciones() throws IOException {
        try (Stream<Path> s = Files.list(carpeta())) {
            return s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(java.util.Comparator.comparingInt(GuardaDeMigracionesTest::version))
                    .toList();
        }
    }

    private static int version(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        if (!m.find()) {
            throw new IllegalStateException("nombre de migración imposible: " + p);
        }
        return Integer.parseInt(m.group(1));
    }

    private static String huella(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> lineasUtiles() throws IOException {
        return Files.readAllLines(manifiesto()).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
    }

    /** El manifiesto tal y como debería ser hoy. Se usa para el mensaje de ayuda. */
    private String manifiestoEsperado() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : Files.readAllLines(manifiesto())) {
            if (l.isBlank() || l.strip().startsWith("#") || l.strip().startsWith("@rango")) {
                sb.append(l).append('\n');
            }
        }
        for (Path p : migraciones()) {
            sb.append(version(p)).append('\t').append(huella(p))
              .append('\t').append(p.getFileName()).append('\n');
        }
        return sb.toString();
    }

    private void escribirAyuda() throws IOException {
        Path destino = ayuda();
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, manifiestoEsperado());
    }

    // =====================================================================

    @Test
    @DisplayName("🔴 ninguna migración ya sellada cambió de contenido")
    void ningunaMigracionSelladaCambio() throws IOException {
        Map<String, String> sellado = new LinkedHashMap<>();
        for (String l : lineasUtiles()) {
            if (l.startsWith("@rango")) {
                continue;
            }
            String[] c = l.split("\\s+");
            sellado.put(c[2], c[1]);
        }

        List<String> problemas = new ArrayList<>();
        for (var e : sellado.entrySet()) {
            Path p = carpeta().resolve(e.getKey());
            if (!Files.exists(p)) {
                problemas.add("BORRADA: " + e.getKey());
            } else if (!huella(p).equals(e.getValue())) {
                problemas.add("MODIFICADA: " + e.getKey());
            }
        }

        if (!problemas.isEmpty()) {
            escribirAyuda();
        }
        assertThat(problemas)
                .as("""
                    Una migración sellada cambió. Si YA se aplicó en algún sitio, NO la \
                    edites: Flyway guarda su suma de comprobación y el servicio se negará \
                    a arrancar — pasó el 2026-09-02 con V18. Escribe una migración NUEVA \
                    que corrija lo anterior. Si de verdad no se ha aplicado en ninguna \
                    parte, copia %s sobre MIGRACIONES.txt.""".formatted(ayuda()))
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 no hay dos migraciones con el mismo número")
    void sinNumerosRepetidos() throws IOException {
        Map<Integer, List<String>> porVersion = new LinkedHashMap<>();
        for (Path p : migraciones()) {
            porVersion.computeIfAbsent(version(p), k -> new ArrayList<>())
                      .add(p.getFileName().toString());
        }
        List<String> repetidas = porVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();

        // Git fusiona dos archivos con el mismo número sin conflicto: son
        // nombres distintos. El fallo aparece al arrancar, en el despliegue.
        assertThat(repetidas)
                .as("dos migraciones con el mismo número: Flyway no arranca. "
                    + "Renumera la tuya a la siguiente libre")
                .isEmpty();
    }

    @Test
    @DisplayName("toda migración está sellada en el manifiesto")
    void todasSelladas() throws IOException {
        List<String> enManifiesto = lineasUtiles().stream()
                .filter(l -> !l.startsWith("@rango"))
                .map(l -> l.split("\\s+")[2])
                .toList();
        List<String> enDisco = migraciones().stream()
                .map(p -> p.getFileName().toString()).toList();

        List<String> sinSellar = enDisco.stream().filter(n -> !enManifiesto.contains(n)).toList();
        if (!sinSellar.isEmpty()) {
            escribirAyuda();
        }
        assertThat(sinSellar)
                .as("migraciones sin sellar. Copia " + ayuda() + " sobre " + manifiesto())
                .isEmpty();
    }

    @Test
    @DisplayName("los rangos reservados no se solapan y cubren todo lo escrito")
    void losRangosSeRespetan() throws IOException {
        List<Rango> rangos = new ArrayList<>();
        for (String l : lineasUtiles()) {
            Matcher m = RANGO.matcher(l);
            if (m.matches()) {
                rangos.add(new Rango(Integer.parseInt(m.group(1)),
                                     Integer.parseInt(m.group(2)), m.group(3)));
            }
        }
        assertThat(rangos).as("el manifiesto tiene que declarar al menos un rango").isNotEmpty();

        // Solapados: dos personas creerían tener el mismo número reservado.
        List<String> solapes = new ArrayList<>();
        for (int i = 0; i < rangos.size(); i++) {
            for (int j = i + 1; j < rangos.size(); j++) {
                Rango a = rangos.get(i);
                Rango b = rangos.get(j);
                if (a.desde() <= b.hasta() && b.desde() <= a.hasta()) {
                    solapes.add(a.duenno() + " y " + b.duenno());
                }
            }
        }
        assertThat(solapes).as("rangos solapados: el reparto no sirve de nada").isEmpty();

        List<String> fuera = new ArrayList<>();
        for (Path p : migraciones()) {
            int v = version(p);
            if (rangos.stream().noneMatch(r -> v >= r.desde() && v <= r.hasta())) {
                fuera.add(p.getFileName().toString());
            }
        }
        assertThat(fuera)
                .as("migración fuera de todo rango reservado. Funciona hoy y colisiona "
                    + "el día que otra vertical llegue a ese número")
                .isEmpty();
    }

    @Test
    @DisplayName("el nombre dice qué hace, en minúsculas y con guiones bajos")
    void nombresLegibles() throws IOException {
        List<String> malos = migraciones().stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> !NOMBRE.matcher(n).matches())
                .toList();

        // `V23__fix.sql` no dice nada seis meses después. El nombre es lo único
        // que se lee al mirar la lista.
        assertThat(malos)
                .as("formato esperado: V<numero>__descripcion_en_minusculas.sql")
                .isEmpty();
    }
}
