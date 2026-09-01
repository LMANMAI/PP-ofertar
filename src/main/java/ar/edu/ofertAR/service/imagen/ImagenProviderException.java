package ar.edu.ofertAR.service.imagen;

/** La consulta al proveedor falló; el EAN sigue siendo candidato a reintento. */
public class ImagenProviderException extends Exception {
    public ImagenProviderException(String message) {
        super(message);
    }

    public ImagenProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
