package ar.edu.ofertAR.service.imagen;

import java.util.Optional;

/**
 * Fuente externa de datos de producto, consultada por EAN.
 *
 * <p>Las implementaciones se recorren en cadena, en el orden que fija
 * {@code imagenes.orden}: la primera que devuelva imagen gana.
 * Agregar un proveedor nuevo es agregar una clase, sin tocar el servicio.
 */
public interface ImagenProvider {

    /** Identificador corto que queda guardado en producto_imagen.fuente. */
    String nombre();

    /**
     * @param ean EAN ya normalizado a 13 dígitos
     * @return los datos del producto, o vacío si el proveedor no lo conoce
     * @throws ImagenProviderException si la consulta falló (timeout, 5xx, rate limit).
     *         Distinguir "no lo tengo" de "no pude preguntar" es lo que permite
     *         reintentar solo lo segundo.
     */
    Optional<ProductoExterno> buscar(String ean) throws ImagenProviderException;
}
