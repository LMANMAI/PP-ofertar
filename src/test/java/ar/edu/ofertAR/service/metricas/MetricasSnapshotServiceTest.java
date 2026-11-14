package ar.edu.ofertAR.service.metricas;

import ar.edu.ofertAR.repository.MetricasRepository;
import ar.edu.ofertAR.repository.MetricasRepository.Muestra;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Qué filas salen del registro de Micrometer en cada minuto. */
@ExtendWith(MockitoExtension.class)
class MetricasSnapshotServiceTest {

    @Mock private MetricasRepository repo;
    @Mock private UserRepository userRepository;
    @Mock private TicketRepository ticketRepository;

    private SimpleMeterRegistry registry;
    private MetricasSnapshotService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new MetricasSnapshotService(registry, repo, userRepository, ticketRepository);
    }

    private Timer request(String uri, String status, String exception) {
        return Timer.builder("http.server.requests")
                .tags("method", "GET", "uri", uri, "status", status, "exception", exception, "outcome", "SUCCESS")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /** Con referencia fuerte: Micrometer guarda el objeto medido con una débil y el GC podría llevárselo. */
    private void gauge(String nombre, AtomicInteger valor, String... tags) {
        Gauge.builder(nombre, valor, AtomicInteger::get).tags(tags).strongReference(true).register(registry);
    }

    private static List<Muestra> de(List<Muestra> muestras, String name) {
        return muestras.stream().filter(m -> m.name().equals(name)).toList();
    }

    @Test
    @DisplayName("un timer guarda solo lo que pasó en el minuto, con los tags elegidos")
    void timerPorMinuto() {
        Timer timer = request("/offers/{id}", "200", "none");
        timer.record(100, TimeUnit.MILLISECONDS);
        timer.record(300, TimeUnit.MILLISECONDS);

        List<Muestra> primera = de(service.recolectar(60), "http.server.requests");
        assertEquals(1, primera.size());
        Muestra m = primera.get(0);
        assertEquals(60, m.ts());
        assertEquals("timer", m.kind());
        // "outcome" no se guarda y "exception=none" tampoco.
        assertEquals("method=GET|status=200|uri=/offers/{id}", m.tags());
        assertEquals(2, m.cnt());
        assertEquals(400, m.total(), 1);
        assertTrue(m.max() >= 200, "el máximo no puede ser menor que el promedio");
        assertNotNull(m.p95());

        // Sin actividad nueva no hay fila.
        assertTrue(de(service.recolectar(120), "http.server.requests").isEmpty());

        timer.record(50, TimeUnit.MILLISECONDS);
        Muestra tercera = de(service.recolectar(180), "http.server.requests").get(0);
        assertEquals(1, tercera.cnt());
        assertEquals(50, tercera.total(), 1);
    }

    @Test
    @DisplayName("un 500 conserva la excepción; /telemetry y /actuator no cuentan como tráfico")
    void filtrosDeHttp() {
        request("/tickets/scan", "500", "IllegalStateException").record(10, TimeUnit.MILLISECONDS);
        request("/telemetry/series", "200", "none").record(10, TimeUnit.MILLISECONDS);
        request("/actuator/health", "200", "none").record(10, TimeUnit.MILLISECONDS);

        List<Muestra> filas = de(service.recolectar(60), "http.server.requests");
        assertEquals(1, filas.size());
        assertEquals("exception=IllegalStateException|method=GET|status=500|uri=/tickets/scan", filas.get(0).tags());
    }

    @Test
    @DisplayName("los gauges de la misma serie se suman (pools del heap) y los negativos se saltean")
    void gauges() {
        AtomicInteger eden = new AtomicInteger(100);
        AtomicInteger old = new AtomicInteger(250);
        AtomicInteger sinTope = new AtomicInteger(-1);
        gauge("jvm.memory.used", eden, "area", "heap", "id", "eden");
        gauge("jvm.memory.used", old, "area", "heap", "id", "old");
        gauge("jvm.memory.max", sinTope, "area", "heap", "id", "eden");
        gauge("no.listada", eden);

        List<Muestra> filas = service.recolectar(60);
        assertEquals(1, filas.size());
        Muestra m = filas.get(0);
        assertEquals("jvm.memory.used", m.name());
        assertEquals("area=heap", m.tags());
        assertEquals("gauge", m.kind());
        assertEquals(1, m.cnt());
        assertEquals(350, m.total());
        assertEquals(350, m.max());
    }

    @Test
    @DisplayName("lo que empieza con ofertar. o app. se guarda con todos sus tags; solo logs warn/error")
    void negocioYLogs() {
        Counter.builder("ofertar.tickets.processed").tag("status", "processed").register(registry).increment(3);
        Counter.builder("app.screen.views").tag("screen", "Home").register(registry).increment();
        Counter.builder("logback.events").tag("level", "info").register(registry).increment(50);
        Counter.builder("logback.events").tag("level", "error").register(registry).increment(2);

        List<Muestra> filas = service.recolectar(60);
        assertEquals(3, filas.size());
        Muestra tickets = de(filas, "ofertar.tickets.processed").get(0);
        assertEquals("status=processed", tickets.tags());
        assertEquals("counter", tickets.kind());
        assertEquals(3, tickets.cnt());
        assertEquals("level=error", de(filas, "logback.events").get(0).tags());
        assertEquals(1, de(filas, "app.screen.views").size());
    }
}
