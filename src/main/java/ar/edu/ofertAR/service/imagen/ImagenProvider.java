package ar.edu.ofertAR.service.imagen;

import java.util.Optional;

/**
 * Fuente externa de imágenes de producto, consultada por EAN.
 *
 * <p>Las implementaciones se ordenan con {@link org.springframework.core.annotation.Order}
 * y se recorren en cadena: la primera que devuelva una URL gana.
 * Agregar un proveedor nuevo es agregar una clase, sin tocar el servicio.
 */
public interface ImagenProvider {

    /** Identificador corto que queda guardado en producto_imagen.fuente. */
    String nombre();

    /**
     * @param ean EAN ya normalizado a 13 dígitos
     * @return la URL de la imagen, o vacío si el proveedor no conoce el producto
     * @throws ImagenProviderException si la consulta falló (timeout, 5xx, rate limit).
     *         Distinguir "no lo tengo" de "no pude preguntar" es lo que permite
     *         reintentar solo lo segundo.
     */
    Optional<String> buscarImagen(String ean) throws ImagenProviderException;
}
