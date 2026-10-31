package ar.edu.ofertAR.service.imagen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleTest {

    @Test
    @DisplayName("la primera llamada pasa sin esperar")
    void primeraSinEspera() throws Exception {
        Throttle t = new Throttle(2);
        long ini = System.nanoTime();
        t.esperarTurno();
        assertTrue((System.nanoTime() - ini) / 1_000_000 < 100);
    }

    @Test
    @DisplayName("con el turno lejos y un tope corto, devuelve false sin bloquear ni reservar")
    void topeCorto() throws Exception {
        Throttle t = new Throttle(1); // 1 req/s
        assertTrue(t.esperarTurno(50));   // libre
        long ini = System.nanoTime();
        assertFalse(t.esperarTurno(50));  // el siguiente turno esta ~1s adelante
        assertTrue((System.nanoTime() - ini) / 1_000_000 < 50);
        // no reservo: pasado el intervalo vuelve a haber lugar
        Thread.sleep(1050);
        assertTrue(t.esperarTurno(50));
    }

    @Test
    @DisplayName("varios hilos se espacian al ritmo configurado, sin dormir con el monitor tomado")
    void hilosSeEspacian() throws Exception {
        Throttle t = new Throttle(20); // 50 ms
        ExecutorService ex = Executors.newFixedThreadPool(5);
        List<Future<Long>> fs = new ArrayList<>();
        long ini = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            Callable<Long> c = () -> { t.esperarTurno(); return (System.nanoTime() - ini) / 1_000_000; };
            fs.add(ex.submit(c));
        }
        long max = 0;
        for (Future<Long> f : fs) max = Math.max(max, f.get());
        ex.shutdown();
        assertTrue(max >= 150, "el quinto turno debia caer ~200ms despues: " + max);
        assertTrue(max < 600, "no deberia acumular espera de mas: " + max);
    }
}
