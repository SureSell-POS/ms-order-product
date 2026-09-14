package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R16: `ms-core-app` lee `debt_transactions.type` como enum DEBIT|CREDIT y
 * `payment_method` como CASH|CARD|TRANSFER|CHECK|OTHER
 * (`DebtTransactionEntity.java:35,48`). Un valor nuevo tumba su pantalla de
 * cartera sin que -mt se entere. V64 lo cierra con dos CHECK; esta prueba falla
 * si alguien los afloja o escribe otro valor. Si el core amplía su enum, se
 * amplían a la vez el CHECK (migración nueva) y las listas de aquí.
 */
@Testcontainers
class ElLibroQueLeeElCoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String NEGOCIO = "__libro_core__";
    private static final String CUENTA = UUID.randomUUID().toString();

    @BeforeAll
    static void migrarYSembrar() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, name, plan) VALUES ('" + NEGOCIO + "', 'Libro core', 'basico')");
            s.execute("INSERT INTO accounts_receivable (id, tenant_id, created_at, credit_limit, customer_document,"
                    + " customer_name, status, total_debt, updated_at) VALUES ('" + CUENTA + "', '" + NEGOCIO
                    + "', now(), 0, 'doc-core', 'Cliente', 'ACTIVE', 0, now())");
        }
    }

    private static Connection conexion() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void escribir(String tipo, String medio) throws SQLException {
        try (Connection c = conexion();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO debt_transactions (id, tenant_id, account_id, amount, created_at, transaction_date,"
                             + " type, payment_method) VALUES (?, ?, ?, 1, now(), current_date, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, NEGOCIO);
            ps.setString(3, CUENTA);
            ps.setString(4, tipo);
            ps.setString(5, medio);
            ps.executeUpdate();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADJUSTMENT", "REVERSAL", "debit", "ABONO"})
    @DisplayName("🔴 un type que el core no entiende se rechaza")
    void tipoAjeno(String tipo) {
        assertThatThrownBy(() -> escribir(tipo, null))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EFECTIVO", "BRE_B", "QR", "cash", ""})
    @DisplayName("🔴 un payment_method que el core no entiende se rechaza")
    void medioAjeno(String medio) {
        assertThatThrownBy(() -> escribir("CREDIT", medio))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASH", "CARD", "TRANSFER", "CHECK", "OTHER"})
    @DisplayName("los cinco medios del core entran (y el abono con medio NULL también)")
    void mediosDelCore(String medio) throws SQLException {
        escribir("CREDIT", medio);
        escribir("DEBIT", medio);
        escribir("CREDIT", null);
    }
}
