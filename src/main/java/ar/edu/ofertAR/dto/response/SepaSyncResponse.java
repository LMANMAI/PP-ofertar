package ar.edu.ofertAR.dto.response;

import lombok.Builder;

@Builder
public record SepaSyncResponse(
        String dia,
        String fechaDataset,
        long filasProcesadas,
        long productosGuardados,
        long duracionSegundos
) {}
