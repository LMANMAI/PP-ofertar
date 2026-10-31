package ar.edu.ofertAR.dto.response;

/**
 * Un tramo continuo en que la sucursal atiende, en hora de Argentina.
 *
 * @param desde "HH:mm", de "00:00" a "23:59"
 * @param hasta "HH:mm", puede ser "24:00". Si es menor o igual que {@code desde},
 *              el tramo cruza la medianoche: "18:00" a "02:00" cierra a las 2 del
 *              día siguiente
 */
public record FranjaHorariaResponse(String desde, String hasta) {
}
