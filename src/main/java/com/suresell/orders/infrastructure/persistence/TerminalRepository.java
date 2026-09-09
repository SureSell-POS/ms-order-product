package com.suresell.orders.infrastructure.persistence;

import com.suresell.orders.domain.model.Terminal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    /**
     * Anota el contacto de un terminal que ya es de este negocio.
     *
     * <p>Bajo RLS solo ve las filas del negocio de la sesión: si el UUID está
     * registrado a nombre de OTRO negocio, esto devuelve 0 igual que si no
     * existiera. Por eso el alta que sigue tiene que tolerar el choque.
     */
    @Modifying
    @Query("""
            UPDATE Terminal t
               SET t.epochVisto = CASE WHEN :epoch > t.epochVisto THEN :epoch ELSE t.epochVisto END,
                   t.ultimaConexionEn = :ahora
             WHERE t.id = :id
            """)
    int registrarContacto(@Param("id") UUID id,
                          @Param("epoch") Integer epoch,
                          @Param("ahora") OffsetDateTime ahora);

    /**
     * Da de alta el terminal <b>sin fallar si el UUID ya existe</b>.
     *
     * <p>Antes era un {@code save()} y en el segundo negocio que usaba el mismo
     * navegador reventaba con {@code duplicate key … terminals_pkey}: la clave
     * primaria es global, el POS guarda UN UUID por navegador (no por negocio)
     * y el {@code UPDATE} de arriba, bajo RLS, no ve la fila ajena. La
     * violación saltaba al confirmar la transacción {@code REQUIRES_NEW}, fuera
     * del {@code catch}, y la VENTA salía con 500 (fase 0, 2026-09-09, en
     * ferretería y mayorista).
     *
     * <p>{@code ON CONFLICT DO NOTHING} no dispara la violación: devuelve 0 y la
     * venta sigue, que es lo que V35 prometió. El terminal queda registrado a
     * nombre del primer negocio que lo usó; el segundo lo verá en el WARN.
     *
     * @return 1 si lo dio de alta, 0 si ese UUID ya estaba (de este negocio o de otro)
     */
    @Modifying
    @Query(value = """
            INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto)
            VALUES (:id, :tenantId, :estado, :ahora, :ahora, :epoch)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int darDeAltaSiNoExiste(@Param("id") UUID id,
                            @Param("tenantId") String tenantId,
                            @Param("estado") String estado,
                            @Param("ahora") OffsetDateTime ahora,
                            @Param("epoch") int epoch);
}
