package com.suresell.orders.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una caja física. V35.
 *
 * <h3>El id lo genera el cliente</h3>
 *
 * {@link #id} es un UUID que el POS crea en su primer arranque y persiste
 * localmente. No lo asigna el servidor, y el motivo es el diferenciador del
 * producto: el terminal tiene que poder vender desde el segundo cero, sin
 * conexión. Si el id viniera del servidor, un local sin internet no podría
 * abrir caja.
 *
 * <p>Por eso mismo el servidor <b>nunca rechaza un terminal desconocido</b>: lo
 * da de alta. Rechazarlo convertiría un problema de registro en una venta
 * perdida.
 *
 * <h3>`epochVisto` no es un contador cualquiera</h3>
 *
 * Es la cuenta de veces que este terminal perdió su estado local. Un salto aquí
 * explica un reinicio de la secuencia: sin él, ver `seq` volver a 1 sería
 * indistinguible de un ataque de repetición.
 */
@Entity
@Table(name = "terminals")
@IdClass(TerminalId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(com.suresell.orders.multitenant.TenantEntityListener.class)
public class Terminal implements com.suresell.orders.multitenant.TenantOwned {

    /** Enum CERRADO. Sin valor "otro" (reglas 9 y 10 de LINEAMIENTOS). */
    public static final String ACTIVO = "activo";
    public static final String INACTIVO = "inactivo";
    public static final String RETIRADO = "retirado";

    @Id
    @Column(name = "id")
    private UUID id;

    /**
     * Desde V50 forma parte de la clave: el mismo UUID puede existir una vez
     * por negocio. Lo pone {@code TenantEntityListener} al persistir, como
     * antes; el alta automática va por SQL nativo y lo pasa a mano.
     */
    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "site_id")
    private Long siteId;

    /** Nombre corto que le da el negocio ('CAJA01'). NULL en el alta automática. */
    @Column(name = "codigo")
    private String codigo;

    @Column(name = "alias")
    private String alias;

    @Column(name = "estado", nullable = false)
    private String estado = ACTIVO;

    @Column(name = "registrado_en")
    private OffsetDateTime registradoEn;

    /** Se actualiza en el registro y en el latido, NO en cada orden. */
    @Column(name = "ultima_conexion_en")
    private OffsetDateTime ultimaConexionEn;

    @Column(name = "epoch_visto", nullable = false)
    private Integer epochVisto = 1;

    /** Nombre para mostrar, sin inventarse nada: alias, luego código, luego el id corto. */
    public String nombreVisible() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        if (codigo != null && !codigo.isBlank()) {
            return codigo;
        }
        return id == null ? "(sin identificar)" : id.toString().substring(0, 8);
    }
}
