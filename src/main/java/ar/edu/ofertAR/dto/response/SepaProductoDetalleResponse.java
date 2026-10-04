package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta del escaneo de un código de barras.
 *
 * <p>Siempre responde 200, incluso cuando SEPA no conoce el EAN: en ese caso
 * {@code encontrado} viene en false y se completa lo que se pueda desde los
 * proveedores externos. Para quien acaba de apuntar la cámara a un producto,
 * ver el nombre y la foto con un "sin datos de precio" es mucho mejor que una
 * pantalla de error que no distingue entre "escaneaste mal" y "SEPA no lo
 * publica".
 */
@Builder
public record SepaProductoDetalleResponse(
        String ean,
        /** El EAN está en el snapshot de SEPA. */
        boolean encontrado,
        /** No hay ningún dato de precio para mostrar. */
        boolean sinPrecios,
        /** sepa | externo | ninguna */
        String fuenteDatos,
        @Nullable String descripcion,
        @Nullable String marca,
        @Nullable String imagenUrl,
        @Nullable BigDecimal precioMinimo,
        @Nullable BigDecimal precioPromedio,
        @Nullable BigDecimal precioMaximo,
        int cantidadOfertas,
        @Nullable LocalDate fechaDataset,
        /** Comercios donde está, del más barato al más caro. Vacío si no hay datos. */
        List<ComercioPrecioResponse> comercios
) {}
