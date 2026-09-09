package com.suresell.orders.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sede de un negocio (Inc. 1 del modo Restaurante).
 *
 * Es la unidad que decide el MODO DE POS. Va por sede y no por tenant porque una
 * cadena puede tener una sede en plazoleta y otra en restaurante; el modo lo
 * cambia únicamente el KAM.
 *
 * Alcance deliberado: esto NO es el sistema multisede completo. Es la unidad
 * mínima para que el modo y el consecutivo de facturación tengan de dónde
 * colgar sin rehacerlo después.
 */
@Entity
@Table(name = "sites")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
public class Site implements com.suresell.orders.multitenant.TenantOwned {

    /**
     * LEGADO (V48). Son los dos valores que un lector viejo de {@code posMode}
     * entiende; los conserva la base en {@code pos_mode}, derivados del flujo.
     * NO se usan para validar: eso lo hace el catálogo
     * ({@code FlujosDeVenta}). Se quedan porque {@link #esRestaurante()} y los
     * tests que garantizan el comportamiento viejo los nombran.
     */
    public static final String MODO_PLAZOLETA = "PLAZOLETA";
    public static final String MODO_RESTAURANTE = "RESTAURANTE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(nullable = false)
    private String name;

    /** Código corto, base de la numeración por sede. */
    @Column(nullable = false)
    private String code;

    /**
     * V48 — Lo que ve un POS sin actualizar. La base lo DERIVA de
     * {@link #flujoDeVenta} por trigger: escribirlo aquí solo sirve para un
     * escritor viejo, y aun así el trigger corrige el flujo.
     */
    @Column(name = "pos_mode", nullable = false)
    private String posMode = MODO_PLAZOLETA;

    /**
     * V48 — El flujo de venta de la sede (catálogo {@code flujos_de_venta}).
     * Es la verdad; {@code posMode} es su sombra para los clientes viejos.
     * Quien crea una sede desde Java lo fija SIEMPRE ({@code SiteService.crear}):
     * JPA escribe la columna aunque sea nula y el DEFAULT de la base no aplica.
     */
    @Column(name = "flujo_de_venta")
    private String flujoDeVenta;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /**
     * V52 — ¿Esta sede imprime tirilla? Con {@code false} el POS no busca el
     * programa de impresión, no muestra alerta y la venta nace
     * {@code no_solicitado}. Lo cambia el KAM. Nace en {@code true}: ningún
     * negocio que hoy imprime deja de hacerlo por esta columna.
     */
    @Column(name = "imprime_tirilla", nullable = false)
    private Boolean imprimeTirilla = true;

    public boolean esRestaurante() {
        return MODO_RESTAURANTE.equalsIgnoreCase(posMode);
    }
}
