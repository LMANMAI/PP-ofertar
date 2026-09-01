package ar.edu.ofertAR.service.imagen;

/**
 * Espaciador de requests por proveedor: garantiza un intervalo mínimo entre
 * llamadas salientes para no golpear la API externa en ráfaga.
 *
 * <p>Deliberadamente simple (un solo hilo consume las imágenes). Si algún día
 * el enriquecimiento pasa a ser paralelo, reemplazar por un token bucket.
 */
public class Throttle {

    private final long intervaloMs;
    private long proximoPermitido = 0L;

    public Throttle(double requestsPorSegundo) {
        this.intervaloMs = requestsPorSegundo <= 0 ? 0 : (long) (1000.0 / requestsPorSegundo);
    }

    /** Bloquea hasta que esté permitido emitir la siguiente request. */
    public synchronized void esperarTurno() throws InterruptedException {
        long ahora = System.currentTimeMillis();
        if (ahora < proximoPermitido) {
            Thread.sleep(proximoPermitido - ahora);
            ahora = System.currentTimeMillis();
        }
        proximoPermitido = ahora + intervaloMs;
    }
}
