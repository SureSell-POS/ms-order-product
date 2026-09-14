package com.suresell.orders.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.suresell.orders.domain.model.Order;
import com.suresell.orders.domain.model.OrderItem;
import com.suresell.orders.domain.service.GramaticaCanonica;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan de mayoristas F1.4 (pareja de F1.7 en el POS, feat/mayoristas-pos ad54206):
 * {@code clienteDocumento}, {@code vendedorId} y {@code condicionPago} quedan fuera
 * del hash del evento. Atribuir una venta no cambia el hecho económico, y meterlos
 * obligaría a subir {@code VERSION_CANONICA} en Java y en TS a la vez.
 *
 * <p>El control de que la prueba no es vacía va dentro: cambiar el total SÍ cambia
 * la canónica. Sin eso, una proyección que devolviera siempre lo mismo pasaría.
 */
class CadenaDelServidorExclusionesTest {

    private static final GramaticaCanonica.Identidad ID = new GramaticaCanonica.Identidad(
            "7b2a0c7e-0000-4000-8000-000000000001", 1, 42L, "order_created", "clave-1", null);
    private static final Instant OCURRIDO = Instant.parse("2026-09-13T15:00:00.000Z");

    private static Order venta(String medio, BigDecimal total) {
        Order o = new Order();
        o.setUuidId(UUID.fromString("00000000-0000-4000-8000-000000000042"));
        o.setPaymentMethod(medio);
        o.setSubtotal(total);
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setTotal(total);
        OrderItem it = new OrderItem();
        it.setProductId("aceite-x12");
        it.setQuantity(3);
        it.setUnitPrice(new BigDecimal("112000.00"));
        it.setTotalPrice(total);
        o.setItems(List.of(it));
        return o;
    }

    private static String canonica(Order o) {
        return GramaticaCanonica.formaCanonica(ID, CadenaDelServidor.hechosDe(o, OCURRIDO));
    }

    @Test
    @DisplayName("🔴 cliente, vendedor, condición, origen y pedido no mueven ni la canónica ni el hash")
    void lasAtribucionesQuedanFuera() {
        Order sin = venta("CREDITO", new BigDecimal("336000.00"));
        Order con = venta("CREDITO", new BigDecimal("336000.00"));
        con.setClienteDocumento("900123456");
        con.setVendedorId(7L);
        con.setCondicionPago("CREDITO");
        con.setOrigen("caja");
        con.setPedidoId(UUID.randomUUID());

        assertThat(canonica(con)).isEqualTo(canonica(sin));
        assertThat(GramaticaCanonica.hash(ID, CadenaDelServidor.hechosDe(con, OCURRIDO)))
                .isEqualTo(GramaticaCanonica.hash(ID, CadenaDelServidor.hechosDe(sin, OCURRIDO)));

        con.setVendedorId(8L);
        con.setClienteDocumento("800555666");
        assertThat(canonica(con)).isEqualTo(canonica(sin));
    }

    @Test
    @DisplayName("control: el medio CREDITO sí entra, y el total también")
    void loEconomicoSiEntra() {
        String credito = canonica(venta("CREDITO", new BigDecimal("336000.00")));
        assertThat(credito).contains("CREDITO");
        assertThat(canonica(venta("CASH", new BigDecimal("336000.00")))).isNotEqualTo(credito);
        assertThat(canonica(venta("CREDITO", new BigDecimal("336000.01")))).isNotEqualTo(credito);
    }
}
