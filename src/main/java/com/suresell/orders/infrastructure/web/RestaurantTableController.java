package com.suresell.orders.infrastructure.web;

import com.suresell.orders.application.usecase.SiteService;
import com.suresell.orders.application.usecase.TableSessionService;
import com.suresell.orders.domain.model.RestaurantTable;
import com.suresell.orders.domain.model.TableSession;
import com.suresell.orders.infrastructure.persistence.RestaurantTableRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Mesas y sus cuentas (Inc. 2 y 3 del modo Restaurante).
 *
 * El plano de mesas del POS se dibuja con GET /api/tables: cada mesa trae si
 * está libre, ocupada o cobrándose.
 */
@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
@Tag(name = "Mesas", description = "Mesas y cuentas abiertas (modo Restaurante)")
public class RestaurantTableController {

    private final RestaurantTableRepository tableRepository;
    private final TableSessionService sessionService;
    private final SiteService siteService;
    private final com.suresell.orders.multitenant.JwtTenantResolver resolver;

    /** Plano de mesas con su estado. Es lo que pinta el POS. */
    @GetMapping
    @Operation(summary = "Mesas del negocio con su estado (LIBRE / OCUPADA / COBRANDO)")
    public ResponseEntity<Map<String, Object>> plano() {
        List<RestaurantTable> mesas = tableRepository.findAllByOrderByNumberAsc();
        List<TableSession> vivas = sessionService.vivas();

        List<Map<String, Object>> salida = mesas.stream().map(m -> {
            var sesion = vivas.stream()
                    .filter(s -> s.getTableId().equals(m.getId())).findFirst();
            String estado = sesion.map(s -> TableSession.COBRANDO.equals(s.getStatus())
                    ? "COBRANDO" : "OCUPADA").orElse("LIBRE");
            Map<String, Object> fila = new java.util.HashMap<>();
            fila.put("id", m.getId());
            fila.put("number", m.getNumber());
            fila.put("label", m.getLabel());
            fila.put("seats", m.getSeats());
            fila.put("active", m.getActive());
            fila.put("estado", estado);
            fila.put("sessionId", sesion.map(s -> s.getId().toString()).orElse(null));
            fila.put("claimedBy", sesion.map(TableSession::getClaimedBy).orElse(null));
            return fila;
        }).toList();

        var flujo = siteService.flujoEfectivo();
        return ResponseEntity.ok(Map.of(
                "posMode", flujo.posModeLegado(),
                "flujoDeVenta", flujo.codigo(),
                "tables", salida));
    }

    public record ConfigurarMesasRequest(Integer cantidad, Integer seats) {
    }

    /**
     * Fija la CANTIDAD de mesas (solo admin), igual que el editor de
     * rastreadores. Crea las que falten y desactiva las sobrantes en vez de
     * borrarlas: una mesa borrada dejaría huérfanas sus cuentas históricas.
     */
    @PutMapping("/config")
    @Operation(summary = "Configurar la cantidad de mesas (solo admin)")
    public ResponseEntity<?> configurar(@RequestBody ConfigurarMesasRequest req,
                                        HttpServletRequest http) {
        if (!esAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of("error", "Solo un administrador puede configurar las mesas"));
        }
        Integer cantidad = req == null ? null : req.cantidad();
        if (cantidad == null || cantidad < 0 || cantidad > 500) {
            return ResponseEntity.badRequest().body(Map.of("error", "La cantidad debe estar entre 0 y 500"));
        }
        Long siteId = siteService.sedePorDefecto().map(s -> s.getId()).orElse(null);
        List<RestaurantTable> existentes = tableRepository.findAllByOrderByNumberAsc();

        for (int n = 1; n <= cantidad; n++) {
            final int numero = n;
            RestaurantTable mesa = existentes.stream()
                    .filter(m -> m.getNumber() == numero).findFirst()
                    .orElseGet(RestaurantTable::new);
            mesa.setNumber(numero);
            mesa.setActive(true);
            mesa.setSiteId(siteId);
            if (req.seats() != null) {
                mesa.setSeats(req.seats());
            }
            tableRepository.save(mesa);
        }
        // Las que sobran se DESACTIVAN, no se borran.
        existentes.stream().filter(m -> m.getNumber() > cantidad).forEach(m -> {
            m.setActive(false);
            tableRepository.save(m);
        });
        return ResponseEntity.ok(tableRepository.findAllByOrderByNumberAsc());
    }

    public record AbrirMesaRequest(Integer number) {
    }

    @PostMapping("/sessions")
    @Operation(summary = "Abrir la cuenta de una mesa")
    public ResponseEntity<?> abrir(@RequestBody AbrirMesaRequest req, HttpServletRequest http) {
        try {
            return ResponseEntity.status(201).body(
                    sessionService.abrir(req.number(), usuario(http)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sessions/{id}/claim")
    @Operation(summary = "Marcar que esta caja está cobrando la mesa (aviso, no bloqueo)")
    public ResponseEntity<?> reclamar(@PathVariable UUID id, HttpServletRequest http) {
        try {
            return ResponseEntity.ok(sessionService.reclamarParaCobro(id, usuario(http)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sessions/{id}/summary")
    @Operation(summary = "Consumo acumulado de la mesa (para mostrarlo antes de cobrar)")
    public ResponseEntity<?> resumen(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(sessionService.resumen(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cobro de mesa.
     *
     * <p>{@code personas} y {@code metodosPorPersona} son OPCIONALES y llegaron
     * con la división de cuenta. Sin ellos el cobro se comporta exactamente
     * como antes —un solo medio para toda la mesa—, así que un POS viejo sigue
     * cobrando sin enterarse del cambio.
     *
     * <p>Nótese que NO se reciben montos: el cliente dice entre cuántos se
     * divide y con qué paga cada uno; las cifras las calcula el servidor. Si el
     * cliente pudiera mandar montos, podría registrar un reparto que no cuadra.
     */
    public record CobrarMesaRequest(String paymentMethod,
                                    Integer personas,
                                    List<String> metodosPorPersona) {
    }

    @PostMapping("/sessions/{id}/charge")
    @Operation(summary = "Cobrar la mesa completa: suma el consumo, marca las órdenes pagadas y cierra la cuenta")
    public ResponseEntity<?> cobrar(@PathVariable UUID id, @RequestBody CobrarMesaRequest req,
                                    HttpServletRequest http) {
        try {
            Integer personas = req == null ? null : req.personas();
            if (personas != null && personas > 1) {
                return ResponseEntity.ok(sessionService.cobrarDividido(
                        id, personas, req.metodosPorPersona(), usuario(http)));
            }
            return ResponseEntity.ok(sessionService.cobrar(id, req == null ? null : req.paymentMethod()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Previsualiza la división ANTES de cobrar: cuánto paga cada comensal y
     * cuánto asume el negocio. El cajero tiene que poder decirle la cifra a la
     * mesa sin haber cobrado todavía.
     */
    @GetMapping("/sessions/{id}/split-preview")
    @Operation(summary = "Cuánto paga cada persona al dividir la cuenta (no cobra nada)")
    public ResponseEntity<?> previsualizarDivision(@PathVariable UUID id,
                                                   @RequestParam int personas) {
        try {
            return ResponseEntity.ok(sessionService.previsualizarDivision(id, personas));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sessions/open")
    @Operation(summary = "Cuentas de mesa sin cobrar (las que bloquean el cierre de caja)")
    public ResponseEntity<List<TableSession>> abiertas() {
        return ResponseEntity.ok(sessionService.pendientesDeCobro());
    }

    private boolean esAdmin(HttpServletRequest http) {
        return resolver.resolveRole(http.getHeader("Authorization"))
                .map("admin"::equalsIgnoreCase).orElse(false);
    }

    private String usuario(HttpServletRequest http) {
        return resolver.resolveSubject(http.getHeader("Authorization")).orElse("desconocido");
    }
}
