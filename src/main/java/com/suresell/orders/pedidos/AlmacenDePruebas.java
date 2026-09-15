package com.suresell.orders.pedidos;

import java.util.List;

/**
 * F5.7b: dónde viven la foto y la firma de una entrega (docs/planes/F5-7B-ALMACENAMIENTO-ENTREGAS.md). El cliente nunca
 * habla con el almacén: todo pasa por -mt, que sabe de qué negocio es la sesión. Las rutas empiezan por el negocio.
 */
public interface AlmacenDePruebas {

    /** false si faltan las variables del almacén: la prueba responde 503 y la entrega sigue sin foto. */
    boolean configurado();

    /** Sube el objeto (sustituye uno que no esté registrado: el reintento tras un registro fallido). */
    void subir(String ruta, byte[] contenido, String tipo);

    /** URL firmada, válida {@code segundos}. Se fabrica en cada lectura; nunca se guarda. */
    String firmar(String ruta, int segundos);

    /** Borra los objetos (purga por retención). */
    void borrar(List<String> rutas);

    /** El almacén no respondió o respondió con error: 502 ALMACEN_NO_DISPONIBLE. */
    class NoDisponible extends RuntimeException {
        public NoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
