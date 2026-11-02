package ar.edu.ofertAR.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Una notificación de prueba cada {@link #VENTANA_MS} por usuario, en memoria.
 * Cada prueba es una llamada a Expo con todos los dispositivos del usuario:
 * el tope evita que un botón apretado varias veces (o un script) la repita.
 * Con varias réplicas cada una cuenta por separado, igual que
 * {@link ar.edu.ofertAR.security.RateLimitFilter}.
 */
@Component
public class PushTestRateLimiter {

    static final long VENTANA_MS = 10_000L;
    private static final int PODAR_DESDE = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<Long, Long> ultimoIntento = new ConcurrentHashMap<>();

    public PushTestRateLimiter() {
        this(Clock.systemUTC());
    }

    PushTestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Registra el intento si está permitido. Si no, devuelve cuántos segundos
     * faltan (redondeado para arriba, al menos 1) y no cuenta el intento: el
     * reintento queda habilitado a los 10 s del último que pasó.
     */
    public OptionalLong tryAcquire(Long userId) {
        long ahora = clock.millis();
        long[] espera = {0};
        ultimoIntento.compute(userId, (id, anterior) -> {
            if (anterior != null && ahora - anterior < VENTANA_MS) {
                espera[0] = Math.max(1, (VENTANA_MS - (ahora - anterior) + 999) / 1000);
                return anterior;
            }
            return ahora;
        });
        if (ultimoIntento.size() > PODAR_DESDE) {
            ultimoIntento.entrySet().removeIf(e -> ahora - e.getValue() >= VENTANA_MS);
        }
        return espera[0] > 0 ? OptionalLong.of(espera[0]) : OptionalLong.empty();
    }
}
