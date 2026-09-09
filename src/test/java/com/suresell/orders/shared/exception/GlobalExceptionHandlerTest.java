package com.suresell.orders.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

/**
 * Lo que un error de la base le dice a la persona (ola 4, «que funcione»).
 *
 * <p>Antes todo {@code RuntimeException} salía como 500 «Error interno del
 * servidor»: un {@code duplicate key} —que es un dato que YA existe— se contaba
 * igual que un {@code NullPointerException}, y el POS lo tomaba por «sin
 * internet» y reintentaba para siempre.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException deLaBase(String sqlState, String mensaje) {
        return new DataIntegrityViolationException("could not execute statement [" + mensaje + "]",
                new org.hibernate.exception.ConstraintViolationException(mensaje,
                        new SQLException(mensaje, sqlState), "una_restriccion"));
    }

    @Test
    void unaClaveDuplicadaEsUn409NoUn500() {
        ResponseEntity<Map<String, String>> r = handler.handleDataAccess(
                deLaBase("23505", "ERROR: duplicate key value violates unique constraint \"terminals_pkey\""));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("error", "YA_EXISTE");
        // Ni el nombre de la restricción ni el SQL viajan al usuario.
        assertThat(r.getBody().get("message")).doesNotContain("terminals_pkey").doesNotContain("ERROR:");
    }

    @Test
    void unRaiseNuestroViajaTalCual() {
        ResponseEntity<Map<String, String>> r = handler.handleDataAccess(
                deLaBase("P0001", "La sede 3 no admite rastreador.\nWhere: PL/pgSQL function fn_x() line 4"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("message", "La sede 3 no admite rastreador.");
    }

    @Test
    void unaReferenciaInexistenteEsUn422ConTextoParaPersonas() {
        ResponseEntity<Map<String, String>> r = handler.handleDataAccess(
                deLaBase("23503", "ERROR: insert or update on table \"orders\" violates foreign key constraint \"fk_site\""));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).containsEntry("error", "NO_EXISTE");
        assertThat(r.getBody().get("message")).doesNotContain("fk_site");
    }

    @Test
    void loQueNoSeReconoceSigueSiendo500SinContarNadaPorDentro() {
        ResponseEntity<Map<String, String>> r = handler.handleDataAccess(
                deLaBase("42703", "ERROR: column we1_0.commission_percentage does not exist"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsEntry("error", "INTERNAL_SERVER_ERROR");
        assertThat(r.getBody().get("message")).doesNotContain("commission_percentage");
    }

    @Test
    void unaRutaInexistenteEsUn404NoUn500() {
        ResponseEntity<Map<String, String>> r = handler.handleRutaInexistente(
                new NoResourceFoundException(HttpMethod.POST, "orders//create"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("error", "NOT_FOUND");
    }

    @Test
    void unRechazoDeNegocioConservaSuTextoYSuCodigo() {
        ResponseEntity<Map<String, String>> r = handler.handleIllegalStateException(
                new IllegalStateException("La caja ya está cerrada hoy."));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("error", "CONFLICT")
                .containsEntry("message", "La caja ya está cerrada hoy.");
    }
}
