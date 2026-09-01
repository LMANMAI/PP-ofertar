package ar.edu.ofertAR.dto.response;

import ar.edu.ofertAR.model.SepaProducto;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Producto del snapshot, enriquecido con la URL de su imagen.
 * {@code imagenUrl} llega en null si todavía no se resolvió: el front debe
 * mostrar un placeholder, no esperarla.
 */
@Builder
public record SepaProductoResponse(
        Long id,
        String ean,
        String descripcion,
        String marca,
        BigDecimal precioMinimo,
        BigDecimal precioPromedio,
        BigDecimal precioMaximo,
        int cantidadOfertas,
        LocalDate fechaDataset,
        String imagenUrl
) {
    public static SepaProductoResponse from(SepaProducto p, String imagenUrl) {
        return SepaProductoResponse.builder()
                .id(p.getId())
                .ean(p.getEan())
                .descripcion(p.getDescripcion())
                .marca(p.getMarca())
                .precioMinimo(p.getPrecioMinimo())
                .precioPromedio(p.getPrecioPromedio())
                .precioMaximo(p.getPrecioMaximo())
                .cantidadOfertas(p.getCantidadOfertas())
                .fechaDataset(p.getFechaDataset())
                .imagenUrl(imagenUrl)
                .build();
    }
}
