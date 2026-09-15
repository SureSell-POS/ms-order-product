package com.suresell.orders.mayorista;

import com.suresell.orders.shared.exception.DatoInvalidoException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plan de mayoristas, F1.3: a quién se le atribuye la venta, con qué condición de
 * pago, y la cuenta de crédito del cliente cuando todavía no existe.
 *
 * <p>Separado de {@code OrderHandler} para que la regla se lea en un sitio. El
 * negocio va escrito en cada consulta: RLS es el suelo, no la regla.
 */
@Component
public class AtribucionDeLaVenta {

    /** Roles que pueden figurar como vendedor de una venta. */
    static final Set<String> ROLES_QUE_VENDEN = Set.of("vendedor", "admin", "cajero");
    public static final String CONTADO = "CONTADO";
    public static final String CREDITO = "CREDITO";

    private final JdbcTemplate jdbc;

    public AtribucionDeLaVenta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * El vendedor de la venta.
     *
     * <ul>
     *   <li>Si quien la registra es un {@code vendedor}, vende a su nombre: sin
     *       {@code vendedorId} se toma el suyo, y con el de otro se rechaza con 409
     *       {@code USUARIO_DE_OTRA_SESION}: es la venta de otro vendedor que llega
     *       por su sesión, y el POS la aparta hasta que entre su dueño (plan F2.3).</li>
     *   <li>Si es cajero o admin, elige: sin {@code vendedorId} queda «sin asignar».</li>
     *   <li>El vendedor tiene que ser de este negocio y de un rol que venda. No se
     *       exige que siga activo: una venta tomada sin conexión antes de
     *       desactivarlo sigue siendo suya.</li>
     * </ul>
     */
    public Long vendedor(String negocio, Long vendedorId, Optional<Long> quienRegistra, Optional<String> suRol) {
        if (suRol.map("vendedor"::equals).orElse(false)) {
            Long propio = quienRegistra.orElse(null);
            if (vendedorId == null) {
                return propio;
            }
            if (!vendedorId.equals(propio)) {
                throw new com.suresell.orders.shared.exception.UsuarioDeOtraSesionException(vendedorId);
            }
        }
        if (vendedorId == null || negocio == null) {
            return vendedorId;
        }
        List<String> rol = jdbc.queryForList(
                "SELECT role FROM users WHERE tenant_id = ? AND id = ?", String.class, negocio, vendedorId);
        if (rol.isEmpty()) {
            throw new DatoInvalidoException("vendedorId", "El vendedor no es de este negocio.");
        }
        if (!ROLES_QUE_VENDEN.contains(rol.get(0))) {
            throw new DatoInvalidoException("vendedorId", "Ese usuario no puede figurar como vendedor.");
        }
        return vendedorId;
    }

    /**
     * La condición de pago. Si no viene, {@code CREDITO} cuando el medio es
     * {@code CREDITO} y {@code CONTADO} en todo lo demás. Si viene, tiene que ser
     * coherente con el medio: el débito en cuentas por cobrar lo escribe el
     * disparador de V45 solo cuando el medio es {@code CREDITO}, así que un
     * «crédito pagado en efectivo» sería una venta a crédito sin deuda.
     *
     * <p><b>Excepción prevista para F5, sin construir:</b> la venta nacida de un
     * pedido contraentrega va con medio {@code CREDITO} y plazo 0 (el cobro en
     * la entrega es un abono), y ante la DIAN es de {@code CONTADO}. Cuando
     * exista, entra aquí como una rama más, para {@code origen = pedido} con
     * plazo 0, y la regla de la caja no cambia (acordado con ECM, 2026-09-13).
     */
    public String condicionPago(String declarada, String medioNormalizado) {
        boolean medioCredito = CREDITO.equals(medioNormalizado);
        if (declarada == null || declarada.isBlank()) {
            return medioCredito ? CREDITO : CONTADO;
        }
        String c = declarada.trim().toUpperCase();
        if (!CONTADO.equals(c) && !CREDITO.equals(c)) {
            throw new DatoInvalidoException("condicionPago", "La condición de pago es CONTADO o CREDITO.");
        }
        if (CREDITO.equals(c) != medioCredito) {
            throw new DatoInvalidoException("condicionPago", medioCredito
                    ? "Una venta con medio de pago CREDITO es a crédito."
                    : "Una venta a crédito se registra con el medio de pago CREDITO.");
        }
        return c;
    }

    /**
     * Abre la cuenta de cuentas por cobrar de un cliente registrado que todavía
     * no la tiene, para que su primera venta a crédito entre (el disparador de
     * V45 la niega sin cuenta).
     *
     * <p>Solo para clientes que existen en {@code clientes} de este negocio: un
     * documento que nadie registró sigue rechazándose, como hoy. El cupo nace en
     * 0 porque nadie lo ha fijado: la venta entra y queda marcada
     * {@code excede_cupo} (avisa, no bloquea, D9), que es verdad hasta que el
     * admin le dé un cupo. Un bloqueo consultivo por (negocio, documento)
     * impide que dos ventas simultáneas abran dos cuentas, porque
     * {@code accounts_receivable} todavía no tiene la unicidad (F4.2).
     *
     * <p>{@code ventaDeCaja} (F4.11, opción A): la venta la hizo una caja
     * (trae terminal y no nace de un pedido) y ya ocurrió. A un cliente en insolvencia
     * no se le rechaza aquí: la base (V72) la registra con su DEBIT y la marca para
     * revisar. Sin caja, 409 {@code CLIENTE_EN_INSOLVENCIA} como siempre.
     *
     * @return true si abrió la cuenta
     */
    public boolean asegurarCuentaDeCredito(String negocio, String documento, boolean ventaDeCaja) {
        if (!ventaDeCaja) {
            exigirQueNoEsteEnInsolvencia(negocio, documento);
        }
        if (negocio == null || documento == null || documento.isBlank()) {
            return false;
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null,
                "cuenta-de-credito:" + negocio + ":" + documento);
        int abiertas = jdbc.update("""
                INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document,
                                                 customer_name, customer_phone, status, total_debt, updated_at)
                SELECT gen_random_uuid()::text, c.tenant_id, now(), 0, c.documento, left(c.nombre, 100),
                       left(c.telefono, 20), 'ACTIVE', 0, now()
                  FROM clientes c
                 WHERE c.tenant_id = ? AND c.documento = ? AND c.activo
                   AND NOT EXISTS (SELECT 1 FROM accounts_receivable a
                                    WHERE a.tenant_id = c.tenant_id AND a.customer_document = c.documento)""",
                negocio, documento);
        return abiertas > 0;
    }

    /**
     * V72 (F4.11): si la venta quedó marcada VENTA_A_INSOLVENTE_POR_REVISAR. La marca la
     * escribe el disparador al insertar la venta; aquí solo se lee.
     */
    public boolean quedoPorRevisarPorInsolvencia(String negocio, java.util.UUID venta) {
        if (negocio == null || venta == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ventas_a_insolvente WHERE tenant_id = ? AND order_uuid = ?)",
                Boolean.class, negocio, venta));
    }

    /**
     * V74 (F4.12): cuánto saldo a favor del cliente se aplicó solo a la deuda de esta venta. Lo
     * aplica el disparador al insertar la venta (regla SALDO_A_FAVOR_AUTOMATICO); aquí solo se lee.
     */
    public java.math.BigDecimal saldoAFavorAplicado(String negocio, java.util.UUID venta) {
        if (negocio == null || venta == null) {
            return java.math.BigDecimal.ZERO;
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(a.monto), 0)
                  FROM cartera_aplicaciones a
                  JOIN debt_transactions d ON d.tenant_id = a.tenant_id AND d.id = a.debito_tx_id
                 WHERE a.tenant_id = ? AND d.order_uuid = ? AND d.type = 'DEBIT'
                   AND a.regla = 'SALDO_A_FAVOR_AUTOMATICO'""", java.math.BigDecimal.class, negocio, venta);
    }

    /**
     * F4.3: a un cliente con la insolvencia ya cumplida (día de Bogotá) no se le
     * vende a crédito: 409 {@code CLIENTE_EN_INSOLVENCIA}, antes de escribir nada.
     * La base (V65) lo vuelve a comprobar.
     */
    public void exigirQueNoEsteEnInsolvencia(String negocio, String documento) {
        if (negocio == null || documento == null || documento.isBlank()) {
            return;
        }
        List<java.sql.Date> desde = jdbc.queryForList("""
                SELECT c.en_insolvencia_desde FROM clientes c
                 WHERE c.tenant_id = ? AND c.documento = ?
                   AND c.en_insolvencia_desde <= (now() AT TIME ZONE 'America/Bogota')::date""",
                java.sql.Date.class, negocio, documento);
        if (!desde.isEmpty()) {
            throw new com.suresell.orders.shared.exception.ClienteEnInsolvenciaException(
                    documento, desde.get(0).toLocalDate());
        }
    }
}
