package com.suresell.orders.mayorista;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.suresell.orders.application.dto.OrderItemRequestRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * F1.5: una venta de 50 productos resuelve sus precios en UN viaje a la base.
 * Antes eran 50 llamadas a {@code fn_precio_para} (anexo A-4 del plan).
 */
class ResolucionDePreciosUnViajeTest {

    @Test
    @DisplayName("🔴 50 productos distintos: una sola consulta, contra fn_precios_para")
    @SuppressWarnings("unchecked")
    void cincuentaLineasUnViaje() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<OrderItemRequestRecord> lineas = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            lineas.add(new OrderItemRequestRecord("producto-" + i, 1 + i % 3, BigDecimal.ONE, null, null));
        }
        new ResolucionDePrecios(jdbc).resolver("900123456", lineas, false);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).query(sql.capture(), any(RowMapper.class), any(), any(), any(), any());
        assertThat(sql.getValue()).contains("fn_precios_para").doesNotContain("fn_precio_para(");
    }
}
