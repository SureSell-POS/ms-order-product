package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.DailyClosure;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
@Repository
public interface DailyClosureRepository
extends JpaRepository<DailyClosure, UUID> {
    Optional<DailyClosure> findTopByOrderByClosingTimeDesc();
    @Query(value="SELECT dc FROM DailyClosure dc ORDER BY dc.closingTime DESC")
    List<DailyClosure> findAllClosuresOrderByDateDesc();
    /**
     * El cierre anterior: de aquí salen a la vez la BASE del día y el arranque
     * de la ventana. Tienen que salir de la misma fila.
     *
     * <p>Aquí vivía también un {@code findLastClosingTimeByUser(userName)} que
     * hacía {@code SELECT MAX(closing_time) ... WHERE user_name = :userName}.
     * Se eliminó el 2026-08-31 porque era una trampa: un {@code MAX} sin
     * {@code GROUP BY} devuelve siempre una fila, con NULL dentro si no encaja
     * nadie, y Spring Data convierte ese NULL en {@code Optional.empty()}. Es
     * decir, <b>«no hay cierre anterior» y «el nombre no coincide con ninguno»
     * son indistinguibles</b> para quien llama. El POS mandaba
     * {@code sellerId: 'Angie'} mientras los cierres se guardaban con
     * {@code user_name = 'Cajero 1'}, y durante 103 cierres el resultado fue
     * silenciosamente el segundo caso.
     */
    Optional<DailyClosure> findFirstByOrderByClosingTimeDesc();

    /**
     * V55: cuántos cierres lleva el día ({@code cierresHoy} del preview). RLS
     * acota al negocio; no hace falta {@code tenant_id} en el WHERE.
     */
    int countByClosureDate(LocalDate closureDate);

    /**
     * V55: el último turno cerrado en el día, 0 si ninguno. El turno que se va
     * a cerrar es este + 1.
     *
     * <p>MAX y no COUNT a propósito. Mientras no se borre ningún cierre dan lo
     * mismo, pero la receta para un cierre en ceros ya guardado es BORRAR la
     * fila ({@code cierre-en-ceros-bloquea-el-dia}): con turnos 1 y 2 y el 1
     * borrado, COUNT + 1 volvería a dar 2, chocaría con la unicidad
     * (negocio, fecha, turno) y el día quedaría sin poder cerrarse — el mismo
     * bloqueo que V55 viene a quitar. MAX + 1 da 3 y sigue.
     */
    @Query("SELECT COALESCE(MAX(dc.turno), 0) FROM DailyClosure dc WHERE dc.closureDate = :fecha")
    int ultimoTurnoDelDia(@Param("fecha") LocalDate fecha);
}
