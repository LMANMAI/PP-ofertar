package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.TelemetryBatchRequest;
import ar.edu.ofertAR.dto.response.MetricSeriesResponse;
import ar.edu.ofertAR.repository.MetricasRepository;
import ar.edu.ofertAR.service.metricas.TelemetriaService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Métricas para el tablero de observabilidad (PP-ofertar-dashboard) y entrada de la
 * telemetría de la app.
 *
 * <p>Son endpoints operativos, no parte del contrato que consume la app para sus pantallas:
 * por eso van con {@link Hidden} y no aparecen en openapi.json.
 *
 * <p>Hoy son públicos (SecurityConfig los deja pasar sin sesión). Lo que devuelven es
 * agregado y anónimo, pero si el backend queda expuesto a internet conviene restringir los
 * GET a rol ADMIN desde SecurityConfig.
 */
@RestController
@RequestMapping("/telemetry")
@RequiredArgsConstructor
@Hidden
public class TelemetryController {

    private static final Pattern NOMBRE_METRICA = Pattern.compile("[a-z0-9_.]{1,100}");
    private static final int METRICAS_MAX = 40;
    private static final long RANGO_MAX = 31 * 86_400L;
    /** Tope de intervalos por serie: más que esto no entra en un gráfico. */
    private static final long PUNTOS_MAX = 720;

    private final TelemetriaService telemetria;
    private final MetricasRepository repo;

    @Value("${metricas.enabled:true}")
    private boolean enabled;

    @Value("${metricas.retencion-dias:14}")
    private int retencionDias;

    public record Estado(long serverTime, long uptimeSeconds, Long lastSampleTs, int retentionDays, boolean enabled) {}

    /** La app manda sus eventos en lotes. Siempre 202: la telemetría no se reintenta. */
    @PostMapping("/events")
    public ResponseEntity<Void> events(@Valid @RequestBody TelemetryBatchRequest lote) {
        telemetria.registrar(lote);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/status")
    public Estado status() {
        return new Estado(
                System.currentTimeMillis() / 1000L,
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000L,
                repo.ultimoTs(),
                retencionDias,
                enabled);
    }

    /** Métricas con datos en las últimas 24 horas. */
    @GetMapping("/metrics")
    public List<MetricasRepository.Metrica> metrics() {
        return repo.catalogo(System.currentTimeMillis() / 1000L - 86_400L);
    }

    /**
     * Series de una o más métricas. {@code from}/{@code to} son epoch en segundos (por defecto,
     * la última hora) y {@code step} el tamaño del intervalo en segundos (múltiplo de 60; si se
     * omite o da demasiados puntos, se elige uno que entre en un gráfico).
     */
    @GetMapping("/series")
    public MetricSeriesResponse series(
            @RequestParam List<String> name,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Long step) {
        if (name.isEmpty() || name.size() > METRICAS_MAX) {
            throw new IllegalArgumentException("Indicá entre 1 y " + METRICAS_MAX + " métricas en 'name'");
        }
        for (String n : name) {
            if (!NOMBRE_METRICA.matcher(n).matches()) {
                throw new IllegalArgumentException("Nombre de métrica inválido: " + n);
            }
        }
        long hasta = to != null ? to : System.currentTimeMillis() / 1000L;
        long desde = from != null ? from : hasta - 3600;
        if (desde >= hasta) {
            throw new IllegalArgumentException("'from' tiene que ser anterior a 'to'");
        }
        if (hasta - desde > RANGO_MAX) {
            throw new IllegalArgumentException("El rango máximo es de 31 días");
        }
        long paso = step != null ? step : 60;
        paso = Math.max(60, (paso + 59) / 60 * 60);
        long minimo = ((hasta - desde) / PUNTOS_MAX + 59) / 60 * 60;
        paso = Math.max(paso, minimo);
        // Alineado al paso, para que los intervalos no cambien de borde entre un refresco y otro.
        desde = desde / paso * paso;

        Map<String, MetricSeriesResponse.Serie> series = new LinkedHashMap<>();
        for (MetricasRepository.Punto p : repo.series(name, desde, hasta, paso)) {
            MetricSeriesResponse.Serie serie = series.computeIfAbsent(p.name() + '\n' + p.tags(),
                    k -> new MetricSeriesResponse.Serie(p.name(), p.kind(), tags(p.tags()), new ArrayList<>()));
            serie.points().add(new MetricSeriesResponse.Punto(
                    p.t(), p.cnt(), p.total(), p.max(), p.p50(), p.p95(), p.p99()));
        }
        return new MetricSeriesResponse(desde, hasta, paso, new ArrayList<>(series.values()));
    }

    /** Últimos errores reportados por la app. */
    @GetMapping("/errors")
    public List<MetricasRepository.ErrorApp> errors(
            @RequestParam(required = false) Long from,
            @RequestParam(defaultValue = "50") int limit) {
        long desde = from != null ? from : System.currentTimeMillis() / 1000L - 86_400L;
        return repo.errores(desde, Math.max(1, Math.min(limit, 200)));
    }

    /** "clave=valor|clave=valor" como lo guarda MetricasSnapshotService. */
    private static Map<String, String> tags(String crudo) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (crudo == null || crudo.isEmpty()) {
            return tags;
        }
        for (String par : crudo.split("\\|")) {
            int igual = par.indexOf('=');
            if (igual > 0) {
                tags.put(par.substring(0, igual), par.substring(igual + 1));
            }
        }
        return tags;
    }
}
