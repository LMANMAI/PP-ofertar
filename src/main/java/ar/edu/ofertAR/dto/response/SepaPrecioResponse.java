package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record SepaPrecioResponse(
        @Nullable String comercioId,
        @Nullable String comercioCuit,
        @Nullable String comercioRazonSocial,
        @Nullable String bandera,
        @Nullable String banderaId,
        @Nullable String sucursalId,
        @Nullable String productoId,
        String ean,
        @Nullable String descripcion,
        @Nullable String marca,
        @Nullable String cantidadPresentacion,
        @Nullable String unidadMedidaPresentacion,
        @Nullable BigDecimal precioLista,
        @Nullable BigDecimal precioReferencia,
        @Nullable String unidadMedidaReferencia,
        @Nullable BigDecimal precioPromo1,
        @Nullable String leyendaPromo1,
        @Nullable BigDecimal precioPromo2,
        @Nullable String leyendaPromo2
) {
    /** Copia con la bandera reemplazada (para nombres de cadena curados). */
    public SepaPrecioResponse withBandera(String bandera) {
        return new SepaPrecioResponse(
                comercioId, comercioCuit, comercioRazonSocial, bandera,
                banderaId, sucursalId, productoId, ean, descripcion, marca,
                cantidadPresentacion, unidadMedidaPresentacion, precioLista,
                precioReferencia, unidadMedidaReferencia, precioPromo1,
                leyendaPromo1, precioPromo2, leyendaPromo2);
    }
}
