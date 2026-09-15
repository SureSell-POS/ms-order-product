package com.suresell.orders.infrastructure.web;

import com.suresell.orders.cartera.Cartera;
import com.suresell.orders.cartera.Cartera.Quien;
import com.suresell.orders.cartera.ProcesoDeInsolvencia;
import com.suresell.orders.multitenant.JwtTenantResolver;
import com.suresell.orders.multitenant.TenantContext;
import com.suresell.orders.multitenant.UsuarioDeLaPeticion;
import com.suresell.orders.shared.exception.SoloAdministradorException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cuentas por cobrar (plan de mayoristas F4.4, contrato §7.4). La ruta exige el
 * módulo {@code cartera} ({@code ModuleAccessFilter}). Las respuestas van en
 * camelCase. Un vendedor ve y cobra solo a sus clientes; el filtro lo pone el
 * servidor con el id del token.
 *
 * <p>🔴 <b>El cliente de otro vendedor responde 400, no 404, y es a propósito</b>
 * (decidido por ECM el 2026-09-14): 400 {@code campo: clienteDocumento} con el MISMO
 * texto que un cliente que no existe («Ese cliente no existe en el negocio.»), así
 * que no confirma que exista. Es el contrato que ya consume el panel en estado de
 * cuenta, recibos, cupo, insolvencia y comportamiento de pago. No cambiarlo a 404
 * sin cambiarlo en todo {@code /api/cartera} y avisar al panel.
 */
@RestController
@RequestMapping("/api/cartera")
@Tag(name = "Cartera", description = "Cuentas por cobrar por documento: recibos, estado de cuenta, cupo")
public class CarteraController {

    static final Set<String> ROLES_QUE_COBRAN = Set.of("admin", "cajero", "vendedor");

    private final Cartera cartera;
    private final ProcesoDeInsolvencia insolvencias;
    private final JwtTenantResolver tokens;
    private final UsuarioDeLaPeticion usuarios;

    public CarteraController(Cartera cartera, ProcesoDeInsolvencia insolvencias, JwtTenantResolver tokens,
                             UsuarioDeLaPeticion usuarios) {
        this.cartera = cartera;
        this.insolvencias = insolvencias;
        this.tokens = tokens;
        this.usuarios = usuarios;
    }

    @GetMapping("/clientes")
    @Operation(summary = "F4.4 — Resumen por cliente: saldo, vencido, factura más vieja, cupo, excede")
    public List<Map<String, Object>> clientes(@RequestParam(required = false) String edad,
                                              @RequestParam(required = false) Long vendedorId,
                                              @RequestParam(required = false) String q,
                                              HttpServletRequest http) {
        return cartera.clientes(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), edad, vendedorId, q);
    }

    @GetMapping("/clientes/{documento}/estado-de-cuenta")
    @Operation(summary = "F4.4 — Facturas con saldo, recibos del periodo, edades y la frase para WhatsApp")
    public Map<String, Object> estadoDeCuenta(@PathVariable String documento,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                              HttpServletRequest http) {
        return cartera.estadoDeCuenta(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), documento, desde, hasta);
    }

    @GetMapping("/clientes/{documento}/comportamiento-de-pago")
    @Operation(summary = "F10.3 — Cómo paga el cliente: días promedio de pago por factura, % a tiempo, atraso; solo lectura")
    public Map<String, Object> comportamientoDePago(@PathVariable String documento,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                    HttpServletRequest http) {
        return cartera.comportamientoDePago(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), documento, desde, hasta);
    }

    @PostMapping("/recibos")
    @Operation(summary = "F4.4 — Registrar un abono: recibo con número, aplicado a la más antigua si no se eligen facturas. "
            + "201 nuevo; 200 si es el reintento de la misma clave")
    public ResponseEntity<Map<String, Object>> registrarRecibo(@RequestBody Cartera.NuevoRecibo cuerpo,
                                                               HttpServletRequest http) {
        Cartera.Registro registro = cartera.registrarRecibo(quien(http, ROLES_QUE_COBRAN, "registrar un abono"), cuerpo);
        return ResponseEntity.status(registro.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(registro.recibo());
    }

    public record Anulacion(String motivo) {}

    @PostMapping("/recibos/{id}/anular")
    @Operation(summary = "F4.4 — Anular un recibo (solo admin): otro recibo con el motivo; la deuda vuelve")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> anular(@PathVariable UUID id, @RequestBody Anulacion cuerpo, HttpServletRequest http) {
        return cartera.anular(quien(http, Set.of("admin"), "anular un recibo"), id, cuerpo == null ? null : cuerpo.motivo());
    }

    public record CambioDeCupo(BigDecimal cupo) {}

    @PutMapping("/clientes/{documento}/cupo")
    @Operation(summary = "F4.4 — Cambiar el cupo de crédito (solo admin); queda en la historia del cliente")
    public Map<String, Object> cambiarCupo(@PathVariable String documento, @RequestBody CambioDeCupo cuerpo,
                                           HttpServletRequest http) {
        return cartera.cambiarCupo(quien(http, Set.of("admin"), "cambiar el cupo de un cliente"), documento,
                cuerpo == null ? null : cuerpo.cupo());
    }

    public record Insolvencia(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde) {}

    @PostMapping("/clientes/{documento}/insolvencia")
    @Operation(summary = "F4.4 — Marcar (o con desde=null, levantar) la insolvencia de un cliente (solo admin). Desde F4.13: "
            + "con fecha registra INICIO sin documento y régimen pendiente; con null y proceso abierto, 409 LEVANTAR_SIN_ETAPA; "
            + "409 LIQUIDACION_NO_SE_LEVANTA en liquidación; 400 fecha si es posterior a hoy")
    public Map<String, Object> insolvencia(@PathVariable String documento, @RequestBody Insolvencia cuerpo,
                                           HttpServletRequest http) {
        return insolvencias.marcarDesdeLaMarcaAnterior(quien(http, Set.of("admin"), "marcar la insolvencia de un cliente"),
                documento, cuerpo == null ? null : cuerpo.desde());
    }

    @PostMapping("/clientes/{documento}/insolvencia/etapas")
    @Operation(summary = "F4.13 — Informar una etapa del proceso de insolvencia (solo admin): SOLICITUD, SOLICITUD_NO_ADMITIDA, "
            + "INICIO (toma la foto de la deuda), ACUERDO_CONFIRMADO, CUMPLIDO_TERMINADO, LIQUIDACION o CORRECCION_DE_ERROR "
            + "(con corrigeEtapaId corrige la fecha de esa etapa). 409 ETAPA_NO_PERMITIDA o LIQUIDACION_NO_SE_LEVANTA")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> informarEtapa(@PathVariable String documento, @RequestBody ProcesoDeInsolvencia.NuevaEtapa cuerpo,
                                             HttpServletRequest http) {
        return insolvencias.informarEtapa(quien(http, Set.of("admin"), "informar una etapa de insolvencia"), documento, cuerpo);
    }

    @PutMapping("/clientes/{documento}/insolvencia/credito-posterior")
    @Operation(summary = "F4.13 — Habilitar o deshabilitar la venta a crédito después del inicio del proceso (solo admin): "
            + "{habilitado, plazoMaximoDias 0..30 (8 si no llega), motivo}. 409 SIN_PROCESO_EN_CURSO o CREDITO_POSTERIOR_EN_LIQUIDACION")
    public Map<String, Object> creditoPosterior(@PathVariable String documento, @RequestBody ProcesoDeInsolvencia.CreditoPosterior cuerpo,
                                                HttpServletRequest http) {
        return insolvencias.cambiarCreditoPosterior(quien(http, Set.of("admin"), "habilitar el crédito después del inicio"),
                documento, cuerpo);
    }

    @GetMapping("/clientes/{documento}/insolvencia")
    @Operation(summary = "F4.13 — El proceso de insolvencia del cliente: etapa vigente, inicio, corte, régimen, etapas, foto y "
            + "cifras (deuda anterior al inicio, saldo a favor, ventas posteriores)")
    public Map<String, Object> proceso(@PathVariable String documento, HttpServletRequest http) {
        return insolvencias.proceso(quien(http, ROLES_QUE_COBRAN, "ver la cartera"), documento);
    }

    @GetMapping("/clientes/{documento}/insolvencia/foto")
    @Operation(summary = "F4.13 — La deuda al inicio del proceso factura por factura, con su huella (solo admin). "
            + "?formato=csv para descargarla y presentarla al proceso")
    public ResponseEntity<?> foto(@PathVariable String documento, @RequestParam(required = false) String formato,
                                  HttpServletRequest http) {
        Quien quien = quien(http, Set.of("admin"), "ver la deuda al inicio del proceso");
        if ("csv".equalsIgnoreCase(formato)) {
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"deuda-al-inicio-" + documento.replaceAll("[^A-Za-z0-9-]", "") + ".csv\"")
                    .contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                    .body(insolvencias.fotoEnCsv(quien, documento));
        }
        return ResponseEntity.ok(insolvencias.foto(quien, documento));
    }

    @PostMapping("/clientes/{documento}/saldo-a-favor/devolver")
    @Operation(summary = "F4.12 — Devolver saldo a favor al cliente (solo admin, cualquier medio): egreso con número. "
            + "201 nuevo; 200 si es el reintento de la misma clave. A un cliente en insolvencia, solo DEVUELTO_AL_PROCESO con referencia")
    public ResponseEntity<Map<String, Object>> devolverSaldoAFavor(@PathVariable String documento,
                                                                   @RequestBody Cartera.Devolucion cuerpo,
                                                                   HttpServletRequest http) {
        Cartera.Registro registro = cartera.devolverSaldoAFavor(
                quien(http, Set.of("admin"), "devolver saldo a favor"), documento, cuerpo);
        return ResponseEntity.status(registro.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(registro.recibo());
    }

    @GetMapping("/resumen")
    @Operation(summary = "F4.4 — Por cobrar, vencido por edad y los que más deben (solo admin)")
    public Map<String, Object> resumen(HttpServletRequest http) {
        return cartera.resumenDelNegocio(quien(http, Set.of("admin"), "ver el resumen de cartera"));
    }

    @GetMapping("/politica-de-credito")
    @Operation(summary = "F5.4 — La política de crédito del negocio (AVISAR o RETENER_PEDIDO con días de mora) y sus cambios")
    public Map<String, Object> politicaDeCredito(HttpServletRequest http) {
        return cartera.politicaDeCredito(quien(http, ROLES_QUE_COBRAN, "ver la política de crédito"));
    }

    public record PoliticaDeCredito(String politica, Integer diasMoraParaRetener) {}

    @PutMapping("/politica-de-credito")
    @Operation(summary = "F5.4 — Cambiar la política de crédito (solo admin): con RETENER_PEDIDO, el pedido de un cliente "
            + "con una factura vencida hace más de N días nace RETENIDO (FACTURA_VENCIDA). Queda en su historia")
    public Map<String, Object> cambiarPoliticaDeCredito(@RequestBody PoliticaDeCredito cuerpo, HttpServletRequest http) {
        return cartera.cambiarPoliticaDeCredito(quien(http, Set.of("admin"), "cambiar la política de crédito"),
                cuerpo == null ? null : cuerpo.politica(), cuerpo == null ? null : cuerpo.diasMoraParaRetener());
    }

    @GetMapping("/ventas-a-insolvente")
    @Operation(summary = "F4.11 — Ventas a crédito que una caja le hizo a un cliente ya en insolvencia (solo admin). "
            + "Entraron con su deuda; pendientes=true (defecto) solo las que esperan decisión")
    public List<Map<String, Object>> ventasAInsolvente(@RequestParam(required = false) Boolean pendientes,
                                                       HttpServletRequest http) {
        return cartera.ventasAInsolvente(quien(http, Set.of("admin"), "ver las ventas a clientes en insolvencia"), pendientes);
    }

    public record ResolucionDeVenta(String decision, String nota) {}

    @PostMapping("/ventas-a-insolvente/{id}/resolucion")
    @Operation(summary = "F4.11 — Decidir sobre una venta a un cliente en insolvencia (solo admin): DEJAR_COMO_DEUDA o "
            + "COBRAR_DE_CONTADO. Se anexa; no mueve la deuda. 409 VENTA_YA_RESUELTA si ya tiene decisión")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> resolverVentaAInsolvente(@PathVariable UUID id, @RequestBody ResolucionDeVenta cuerpo,
                                                        HttpServletRequest http) {
        return cartera.resolverVentaAInsolvente(quien(http, Set.of("admin"), "resolver una venta a un cliente en insolvencia"),
                id, cuerpo == null ? null : cuerpo.decision(), cuerpo == null ? null : cuerpo.nota());
    }

    private Quien quien(HttpServletRequest http, Set<String> roles, String queCosa) {
        String rol = tokens.resolveRole(http.getHeader("Authorization")).orElse("");
        if (!roles.contains(rol)) {
            throw new SoloAdministradorException(queCosa);
        }
        return new Quien(TenantContext.get(), rol, usuarios.id().orElse(null));
    }
}
