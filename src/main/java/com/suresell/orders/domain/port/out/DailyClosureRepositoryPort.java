package com.suresell.orders.domain.port.out;
import com.suresell.orders.domain.model.DailyClosure;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface DailyClosureRepositoryPort {
    DailyClosure save(DailyClosure dailyClosure);
    Optional<DailyClosure> findById(UUID id);
    List<DailyClosure> findAll();
    Optional<DailyClosure> findLastClosure();
    List<DailyClosure> findAllClosuresOrderByDateDesc();
    /** V55: cierres que lleva el día. */
    int countClosuresOn(LocalDate closureDate);
    /** V55: el último turno cerrado en el día (0 si ninguno); el turno a cerrar es este + 1. */
    int ultimoTurnoDel(LocalDate closureDate);
}
