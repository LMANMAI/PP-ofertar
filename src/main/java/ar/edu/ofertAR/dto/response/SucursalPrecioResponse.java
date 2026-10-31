package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import java.math.BigDecimal;

/**
 * La sucursal más barata de una cadena dentro del radio pedido, con lo necesario
 * para llegar: dirección y coordenadas.
 *
 * @param bandera nombre comercial de esa sucursal ("Jumbo"), que puede diferir del
 *                del comercio: Cencosud agrupa Jumbo, Disco y Vea
 * @param precio  precio de lista de esa sucursal, no el mínimo de la cadena
 * @param horarios horario de atención de esa sucursal; null si SEPA no lo informa
 */
public record SucursalPrecioResponse(
        @Nullable String comercioId,
        @Nullable String banderaId,
        @Nullable String bandera,
        Long sucursalId,
        @Nullable String nombre,
        @Nullable String tipo,
        @Nullable String direccion,
        @Nullable String localidad,
        @Nullable String provincia,
        double latitud,
        double longitud,
        double distanciaKm,
        BigDecimal precio,
        HorariosResponse horarios
) {
}
