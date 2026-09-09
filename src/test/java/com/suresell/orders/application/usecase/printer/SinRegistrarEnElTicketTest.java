package com.suresell.orders.application.usecase.printer;

import com.suresell.orders.application.dto.OrderItemResponseRecord;
import com.suresell.orders.application.dto.OrderResponseRecord;
import com.suresell.orders.domain.port.out.PrinterPort;
import com.suresell.orders.infrastructure.printer.EscPosBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V51 «vender sin registrar»: la línea no está en el catálogo, así que
 * `nameProduct` llega nulo y el nombre que tecleó la cajera viene en
 * `instructions`. El ticket imprimía «null». Ahora imprime el nombre, y no
 * lo repite como nota.
 */
class SinRegistrarEnElTicketTest {

    static class ImpresoraQueGuarda implements PrinterPort {
        byte[] ultimo;
        @Override public void printBytes(byte[] data) { ultimo = data; }
        @Override public void openDrawer() { }
        @Override public boolean isPrinterReady() { return true; }
    }

    private static OrderResponseRecord orden(OrderItemResponseRecord... items) {
        return new OrderResponseRecord(77L, null, null, LocalDateTime.of(2026, 9, 9, 8, 0),
                new BigDecimal("3500"), new BigDecimal("3500"), "pagado", "CASH", null, null, null,
                false, true, false, null, List.of(items), null, null, null, null,
                null, null, null); // recibo_estado, recibo_motivo, recibo_actualizado_at (V52)
    }

    private static String ticketDe(OrderResponseRecord orden) {
        ImpresoraQueGuarda impresora = new ImpresoraQueGuarda();
        new PrintOrderTicketUseCase(impresora, new EscPosBuilder()).execute(orden);
        return new String(impresora.ultimo, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("🔴 una línea sin producto en el catálogo imprime el nombre tecleado, no «null»")
    void sinRegistrarImprimeElNombre() {
        String ticket = ticketDe(orden(
                new OrderItemResponseRecord("sin-registrar:9f1c", null, 1,
                        new BigDecimal("3500"), new BigDecimal("3500"), "Galleta importada", null)));
        assertThat(ticket).contains(" 1    Galleta importada");
        assertThat(ticket).doesNotContain("null");
        assertThat(ticket).doesNotContain("* Galleta importada");
    }

    @Test
    @DisplayName("una línea normal sigue igual: nombre del catálogo y la nota debajo con asterisco")
    void lineaNormalNoCambia() {
        String ticket = ticketDe(orden(
                new OrderItemResponseRecord("P-1", "Hamburguesa", 2,
                        new BigDecimal("15000"), new BigDecimal("30000"), "sin cebolla", null)));
        assertThat(ticket).contains(" 2    Hamburguesa");
        assertThat(ticket).contains("* sin cebolla");
    }
}
