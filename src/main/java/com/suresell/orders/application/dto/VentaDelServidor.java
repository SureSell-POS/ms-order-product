package com.suresell.orders.application.dto;

import com.suresell.orders.mayorista.ResolucionDePrecios;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que solo el SERVIDOR pone en una venta que nace de despachar un pedido (plan de
 * mayoristas F5.5). No viaja en el JSON del POS: {@link OrderRequestRecord} rechaza
 * campos desconocidos, así que un cliente no puede fijar su origen, su sede, su plazo
 * ni sus precios.
 *
 * @param pedidoId      el pedido despachado ({@code orders.pedido_id}, la única fuente del enlace)
 * @param siteId        la sede de despacho; NULL = la por defecto, como toda venta
 * @param plazoDias     el plazo pactado en el pedido (V71); NULL = sin plazo pactado
 * @param condicionPago CONTADO (contraentrega, plazo 0) o CREDITO
 * @param precios       el precio congelado de cada producto, en vez de resolver el de hoy (Q2)
 */
public record VentaDelServidor(UUID pedidoId, Long siteId, Short plazoDias, String condicionPago,
                               Map<String, ResolucionDePrecios.Precio> precios) {}
