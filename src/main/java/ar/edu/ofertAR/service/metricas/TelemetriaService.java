package ar.edu.ofertAR.service.metricas;

import ar.edu.ofertAR.dto.request.TelemetryBatchRequest;
import ar.edu.ofertAR.repository.MetricasRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Recibe la telemetría de la app y la vuelca en métricas {@code app.*} de Micrometer;
 * de ahí {@link MetricasSnapshotService} las lleva a la base junto con las del backend.
 *
 * <p>El endpoint es público (la app reporta también antes del login), así que nada de lo que
 * llega se usa tal cual: los textos se validan contra un patrón, y cada dimensión tiene un
 * tope de valores distintos (pasado el tope caen en "otros"). Sin eso, cualquiera podría
 * inventar pantallas o endpoints hasta llenar la memoria y la tabla de métricas.
 */
@Slf4j
@Service
public class TelemetriaService {

    private static final Pattern INSTALL_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern NOMBRE = Pattern.compile("[A-Za-z0-9_.-]{1,60}");
    private static final Pattern ENDPOINT = Pattern.compile("(GET|POST|PUT|DELETE|PATCH) /[A-Za-z0-9_./{}-]{0,100}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9_.+-]{1,20}");
    private static final Set<String> PLATAFORMAS = Set.of("android", "ios", "web");
    private static final Set<String> RESULTADOS = Set.of("ok", "http_4xx", "http_5xx", "timeout", "network");
    private static final String OTROS = "otros";

    /** Una instalación cuenta como activa si reportó algo en este lapso. */
    private static final long ACTIVO_MS = 5 * 60_000L;
    private static final int ACTIVOS_MAX = 50_000;
    private static final int ERRORES_POR_MINUTO = 60;

    private static final double START_MAX_MS = 120_000;
    private static final double API_MAX_MS = 300_000;
    private static final double PANTALLA_MAX_MS = 3_600_000;

    private record Activo(String plataforma, long visto) {}

    private final MeterRegistry registry;
    private final MetricasRepository repo;

    @Value("${metricas.enabled:true}")
    private boolean enabled = true;

    private final Map<String, Activo> activos = new ConcurrentHashMap<>();
    /** Valores ya vistos de cada dimensión, para aplicar el tope. */
    private final Map<String, Set<String>> vistos = new ConcurrentHashMap<>();
    private final AtomicLong ventanaErrores = new AtomicLong();
    private final AtomicLong erroresEnVentana = new AtomicLong();

    public TelemetriaService(MeterRegistry registry, MetricasRepository repo) {
        this.registry = registry;
        this.repo = repo;
        for (String plataforma : PLATAFORMAS) {
            Gauge.builder("app.devices.active", this, s -> s.activos(plataforma))
                    .tag("platform", plataforma)
                    .description("Instalaciones que reportaron en los últimos 5 minutos")
                    .register(registry);
        }
    }

    public void registrar(TelemetryBatchRequest lote) {
        if (!enabled || lote == null || lote.getEvents() == null) {
            return;
        }
        String plataforma = plataforma(lote.getPlatform());
        String version = acotar("version", limpiar(lote.getAppVersion(), VERSION), 30);
        long ahora = System.currentTimeMillis();

        String installId = lote.getInstallId();
        if (installId != null && INSTALL_ID.matcher(installId).matches()
                && (activos.size() < ACTIVOS_MAX || activos.containsKey(installId))) {
            activos.put(installId, new Activo(plataforma, ahora));
        }

        for (TelemetryBatchRequest.Event evento : lote.getEvents()) {
            if (evento == null || evento.getType() == null) {
                continue;
            }
            try {
                registrar(evento, plataforma, version, lote.getOsVersion(), ahora);
            } catch (RuntimeException e) {
                log.debug("Evento de telemetría descartado: {}", e.getMessage());
            }
        }
    }

    private void registrar(TelemetryBatchRequest.Event evento, String plataforma, String version,
                           String osVersion, long ahora) {
        switch (evento.getType()) {
            case "session_start" -> contar("app.sessions", "platform", plataforma, "version", version);
            case "app_start" -> tiempo("app.start", evento.getDurationMs(), START_MAX_MS, "platform", plataforma);
            case "screen_view" -> contar("app.screen.views", "screen", pantalla(evento.getName()));
            case "screen_time" -> tiempo("app.screen.time", evento.getDurationMs(), PANTALLA_MAX_MS,
                    "screen", pantalla(evento.getName()));
            case "api_request" -> {
                String endpoint = acotar("endpoint", limpiar(evento.getName(), ENDPOINT), 200);
                String resultado = evento.getOutcome() != null && RESULTADOS.contains(evento.getOutcome())
                        ? evento.getOutcome() : OTROS;
                tiempo("app.api.requests", evento.getDurationMs(), API_MAX_MS,
                        "endpoint", endpoint, "outcome", resultado);
            }
            case "error" -> {
                String tipo = acotar("error", limpiar(evento.getName(), NOMBRE), 20);
                contar("app.errors", "kind", tipo, "platform", plataforma);
                guardarError(evento, tipo, plataforma, version, osVersion, ahora);
            }
            case "event" -> contar("app.events", "name", acotar("event", limpiar(evento.getName(), NOMBRE), 100));
            default -> { /* tipo desconocido: una app más nueva que el backend */ }
        }
    }

    /** Instalaciones de esa plataforma vistas en los últimos 5 minutos (lo lee el gauge). */
    private double activos(String plataforma) {
        long desde = System.currentTimeMillis() - ACTIVO_MS;
        activos.values().removeIf(a -> a.visto() < desde);
        return activos.values().stream().filter(a -> a.plataforma().equals(plataforma)).count();
    }

    /** El detalle del error va a su tabla, con un tope global por minuto. */
    private void guardarError(TelemetryBatchRequest.Event evento, String tipo, String plataforma,
                              String version, String osVersion, long ahora) {
        long minuto = ahora / 60_000L;
        if (ventanaErrores.getAndSet(minuto) != minuto) {
            erroresEnVentana.set(0);
        }
        if (erroresEnVentana.incrementAndGet() > ERRORES_POR_MINUTO) {
            return;
        }
        String mensaje = recortar(evento.getMessage(), 300);
        try {
            repo.guardarError(ahora / 1000L, tipo, mensaje == null ? "(sin mensaje)" : mensaje,
                    recortar(evento.getStack(), 2000),
                    evento.getScreen() == null ? null : pantalla(evento.getScreen()),
                    plataforma,
                    OTROS.equals(version) ? null : version,
                    limpiarONull(osVersion, VERSION),
                    Boolean.TRUE.equals(evento.getFatal()));
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar un error de la app: {}", e.getMessage());
        }
    }

    private void contar(String nombre, String... tags) {
        Counter.builder(nombre).tags(tags).register(registry).increment();
    }

    private void tiempo(String nombre, Double ms, double maximo, String... tags) {
        if (ms == null || ms.isNaN() || ms < 0) {
            return;
        }
        Timer.builder(nombre)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record((long) (Math.min(ms, maximo) * 1_000_000d), TimeUnit.NANOSECONDS);
    }

    private String pantalla(String nombre) {
        return acotar("screen", limpiar(nombre, NOMBRE), 150);
    }

    private static String plataforma(String valor) {
        String p = valor == null ? "" : valor.toLowerCase(Locale.ROOT);
        return PLATAFORMAS.contains(p) ? p : OTROS;
    }

    /** El valor si cumple el patrón; si no, "otros". */
    private static String limpiar(String valor, Pattern patron) {
        return valor != null && patron.matcher(valor).matches() ? valor : OTROS;
    }

    private static String limpiarONull(String valor, Pattern patron) {
        return valor != null && patron.matcher(valor).matches() ? valor : null;
    }

    /** Deja pasar hasta {@code tope} valores distintos por dimensión; el resto es "otros". */
    private String acotar(String dimension, String valor, int tope) {
        Set<String> conocidos = vistos.computeIfAbsent(dimension, d -> ConcurrentHashMap.newKeySet());
        if (conocidos.contains(valor)) {
            return valor;
        }
        if (conocidos.size() >= tope) {
            return OTROS;
        }
        conocidos.add(valor);
        return valor;
    }

    private static String recortar(String texto, int largo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        // Sin caracteres de control (salvo saltos de línea en el stack).
        String limpio = texto.replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ").strip();
        return limpio.length() > largo ? limpio.substring(0, largo) : limpio;
    }
}
