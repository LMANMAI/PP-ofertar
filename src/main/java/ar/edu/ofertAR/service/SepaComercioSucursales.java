package ar.edu.ofertAR.service;

import java.util.List;

/**
 * Lo que trae un comercio del dataset antes de sus precios: quién es y dónde
 * están sus sucursales. Se entrega una vez por comercio, antes de sus filas de
 * productos, para que quien procesa las filas pueda ubicarlas.
 */
public record SepaComercioSucursales(
        String comercioId,
        String razonSocial,
        List<SepaSucursalData> sucursales
) {
}
