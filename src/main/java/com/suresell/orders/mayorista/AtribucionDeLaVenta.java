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
     *       {@code vendedorId} se toma el suyo, y con otro distinto se rechaza (un
     *       vendedor no atribuye ventas a otro; plan F2.3).</li>
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
                throw new DatoInvalidoException("vendedorId", "Un vendedor solo registra ventas a su nombre.");
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
     * @return true si abrió la cuenta
     */
    public boolean asegurarCuentaDeCredito(String negocio, String documento) {
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
                   -- customer_document es VARCHAR(20) (V28) y un documento no se recorta:
                   -- si no cabe, no se abre y el disparador lo dice (F4.2 lo pasa a TEXT).
                   AND length(c.documento) <= 20
                   AND NOT EXISTS (SELECT 1 FROM accounts_receivable a
                                    WHERE a.tenant_id = c.tenant_id AND a.customer_document = c.documento)""",
                negocio, documento);
        return abiertas > 0;
    }
}
