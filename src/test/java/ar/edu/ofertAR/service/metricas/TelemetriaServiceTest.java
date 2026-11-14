package ar.edu.ofertAR.service.metricas;

import ar.edu.ofertAR.dto.request.TelemetryBatchRequest;
import ar.edu.ofertAR.dto.request.TelemetryBatchRequest.Event;
import ar.edu.ofertAR.repository.MetricasRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El endpoint de telemetría es público: lo que importa es que nada de lo que mande un
 * cliente termine tal cual como nombre de serie.
 */
@ExtendWith(MockitoExtension.class)
class TelemetriaServiceTest {

    @Mock private MetricasRepository repo;

    private SimpleMeterRegistry registry;
    private TelemetriaService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new TelemetriaService(registry, repo);
    }

    private static Event evento(String type, String name) {
        Event e = new Event();
        e.setType(type);
        e.setName(name);
        return e;
    }

    private static TelemetryBatchRequest lote(String installId, String platform, Event... eventos) {
        TelemetryBatchRequest lote = new TelemetryBatchRequest();
        lote.setInstallId(installId);
        lote.setPlatform(platform);
        lote.setAppVersion("1.0.0");
        lote.setEvents(List.of(eventos));
        return lote;
    }

    private double vistas(String pantalla) {
        var contador = registry.find("app.screen.views").tag("screen", pantalla).counter();
        return contador == null ? 0 : contador.count();
    }

    @Test
    @DisplayName("una vista de pantalla suma al contador de esa pantalla")
    void vistaDePantalla() {
        service.registrar(lote("install-0001", "android", evento("screen_view", "Home"), evento("screen_view", "Home")));
        assertEquals(2, vistas("Home"));
    }

    @Test
    @DisplayName("un nombre de pantalla que no cumple el patrón cae en 'otros'")
    void pantallaInvalida() {
        service.registrar(lote("install-0001", "android", evento("screen_view", "Home\n<script>|x=1")));
        assertEquals(1, vistas("otros"));
    }

    @Test
    @DisplayName("pasado el tope de pantallas distintas, las nuevas caen en 'otros'")
    void topeDePantallas() {
        List<Event> eventos = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            eventos.add(evento("screen_view", "Pantalla" + i));
        }
        service.registrar(lote("install-0001", "android", eventos.toArray(new Event[0])));
        assertEquals(1, vistas("Pantalla0"));
        assertEquals(1, vistas("Pantalla149"));
        assertEquals(0, vistas("Pantalla150"));
        assertEquals(10, vistas("otros"));
    }

    @Test
    @DisplayName("un request de la app queda como timer por endpoint y resultado")
    void requestDeLaApp() {
        Event e = evento("api_request", "GET /offers/{id}");
        e.setOutcome("http_5xx");
        e.setDurationMs(250.0);
        Event raro = evento("api_request", "GET /offers?token=abc");
        raro.setOutcome("cualquiera");
        raro.setDurationMs(10.0);
        service.registrar(lote("install-0001", "ios", e, raro));

        Timer timer = registry.find("app.api.requests")
                .tag("endpoint", "GET /offers/{id}").tag("outcome", "http_5xx").timer();
        assertEquals(1, timer.count());
        assertEquals(250.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.5);
        // Con query string no cumple el patrón: ni el endpoint ni el resultado se usan como llegaron.
        assertEquals(1, registry.find("app.api.requests")
                .tag("endpoint", "otros").tag("outcome", "otros").timer().count());
    }

    @Test
    @DisplayName("un error se cuenta y su detalle se guarda recortado")
    void error() {
        Event e = evento("error", "js");
        e.setMessage("x".repeat(500));
        e.setScreen("Home");
        e.setFatal(true);
        service.registrar(lote("install-0001", "android", e));

        assertEquals(1, registry.find("app.errors").tag("kind", "js").tag("platform", "android").counter().count());
        verify(repo).guardarError(anyLong(), eq("js"), argThat(m -> m.length() == 300), isNull(),
                eq("Home"), eq("android"), eq("1.0.0"), isNull(), eq(true));
    }

    @Test
    @DisplayName("las instalaciones activas se cuentan una vez cada una, por plataforma")
    void instalacionesActivas() {
        service.registrar(lote("install-0001", "android", evento("screen_view", "Home")));
        service.registrar(lote("install-0001", "android", evento("screen_view", "Offers")));
        service.registrar(lote("install-0002", "android", evento("screen_view", "Home")));
        service.registrar(lote("install-0003", "ios", evento("screen_view", "Home")));
        service.registrar(lote("corto", "ios", evento("screen_view", "Home")));

        assertEquals(2, registry.find("app.devices.active").tag("platform", "android").gauge().value());
        assertEquals(1, registry.find("app.devices.active").tag("platform", "ios").gauge().value());
    }

    @Test
    @DisplayName("un tipo de evento desconocido se ignora sin romper el lote")
    void tipoDesconocido() {
        service.registrar(lote("install-0001", "android", evento("algo_nuevo", "x"), evento("screen_view", "Home")));
        assertEquals(1, vistas("Home"));
        assertNull(registry.find("app.events").counter());
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("un error sin 'fatal' se guarda como no fatal")
    void errorNoFatal() {
        Event e = evento("error", "promise");
        e.setMessage("falló");
        service.registrar(lote("install-0001", "web", e));
        verify(repo).guardarError(anyLong(), eq("promise"), eq("falló"), isNull(), isNull(),
                eq("web"), eq("1.0.0"), isNull(), eq(false));
    }
}
