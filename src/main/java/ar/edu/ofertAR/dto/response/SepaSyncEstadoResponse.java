package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.Builder;

/** Estado del sync en background, consultable mientras corre. */
@Builder
public record SepaSyncEstadoResponse(
        Estado estado,
        @Nullable String dia,
        @Nullable String fechaDataset,
        @Nullable String inicio,
        @Nullable String fin,
        long filasProcesadas,
        long productosGuardados,
        long productosInsertados,
        long duracionSegundos,
        @Nullable String error
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
