package ar.edu.ofertAR.service.imagen;

/**
 * Espaciador de requests por proveedor: garantiza un intervalo mínimo entre
 * llamadas salientes para no golpear la API externa en ráfaga.
 *
 * <p>Cada llamada reserva su turno dentro de una sección crítica de nanosegundos
 * y duerme FUERA de ella. Antes dormía con el monitor tomado, así que un hilo
 * dormido bloqueaba a todos los demás incluso para reservar.
 */
public class Throttle {

    private final long intervaloMs;
    private long proximoPermitido = 0L;

    public Throttle(double requestsPorSegundo) {
        this.intervaloMs = requestsPorSegundo <= 0 ? 0 : (long) (1000.0 / requestsPorSegundo);
    }

    /** Bloquea hasta que esté permitido emitir la siguiente request. */
    public void esperarTurno() throws InterruptedException {
        dormir(reservar(Long.MAX_VALUE));
    }

    /**
     * Como {@link #esperarTurno()}, pero si el turno queda más lejos que
     * {@code maxEsperaMs} no reserva nada y devuelve false de inmediato.
     */
    public boolean esperarTurno(long maxEsperaMs) throws InterruptedException {
        long espera = reservar(maxEsperaMs);
        if (espera < 0) {
            return false;
        }
        dormir(espera);
        return true;
    }

    /** @return ms a esperar, o -1 si supera {@code maxEsperaMs} (y entonces no se reserva). */
    private synchronized long reservar(long maxEsperaMs) {
        long ahora = System.currentTimeMillis();
        long turno = Math.max(ahora, proximoPermitido);
        long espera = turno - ahora;
        if (espera > maxEsperaMs) {
            return -1;
        }
        proximoPermitido = turno + intervaloMs;
        return espera;
    }

    private static void dormir(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
