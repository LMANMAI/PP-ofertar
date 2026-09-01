package ar.edu.ofertAR.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record SepaPreciosPageResponse(
        String dia,
        String fecha,
        String recursoUrl,
        int page,
        int size,
        long totalElementos,
        long totalPaginas,
        List<SepaPrecioResponse> data
) {}
