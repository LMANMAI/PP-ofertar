package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lote de eventos de telemetría que manda la app (POST /telemetry/events).
 * Es anónimo: identifica la instalación, no al usuario. Los textos no se validan
 * acá a propósito; TelemetriaService los recorta y descarta lo que no reconoce, así
 * un evento raro no hace rebotar el lote entero.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryBatchRequest {

    /** Id aleatorio de la instalación (se genera en el dispositivo). */
    private String installId;
    /** android | ios | web */
    private String platform;
    private String appVersion;
    private String osVersion;

    @NotNull(message = "Faltan los eventos")
    @Size(max = 200, message = "Demasiados eventos en un lote")
    private List<Event> events;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        /** session_start | app_start | screen_view | screen_time | api_request | error | event */
        private String type;
        /** Pantalla, endpoint ("GET /offers/{id}"), tipo de error o nombre del evento, según el type. */
        private String name;
        private Double durationMs;
        /** api_request: ok | http_4xx | http_5xx | timeout | network */
        private String outcome;
        /** error: mensaje y stack recortados; screen es la pantalla donde ocurrió. */
        private String message;
        private String stack;
        private String screen;
        private Boolean fatal;
    }
}
