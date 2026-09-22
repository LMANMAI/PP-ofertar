package ar.edu.ofertAR.dto.response;

import ar.edu.ofertAR.model.SepaPrecioComercio;
import lombok.Builder;

import java.math.BigDecimal;

/** Precio de un producto en un comercio, para el comparador. */
@Builder
public record ComercioPrecioResponse(
        String comercioId,
        String bandera,
        String razonSocial,
        BigDecimal precioMinimo,
        BigDecimal precioMaximo,
        int cantidadSucursales
) {
    public static ComercioPrecioResponse from(SepaPrecioComercio p) {
        return ComercioPrecioResponse.builder()
                .comercioId(p.getComercioId())
                .bandera(p.getBandera())
                .razonSocial(p.getRazonSocial())
                .precioMinimo(p.getPrecioMinimo())
                .precioMaximo(p.getPrecioMaximo())
                .cantidadSucursales(p.getCantidadSucursales())
                .build();
    }

    /** Copia con la bandera reemplazada (para nombres de cadena curados). */
    public ComercioPrecioResponse withBandera(String bandera) {
        return ComercioPrecioResponse.builder()
                .comercioId(comercioId)
                .bandera(bandera)
                .razonSocial(razonSocial)
                .precioMinimo(precioMinimo)
                .precioMaximo(precioMaximo)
                .cantidadSucursales(cantidadSucursales)
                .build();
    }
}
