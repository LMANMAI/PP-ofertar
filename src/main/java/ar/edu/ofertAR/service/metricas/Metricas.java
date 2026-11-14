package ar.edu.ofertAR.service.metricas;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Atajos para registrar métricas de negocio desde cualquier clase, sin inyectar nada.
 *
 * <p>Usan el registro global de Micrometer, al que Spring Boot suma el de Actuator al
 * arrancar. Fuera de Spring (un test unitario que arma el servicio a mano) el registro
 * global está vacío y estas llamadas no hacen nada, así que instrumentar una clase no
 * obliga a tocar su constructor ni sus tests.
 *
 * <p>Los nombres van con prefijo {@code ofertar.}: {@link MetricasSnapshotService} guarda
 * en la base todo lo que empiece así. Los tags son pares clave, valor y tienen que ser de
 * pocos valores posibles (un estado, un host), nunca un id de usuario o de ticket: cada
 * combinación distinta es una serie más en memoria y en la base.
 */
public final class Metricas {

    private Metricas() {}

    /** Suma 1 al contador. */
    public static void contar(String nombre, String... tags) {
        try {
            Counter.builder(nombre).tags(tags).register(Metrics.globalRegistry).increment();
        } catch (RuntimeException e) {
            // una métrica nunca puede romper el flujo que la registra
        }
    }

    /** Registra una duración (en nanosegundos, como la da System.nanoTime()). */
    public static void tiempo(String nombre, long nanos, String... tags) {
        try {
            Timer.builder(nombre)
                    .tags(tags)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(Metrics.globalRegistry)
                    .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // ídem
        }
    }
}
