package com.suresell.orders.domain.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Clave de {@link Terminal} desde V50: (negocio, UUID).
 *
 * <p>El UUID lo genera el POS y es uno por navegador; dos negocios que usan
 * el mismo equipo mandan el mismo UUID. Con el negocio en la clave cada uno
 * tiene su fila y ninguna orden puede apuntar a un terminal ajeno.
 */
public class TerminalId implements Serializable {

    private String tenantId;
    private UUID id;

    public TerminalId() {
    }

    public TerminalId(String tenantId, UUID id) {
        this.tenantId = tenantId;
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TerminalId that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, id);
    }
}
