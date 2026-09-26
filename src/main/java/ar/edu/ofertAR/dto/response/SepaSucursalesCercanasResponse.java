package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import java.time.LocalDate;
import java.util.List;

/**
 * El precio de un producto en las sucursales dentro de un radio: una por cadena,
 * la más barata, de menor a mayor precio.
 *
 * @param fechaDataset día al que corresponden los precios (SEPA los publica con
 *                     días de atraso); null si el producto no está en el snapshot
 */
public record SepaSucursalesCercanasResponse(
        String ean,
        double radioKm,
        @Nullable LocalDate fechaDataset,
        List<SucursalPrecioResponse> sucursales
) {
}
