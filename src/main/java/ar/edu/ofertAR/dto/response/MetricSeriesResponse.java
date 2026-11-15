package ar.edu.ofertAR.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Series de tiempo para el tablero (GET /telemetry/series). Los tiempos son epoch en segundos. */
public record MetricSeriesResponse(long from, long to, long step, List<Serie> series) {

    public record Serie(String name, String kind, Map<String, String> tags, List<Punto> points) {}

    /**
     * Un intervalo de {@code step} segundos que empieza en {@code t}.
     * timer: count eventos, sum y max en milisegundos. counter: count eventos.
     * gauge: sum / count es el promedio del intervalo y max el pico.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Punto(long t, double count, double sum, double max, Double p50, Double p95, Double p99) {}
}
