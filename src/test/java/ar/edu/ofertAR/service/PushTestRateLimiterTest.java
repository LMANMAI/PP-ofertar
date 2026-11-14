package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class PushTestRateLimiterTest {

    /** Reloj que avanza sólo cuando el test lo pide. */
    private static class RelojManual extends Clock {
        long millis = 1_000_000L;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private final RelojManual reloj = new RelojManual();
    private final PushTestRateLimiter limiter = new PushTestRateLimiter(reloj);

    @Test
    @DisplayName("la primera prueba pasa; la segunda dentro de los 10 s no, y dice cuánto esperar")
    void segundaDentroDeLaVentana_seFrena() {
        assertTrue(limiter.tryAcquire(1L).isEmpty());

        reloj.millis += 3_000;
        OptionalLong espera = limiter.tryAcquire(1L);

        assertTrue(espera.isPresent());
        assertEquals(7, espera.getAsLong());
    }

    @Test
    @DisplayName("un intento frenado no corre la ventana: a los 10 s del último que pasó, vuelve a pasar")
    void intentoFrenado_noReiniciaLaVentana() {
        limiter.tryAcquire(1L);
        reloj.millis += 9_999;
        assertTrue(limiter.tryAcquire(1L).isPresent());

        reloj.millis += 1;
        assertTrue(limiter.tryAcquire(1L).isEmpty());
    }

    @Test
    @DisplayName("el tope es por usuario")
    void porUsuario() {
        assertTrue(limiter.tryAcquire(1L).isEmpty());
        assertTrue(limiter.tryAcquire(2L).isEmpty());
        assertTrue(limiter.tryAcquire(1L).isPresent());
    }

    @Test
    @DisplayName("la espera informada es al menos 1 segundo")
    void esperaMinima() {
        limiter.tryAcquire(1L);
        reloj.millis += 9_900;
        assertEquals(1, limiter.tryAcquire(1L).getAsLong());
    }
}
