package ar.edu.ofertAR.model;

/** Resultado de la última resolución de imagen para un EAN. */
public enum EstadoImagen {
    /** Se encontró una URL válida. */
    OK,
    /** Ningún proveedor conoce el EAN. Cache negativo: no reintentar enseguida. */
    NOT_FOUND,
    /** Falló la consulta (timeout, 5xx, rate limit). Se reintenta antes que NOT_FOUND. */
    ERROR
}
