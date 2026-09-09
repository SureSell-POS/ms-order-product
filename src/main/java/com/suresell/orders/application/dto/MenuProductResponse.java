package com.suresell.orders.application.dto;

import java.util.List;

public record MenuProductResponse(
        String idProduct,
        String nameProduct,
        Integer price,
        Boolean active,
        String categoryId,
        String categoryName,
        /**
         * V51: los códigos vigentes del producto (EAN, PLU…). Campo ADITIVO: un
         * POS viejo lo ignora; nunca es null (lista vacía si no tiene).
         */
        List<CodigoDeProductoResponse> codigos
) {
}
