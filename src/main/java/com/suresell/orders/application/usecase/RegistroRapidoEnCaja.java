package com.suresell.orders.application.usecase;

import com.suresell.orders.application.dto.CodigoDeProductoResponse;
import com.suresell.orders.application.dto.MenuProductResponse;
import com.suresell.orders.application.dto.RegistroRapidoRequest;
import com.suresell.orders.multitenant.TenantContext;
import com.suresell.orders.shared.ZonaHoraria;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Registrar un producto desde la caja: nombre, categoría, precio y el código
 * que acaba de leer el lector (V55, contrato CAJA-POR-TURNOS-Y-REGISTRO-EN-CAJA
 * §3 y PLAN-ESCANER-Y-REGISTRO-RAPIDO §4/§6).
 *
 * <h2>Una transacción, un dueño del dato</h2>
 *
 * Producto, categoría «General» (si hace falta) y fila de código nacen juntos
 * o ninguno. No se repite el error del alta única del panel («producto sin
 * línea»): aquí no hay segundo servicio. El inventario no se toca: el producto
 * queda «pendiente de completar» y el panel lo enlaza.
 *
 * <h2>El PIN es la puerta</h2>
 *
 * Lo configura el administrador (BCrypt en {@code sites.pin_registro_caja_hash}).
 * Sin PIN configurado la caja no registra y lo dice ({@code SIN_PIN}); con PIN
 * malo, {@code PIN_INCORRECTO}. No hace falta módulo nuevo.
 *
 * <h2>El id es del servidor</h2>
 *
 * {@code <negocio>-<slug(nombre)>}, la misma función que el panel
 * ({@code slugDe}, menu.component.ts): la clave primaria de {@code menu_products}
 * es GLOBAL (V2) y el prefijo evita que dos negocios choquen. Si el id ya
 * existe en este negocio se añade {@code -2}, {@code -3}…; nunca se reutiliza.
 *
 * <h2>El aislamiento lo pone la base</h2>
 *
 * Las lecturas van sin {@code tenant_id} en el WHERE (RLS filtra por
 * {@code app.tenant_id}); las escrituras lo llevan explícito desde el contexto
 * para que la fila diga lo mismo que la sesión.
 */
@Service
public class RegistroRapidoEnCaja {

    /** Enum cerrado de V51; desde la caja, siempre EAN (decisión 6). */
    static final String TIPO = "EAN";
    /**
     * Regla 6 de los lineamientos: de dónde salió. El contrato dice «fuente
     * CAJA»; en la base eso se escribe {@code pos}: el CHECK de V51
     * ({@code ck_codigos_fuente}) admite solo {@code panel | pos | siembra} y
     * define {@code pos} como «se registró desde la caja». V55 no amplía ese
     * enum (contrato §2), así que un literal {@code CAJA} rompería el INSERT.
     */
    static final String FUENTE = "pos";
    static final String NOMBRE_GENERAL = "General";
    static final int MAX_CODIGO = 64;
    static final int MAX_NOMBRE = 120;
    /** Cuántos sufijos (-2, -3…) se prueban antes de pedir otro nombre. */
    static final int MAX_INTENTOS_ID = 50;

    private final JdbcTemplate jdbc;
    private final SiteService sites;
    /** 5 claves incorrectas en 10 minutos por negocio → 429 sin evaluar el PIN. */
    private final LimiteDeClavesDeRegistro limite;

    public RegistroRapidoEnCaja(JdbcTemplate jdbc, SiteService sites, LimiteDeClavesDeRegistro limite) {
        this.jdbc = jdbc;
        this.sites = sites;
        this.limite = limite;
    }

    /**
     * Copia de {@code slugDe} del panel (menu.component.ts): NFD sin
     * diacríticos, minúsculas, todo lo que no sea [a-z0-9] a guion, sin
     * guiones en los extremos; el nombre a 60 y el negocio a 30 caracteres.
     */
    public static String slugDe(String nombre, String negocio) {
        String base = limpio(nombre == null ? "" : nombre);
        base = base.substring(0, Math.min(60, base.length()));
        if (base.isEmpty()) {
            base = "sin-nombre-" + System.currentTimeMillis();
        }
        String prefijo = limpio(negocio == null ? "" : negocio);
        prefijo = prefijo.substring(0, Math.min(30, prefijo.length()));
        return prefijo.isEmpty() ? base : prefijo + "-" + base;
    }

    private static String limpio(String x) {
        return Normalizer.normalize(x, Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    @Transactional
    public MenuProductResponse registrar(RegistroRapidoRequest cuerpo, String usuario) {
        String tenant = TenantContext.get();
        // 0 · Un negocio que acumuló claves incorrectas no pasa de aquí: 429
        //     para CUALQUIER petición de registro, sin mirar el cuerpo y sin
        //     evaluar el PIN (ni siquiera el correcto), hasta que pase la ventana.
        limite.comprobar(tenant);

        // 1 · Los campos, antes de tocar la base (400 con `campo`).
        String nombre = cuerpo.nombre() == null ? "" : cuerpo.nombre().trim();
        if (nombre.isEmpty()) {
            throw new CodigosDeProducto.CampoInvalido("nombre", "El nombre no puede estar vacío.");
        }
        if (nombre.length() > MAX_NOMBRE) {
            throw new CodigosDeProducto.CampoInvalido("nombre", "El nombre no puede pasar de " + MAX_NOMBRE + " caracteres.");
        }
        int precio = precioEntero(cuerpo.precio());
        String codigo = codigoLimpio(cuerpo.codigo());

        // 2 · El PIN es la puerta (403).
        if (!sites.tienePinRegistro()) {
            throw new PinRechazado("SIN_PIN",
                    "El administrador aún no configuró la clave para registrar desde la caja (panel → Caja).");
        }
        if (!sites.pinRegistroCorrecto(cuerpo.pin())) {
            limite.anotarFallo(tenant);
            throw new PinRechazado("PIN_INCORRECTO", "Clave incorrecta.");
        }
        limite.reiniciar(tenant);

        // 3 · Idempotente ante reintento: si el código ya es de un producto,
        //     se devuelve cuál (409 con productoId) y no se crea nada.
        Optional<String[]> vigente = productoDelCodigo(codigo);
        if (vigente.isPresent()) {
            throw new CodigoYaExiste(vigente.get()[0], vigente.get()[1]);
        }

        // 4 · Tope diario por negocio: `creado_en_caja_en` de HOY en la zona
        //     del servicio (ZonaHoraria). Se pasa el día como un rango de
        //     instantes y no como `(col AT TIME ZONE …)::date = ?`: así la
        //     comparación es contra la columna tal cual, sin una función por fila.
        int max = sites.maxRegistrosCajaPorDia();
        LocalDate dia = ZonaHoraria.hoy();
        OffsetDateTime desde = dia.atStartOfDay(ZonaHoraria.BOGOTA).toOffsetDateTime();
        OffsetDateTime hasta = dia.plusDays(1).atStartOfDay(ZonaHoraria.BOGOTA).toOffsetDateTime();
        Integer hoy = jdbc.queryForObject("""
                SELECT count(*) FROM public.menu_products
                 WHERE creado_en_caja_en >= ? AND creado_en_caja_en < ?""",
                Integer.class, desde, hasta);
        if (hoy != null && hoy >= max) {
            throw new LimiteDiario(max);
        }

        // 5 · La categoría: la pedida, o «General» (creada una sola vez).
        String[] categoria = categoria(cuerpo.categoriaId(), tenant);

        // 6 · El producto y su código, juntos.
        String id = insertarProducto(slugDe(nombre, tenant), tenant, nombre, precio, categoria[0], usuario);
        // El índice único parcial de V51 es la barrera contra el duplicado. Si
        // otra caja registró el mismo código entre la comprobación del paso 3 y
        // aquí, ON CONFLICT no inserta (en vez de abortar la transacción con un
        // 23505) y se responde igual que en el paso 3: YA_EXISTE con el
        // producto vigente. La excepción deshace el producto recién insertado.
        int codigos = jdbc.update("""
                INSERT INTO public.codigos_de_producto (codigo, producto_id, cantidad, tipo, fuente, creado_por)
                VALUES (?, ?, 1, ?, ?, ?)
                ON CONFLICT (tenant_id, codigo) WHERE retirado_en IS NULL DO NOTHING""",
                codigo, id, TIPO, FUENTE, usuario);
        if (codigos == 0) {
            String[] otro = productoDelCodigo(codigo).orElse(new String[] {null, null});
            throw new CodigoYaExiste(otro[0], otro[1]);
        }

        return new MenuProductResponse(id, nombre, precio, true, categoria[0], categoria[1],
                List.of(new CodigoDeProductoResponse(codigo, BigDecimal.ONE, TIPO)));
    }

    // ------------------------------------------------------------------
    // Lo que el panel pinta como «Registrado en la caja · falta enlazar».
    // ------------------------------------------------------------------

    /** Un producto nacido en la caja. */
    public record RegistradoEnCaja(String productoId, String nombre, String creadoEnCajaPor,
                                   OffsetDateTime creadoEnCajaEn) {
    }

    /** Tope de la lista: el panel la pinta entera, y 200 es más que semanas de tope diario. */
    static final int MAX_LISTA = 200;

    /**
     * Los productos del negocio con {@code creado_en_caja_en}, lo más reciente
     * primero, como mucho {@value #MAX_LISTA}. Solo lectura; RLS acota al
     * negocio. Ni el core ni el catálogo del POS mapean esas columnas: sin
     * esto, el panel no tenía de dónde leerlas.
     */
    @Transactional(readOnly = true)
    public List<RegistradoEnCaja> registradosEnCaja() {
        return jdbc.query("""
                SELECT id_product, name_product, creado_en_caja_por, creado_en_caja_en
                  FROM public.menu_products
                 WHERE creado_en_caja_en IS NOT NULL
                 ORDER BY creado_en_caja_en DESC, id_product
                 LIMIT ?""",
                (rs, i) -> new RegistradoEnCaja(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)),
                MAX_LISTA);
    }

    // ------------------------------------------------------------------

    private static int precioEntero(BigDecimal precio) {
        if (precio == null || precio.signum() <= 0) {
            throw new CodigosDeProducto.CampoInvalido("precio", "El precio tiene que ser mayor que cero.");
        }
        try {
            return precio.intValueExact();
        } catch (ArithmeticException e) {
            throw new CodigosDeProducto.CampoInvalido("precio", "El precio tiene que ser un número entero de pesos.");
        }
    }

    /** §6.6: trim, sin caracteres de control ni espacios dentro, máximo 64. Nunca se normaliza (V51 compara exacto). */
    static String codigoLimpio(String codigo) {
        String limpio = codigo == null ? "" : codigo.trim();
        if (limpio.isEmpty()) {
            throw new CodigosDeProducto.CampoInvalido("codigo", "El código no puede estar vacío.");
        }
        if (limpio.length() > MAX_CODIGO) {
            throw new CodigosDeProducto.CampoInvalido("codigo", "El código no puede pasar de " + MAX_CODIGO + " caracteres.");
        }
        for (int i = 0; i < limpio.length(); i++) {
            char c = limpio.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new CodigosDeProducto.CampoInvalido("codigo",
                        "El código trae caracteres que un lector no produce (control o espacios). Vuelve a leerlo.");
            }
        }
        return limpio;
    }

    /** {producto_id, nombre} del producto que hoy tiene ese código vigente, si lo hay. */
    private Optional<String[]> productoDelCodigo(String codigo) {
        List<String[]> filas = jdbc.query("""
                SELECT c.producto_id, p.name_product
                  FROM public.codigos_de_producto c
                  LEFT JOIN public.menu_products p ON p.id_product = c.producto_id
                 WHERE c.codigo = ? AND c.retirado_en IS NULL
                 LIMIT 1""",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, codigo);
        return filas.stream().findFirst();
    }

    /** {id, nombre} de la categoría a usar. */
    private String[] categoria(String categoriaId, String tenant) {
        if (categoriaId != null && !categoriaId.isBlank()) {
            String id = categoriaId.trim();
            List<String> nombres = jdbc.query(
                    "SELECT name_category FROM public.menu_categories WHERE id_category = ?",
                    (rs, i) -> rs.getString(1), id);
            if (nombres.isEmpty()) {
                throw new CategoriaInexistente(id);
            }
            return new String[] {id, nombres.get(0)};
        }
        // `<negocio>-general`: la misma función que el panel aplicaría a una
        // categoría llamada «General», así que si el administrador la crea
        // después con ese nombre, es esta misma.
        String general = slugDe(NOMBRE_GENERAL, tenant);
        // Una sola vez: si ya existe (de este u otro registro), se reutiliza.
        // ON CONFLICT cubre a dos cajas registrando a la vez.
        jdbc.update("""
                INSERT INTO public.menu_categories (id_category, tenant_id, name_category)
                VALUES (?, ?, ?)
                ON CONFLICT (id_category) DO NOTHING""",
                general, tenant, NOMBRE_GENERAL);
        String nombre = jdbc.query(
                "SELECT name_category FROM public.menu_categories WHERE id_category = ?",
                (rs, i) -> rs.getString(1), general).stream().findFirst().orElse(NOMBRE_GENERAL);
        return new String[] {general, nombre};
    }

    /**
     * Inserta el producto con el primer id libre: base, base-2, base-3…
     * Nunca se reutiliza uno existente (eso sería un UPDATE disfrazado).
     *
     * <p>Se pregunta a la base con {@code ON CONFLICT DO NOTHING} y no con un
     * {@code SELECT} previo por dos motivos: la clave primaria de
     * {@code menu_products} es GLOBAL (V2) y RLS esconde los ids de otros
     * negocios —un SELECT diría «libre» y el INSERT chocaría—; y dos cajas
     * registrando el mismo nombre a la vez no se pisan.
     */
    private String insertarProducto(String base, String tenant, String nombre, int precio,
                                    String categoriaId, String usuario) {
        for (int n = 1; n <= MAX_INTENTOS_ID; n++) {
            String candidato = n == 1 ? base : base + "-" + n;
            int filas = jdbc.update("""
                    INSERT INTO public.menu_products
                        (id_product, tenant_id, name_product, price, active, category_id,
                         creado_en_caja_por, creado_en_caja_en)
                    VALUES (?, ?, ?, ?, true, ?, ?, now())
                    ON CONFLICT (id_product) DO NOTHING""",
                    candidato, tenant, nombre, precio, categoriaId, usuario);
            if (filas == 1) {
                return candidato;
            }
        }
        throw new IllegalStateException("Ya hay " + MAX_INTENTOS_ID + " productos llamados «" + nombre
                + "». Cambia el nombre (por ejemplo, con la presentación) y vuelve a intentarlo.");
    }

    // ------------------------------------------------------------------ rechazos

    /** 403: sin PIN configurado ({@code SIN_PIN}) o PIN malo ({@code PIN_INCORRECTO}). */
    public static class PinRechazado extends RuntimeException {
        private final String codigo;

        public PinRechazado(String codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }

        public String codigo() {
            return codigo;
        }
    }

    /** 409 {@code YA_EXISTE}: el código ya es de otro producto; va con su id para que el POS lo añada. */
    public static class CodigoYaExiste extends RuntimeException {
        private final String productoId;

        public CodigoYaExiste(String productoId, String nombreProducto) {
            super("Ese código ya es de «" + (nombreProducto == null ? productoId : nombreProducto) + "».");
            this.productoId = productoId;
        }

        public String productoId() {
            return productoId;
        }
    }

    /** 409 {@code LIMITE_DIARIO}. */
    public static class LimiteDiario extends RuntimeException {
        public LimiteDiario(int max) {
            super("Hoy ya se registraron " + max + " desde la caja, que es el máximo del día. Mañana se puede de nuevo; mientras, créalo en el panel.");
        }
    }

    /** 404 {@code CATEGORIA_INEXISTENTE} con {@code campo: categoriaId}. */
    public static class CategoriaInexistente extends RuntimeException {
        public CategoriaInexistente(String id) {
            super("La categoría " + id + " no existe en este negocio.");
        }
    }
}
