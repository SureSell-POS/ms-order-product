package com.suresell.orders.infrastructure.persistence;
import com.suresell.orders.domain.model.DailyClosure;
import com.suresell.orders.domain.port.out.DailyClosureRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Component
@RequiredArgsConstructor
public class DailyClosureRepositoryAdapter implements DailyClosureRepositoryPort {
    private final DailyClosureRepository dailyClosureRepository;
    @Override
    public DailyClosure save(DailyClosure dailyClosure) {
        return dailyClosureRepository.save(dailyClosure);
    }
    @Override
    public Optional<DailyClosure> findById(UUID id) {
        return dailyClosureRepository.findById(id);
    }
    @Override
    public List<DailyClosure> findAll() {
        return dailyClosureRepository.findAll();
    }
    @Override
    public Optional<DailyClosure> findLastClosure() {
        return dailyClosureRepository.findTopByOrderByClosingTimeDesc();
    }
    @Override
    public List<DailyClosure> findAllClosuresOrderByDateDesc() {
        return dailyClosureRepository.findAllClosuresOrderByDateDesc();
    }
    @Override
    public int countClosuresOn(LocalDate closureDate) {
        return dailyClosureRepository.countByClosureDate(closureDate);
    }
    @Override
    public int ultimoTurnoDel(LocalDate closureDate) {
        return dailyClosureRepository.ultimoTurnoDelDia(closureDate);
    }
}
