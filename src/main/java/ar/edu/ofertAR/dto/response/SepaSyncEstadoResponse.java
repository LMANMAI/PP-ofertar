package ar.edu.ofertAR.dto.response;

import lombok.Builder;

/** Estado del sync en background, consultable mientras corre. */
@Builder
public record SepaSyncEstadoResponse(
        Estado estado,
        String dia,
        String fechaDataset,
        String inicio,
        String fin,
        long filasProcesadas,
        long productosGuardados,
        long productosInsertados,
        long duracionSegundos,
        String error
) {
    public enum Estado {
        /** Nunca se corrió en esta instancia. */
        IDLE,
        /** Descargando / parseando / insertando. */
        EN_CURSO,
        OK,
        ERROR
    }
}
