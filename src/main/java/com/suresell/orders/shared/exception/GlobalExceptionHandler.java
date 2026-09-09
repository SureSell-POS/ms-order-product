package com.suresell.orders.shared.exception;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Convierte lo que falla dentro en algo que una persona pueda leer.
 *
 * <h2>Tres cosas distintas, que no se mezclan</h2>
 *
 * <ul>
 *   <li><b>El mensaje</b> ({@code message}): para quien está en la caja o en el
 *       panel. Dice qué pasó y, cuando se puede, qué hacer. Nunca una traza, un
 *       nombre de clase ni el nombre de una restricción de la base.
 *   <li><b>El código</b> ({@code error}): estable, para que el cliente decida
 *       qué hacer sin leer el texto ({@code YA_EXISTE}, {@code NO_EXISTE}…).
 *   <li><b>El log</b>: técnico y completo. Un rechazo de negocio va a INFO con
 *       su texto; una petición mal formada a WARN con el motivo; un defecto
 *       nuestro a ERROR con la traza entera. Lo que se le esconde al usuario
 *       por seguridad o por claridad <b>no se le esconde al log</b>.
 * </ul>
 *
 * <h2>Lo que costó una tarde (fase 0, 2026-09-09)</h2>
 *
 * Todo {@code RuntimeException} caía al mismo sitio y salía como 500 «Error
 * interno del servidor». Un {@code duplicate key} de la base, que es un dato
 * que ya existe, se contaba igual que un {@code NullPointerException}; el POS
 * lo tomaba por «sin internet» y reintentaba para siempre. Ahora el error de
 * la base se lee (SQLSTATE) antes de decidir el estado, igual que hace el
 * traductor del inventario.
 *
 * <p>Formato acordado con el panel y el POS (ola 4): {@code error} + texto en
 * {@code message} (el inventario lo llama {@code mensaje}; los clientes leen
 * los dos) + {@code campo} opcional cuando el rechazo es de un campo concreto.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Un RAISE EXCEPTION nuestro: el texto está escrito para leerse. */
    private static final String RAISE_NUESTRO = "P0001";
    private static final String CLAVE_DUPLICADA = "23505";
    private static final String REFERENCIA_INEXISTENTE = "23503";
    private static final String REGLA_INCUMPLIDA = "23514";
    private static final String NO_NULO = "23502";

    private static Map<String, String> cuerpo(String codigo, String mensaje) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", codigo);
        m.put("message", mensaje);
        return m;
    }

    private static ResponseEntity<Map<String, String>> respuesta(HttpStatus estado, String codigo, String mensaje) {
        return ResponseEntity.status(estado).body(cuerpo(codigo, mensaje));
    }

    /** Recorre las causas hasta el error real de la base (Spring lo envuelve con el SQL entero). */
    static SQLException sqlDetras(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** Postgres añade "Where: PL/pgSQL function ..." al mensaje; eso no viaja. */
    static String soloLaPrimeraLinea(String mensaje) {
        if (mensaje == null) {
            return "";
        }
        int corte = mensaje.indexOf('\n');
        return (corte < 0 ? mensaje : mensaje.substring(0, corte)).trim();
    }

    // ----------------------------------------------------------------- negocio

    @ExceptionHandler(PagerOcupadoException.class)
    public ResponseEntity<Map<String, String>> handlePagerOcupadoException(PagerOcupadoException ex) {
        logger.info("Rechazo de negocio ({}): {}", ex.getFlag(), ex.getMessage());
        return respuesta(HttpStatus.CONFLICT, ex.getFlag(), ex.getMessage());
    }

    @ExceptionHandler(MesaDuplicadaException.class)
    public ResponseEntity<Map<String, String>> handleMesaDuplicadaException(MesaDuplicadaException ex) {
        logger.info("Rechazo de negocio ({}): {}", ex.getFlag(), ex.getMessage());
        return respuesta(HttpStatus.CONFLICT, ex.getFlag(), ex.getMessage());
    }

    @ExceptionHandler(OrderEditNotAllowedException.class)
    public ResponseEntity<Map<String, String>> handleOrderEditNotAllowedException(OrderEditNotAllowedException ex) {
        logger.info("Rechazo de negocio ({}): {}", ex.getErrorCode(), ex.getMessage());
        return respuesta(HttpStatus.FORBIDDEN, ex.getErrorCode(), ex.getMessage());
    }

    /** Un dato que no vale. El texto lo escribió quien validó, para una persona. */
    /** V51: un campo del cuerpo de un código no vale. 400 con `campo`, el contrato de A/B/C. */
    @ExceptionHandler(com.suresell.orders.application.usecase.CodigosDeProducto.CampoInvalido.class)
    public ResponseEntity<Map<String, String>> handleCampoInvalido(
            com.suresell.orders.application.usecase.CodigosDeProducto.CampoInvalido ex) {
        Map<String, String> m = cuerpo("CAMPO_INVALIDO", ex.getMessage());
        m.put("campo", ex.campo());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    /** V51: el producto al que se le quiere poner un código no está en este negocio. */
    @ExceptionHandler(com.suresell.orders.application.usecase.CodigosDeProducto.ProductoInexistente.class)
    public ResponseEntity<Map<String, String>> handleProductoInexistente(
            com.suresell.orders.application.usecase.CodigosDeProducto.ProductoInexistente ex) {
        return respuesta(HttpStatus.NOT_FOUND, "NO_EXISTE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.info("Dato rechazado: {}", ex.getMessage());
        return respuesta(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    /** La petición está bien; lo que no encaja es el estado de los datos. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalStateException(IllegalStateException ex) {
        logger.info("Rechazo de negocio: {}", ex.getMessage());
        return respuesta(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    // ------------------------------------------------------ petición mal hecha

    /** Un campo del cuerpo no pasa su validación. Va con `campo` para pintarlo junto al input. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
        var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
        String campo = error.map(e -> e.getField()).orElse(null);
        String message = error.map(e -> e.getField() + ": " + e.getDefaultMessage()).orElse("Solicitud inválida");
        logger.warn("Petición inválida: {}", message);
        Map<String, String> m = cuerpo("VALIDATION_ERROR", message);
        if (campo != null) {
            m.put("campo", campo);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    /** Falta una cabecera o un parámetro, el cuerpo no se puede leer, o un id no tiene la forma esperada. */
    @ExceptionHandler({
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> handlePeticionMalFormada(Exception ex) {
        logger.warn("Petición mal formada: {}", soloLaPrimeraLinea(ex.getMessage()));
        String message = ex instanceof HttpMessageNotReadableException
                ? "El cuerpo de la petición no se pudo leer. Revisa el formato de los datos enviados."
                : soloLaPrimeraLinea(ex.getMessage());
        return respuesta(HttpStatus.BAD_REQUEST, "PETICION_INCOMPLETA", message);
    }

    /** Una ruta que no existe. Antes era un 500 con «Error interno del servidor». */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleRutaInexistente(NoResourceFoundException ex) {
        logger.warn("Ruta inexistente: {}", ex.getResourcePath());
        return respuesta(HttpStatus.NOT_FOUND, "NOT_FOUND", "Esa ruta no existe en este servicio.");
    }

    // ---------------------------------------------------------------- la base

    /**
     * El error de la base se lee antes de decidir. Un {@code duplicate key} es
     * un 409 y no un 500: el dato ya existe, y reintentar no lo va a cambiar.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException ex) {
        SQLException sql = sqlDetras(ex);
        String sqlState = sql == null ? null : sql.getSQLState();
        String detalle = sql == null ? soloLaPrimeraLinea(ex.getMessage()) : soloLaPrimeraLinea(sql.getMessage());

        if (RAISE_NUESTRO.equals(sqlState)) {
            logger.info("Rechazo de la base (P0001): {}", detalle);
            return respuesta(HttpStatus.CONFLICT, "CONFLICT", detalle);
        }
        if (CLAVE_DUPLICADA.equals(sqlState)) {
            logger.warn("Clave duplicada: {}", detalle);
            return respuesta(HttpStatus.CONFLICT, "YA_EXISTE",
                    "Ya existe un registro con esos mismos datos. Si lo estás reintentando, ya quedó guardado.");
        }
        if (REFERENCIA_INEXISTENTE.equals(sqlState)) {
            logger.warn("Referencia inexistente: {}", detalle);
            return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EXISTE",
                    "Hace referencia a algo que no existe en este negocio (o que ya se borró). Recarga y vuelve a intentarlo.");
        }
        if (REGLA_INCUMPLIDA.equals(sqlState) || NO_NULO.equals(sqlState)) {
            logger.warn("Regla de la base incumplida: {}", detalle);
            return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CUMPLE_REGLA",
                    "El dato no cumple una regla del sistema. Queda registrado para revisarlo.");
        }
        // Defecto nuestro: la traza al log, nada de por dentro hacia fuera.
        logger.error("Error de base no previsto (SQLSTATE {})", sqlState, ex);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "No se pudo completar la operación. Queda registrado para revisarlo.");
    }

    // -------------------------------------------------------------- lo demás

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("no encontrada") || message.contains("not found")) {
            logger.info("No encontrado: {}", ex.getMessage());
            return respuesta(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
        }
        logger.error("Unexpected runtime exception", ex);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "No se pudo completar la operación. Queda registrado para revisarlo.");
    }
}
