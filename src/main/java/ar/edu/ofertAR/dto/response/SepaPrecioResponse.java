package ar.edu.ofertAR.dto.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record SepaPrecioResponse(
        String comercioId,
        String comercioCuit,
        String comercioRazonSocial,
        String bandera,
        String sucursalId,
        String productoId,
        String ean,
        String descripcion,
        String marca,
        String cantidadPresentacion,
        String unidadMedidaPresentacion,
        BigDecimal precioLista,
        BigDecimal precioReferencia,
        String unidadMedidaReferencia,
        BigDecimal precioPromo1,
        String leyendaPromo1,
        BigDecimal precioPromo2,
        String leyendaPromo2
) {}
