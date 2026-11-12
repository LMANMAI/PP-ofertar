package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Los horarios se guardan en {@code sepa_sucursal.horarios} como el mismo JSON que
 * recibe la app: una columna en vez de siete (o de una tabla de franjas), porque
 * nunca se consultan por día ni por hora, solo se leen junto con la sucursal.
 */
public final class HorariosJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HorariosJson() {
    }

    public static String escribir(HorariosResponse horarios) {
        if (horarios == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(horarios);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el horario", e);
        }
    }

    /** Un JSON ilegible es un horario desconocido, no un error para quien consulta. */
    public static HorariosResponse leer(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, HorariosResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
