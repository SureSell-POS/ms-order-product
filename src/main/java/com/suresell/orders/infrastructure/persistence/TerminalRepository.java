package com.suresell.orders.infrastructure.persistence;

import com.suresell.orders.domain.model.Terminal;
import com.suresell.orders.domain.model.TerminalId;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, TerminalId> {

    /**
     * Anota el contacto de un terminal de este negocio.
     *
     * <p>Bajo RLS solo ve las filas del negocio de la sesión, y desde V50 la
     * fila es (negocio, UUID): si el UUID lo usa además otro negocio, eso es
     * otra fila y no interfiere.
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
     * Da de alta el terminal para este negocio <b>sin fallar si ya existe</b>.
     *
     * <p>Antes de V50 la clave era el UUID solo y el segundo negocio que usaba
     * el mismo navegador reventaba con {@code terminals_pkey}: la violación
     * saltaba al confirmar la transacción {@code REQUIRES_NEW}, fuera del
     * {@code catch}, y la VENTA salía con 500 (fase 0, 2026-09-09). Desde V50
     * la clave es (negocio, UUID) y el único conflicto posible es con la
     * propia fila de este negocio: dos sincronizaciones a la vez, y la otra
     * ganó. {@code ON CONFLICT DO NOTHING} lo deja en 0 filas, sin excepción.
     *
     * @return 1 si lo dio de alta, 0 si este negocio ya lo tenía
     */
    @Modifying
    @Query(value = """
            INSERT INTO terminals (id, tenant_id, estado, registrado_en, ultima_conexion_en, epoch_visto)
            VALUES (:id, :tenantId, :estado, :ahora, :ahora, :epoch)
            ON CONFLICT (tenant_id, id) DO NOTHING
            """, nativeQuery = true)
    int darDeAltaSiNoExiste(@Param("id") UUID id,
                            @Param("tenantId") String tenantId,
                            @Param("estado") String estado,
                            @Param("ahora") OffsetDateTime ahora,
                            @Param("epoch") int epoch);
}
