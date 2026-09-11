package com.suresell.orders.infrastructure.web;
import com.suresell.orders.application.dto.ClosurePreviewResponse;
import com.suresell.orders.application.dto.ClosureRequest;
import com.suresell.orders.application.dto.ClosureResponse;
import com.suresell.orders.application.dto.request.ExecuteClosureRequest;
import com.suresell.orders.application.dto.responses.CashierClosureResponse;
import com.suresell.orders.application.usecase.ExecuteDailyClosureUseCase;
import com.suresell.orders.domain.port.in.DailyClosurePort;  
import com.suresell.orders.shared.export.DailyClosureExcelExporter;  
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping(value = {"/api/closures"})
@Tag(name = "Daily Closures", description = "Gestión de cierres diarios de caja")
public class DailyClosureController {
    private final DailyClosurePort dailyClosurePort;
    private final ExecuteDailyClosureUseCase executeDailyClosureUseCase;
    private static final Logger log = LoggerFactory.getLogger(DailyClosureController.class);
    private static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    public DailyClosureController(DailyClosurePort dailyClosurePort, ExecuteDailyClosureUseCase executeDailyClosureUseCase) {
        this.dailyClosurePort = dailyClosurePort;
        this.executeDailyClosureUseCase = executeDailyClosureUseCase;
    }
    @GetMapping(value = {"/preview"})
    @Operation(summary = "Obtener preview del cierre diario")
    public ResponseEntity<ClosurePreviewResponse> getClosurePreview() {
        try {
            ClosurePreviewResponse preview = this.dailyClosurePort.getClosurePreview();
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            throw new RuntimeException("Error al generar preview de cierre: " + e.getMessage());
        }
    }
    /**
     * V55 — Un cierre es un turno; el día admite los que haga falta. Ya no hay
     * «la caja de hoy ya fue cerrada» ni {@code alreadyClosed}: si dos
     * terminales cierran el mismo turno a la vez, el caso de uso traduce el
     * choque de unicidad a {@code TurnoYaCerradoException} y el
     * {@code GlobalExceptionHandler} lo devuelve como 409 con el texto del contrato.
     */
    @PostMapping
    @Operation(summary = "Cerrar el turno de caja",
            description = "`turno` lo calcula el servidor. `baseForNextDay` opcional (≥ 0); sin él, base_caja del negocio; sin ella, 0.")
    public ResponseEntity<CashierClosureResponse> executeClosure(
            @Valid @RequestBody ExecuteClosureRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "System") String userName) {
        return ResponseEntity.ok(executeDailyClosureUseCase.execute(request, userName));
    }
    @ExceptionHandler(value = {MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        HashMap errors = new HashMap();
        ex.getBindingResult().getFieldErrors().forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status((HttpStatusCode) HttpStatus.BAD_REQUEST).body(Map.of("error", "Errores de validaci\u00f3n", "fields", errors));
    }
}
