package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.Builder;

@Builder
public record SepaSyncResponse(
        @Nullable String dia,
        @Nullable String fechaDataset,
        long filasProcesadas,
        long productosGuardados,
        long duracionSegundos
) {}
