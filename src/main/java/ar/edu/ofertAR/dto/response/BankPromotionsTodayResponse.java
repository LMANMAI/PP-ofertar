package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /bank-promotions/today: para la tira de Home ("Hoy con tus tarjetas: hasta
 * 30% en Carrefour").
 *
 * @param bestPercent el mejor % del día, calculado sobre todas las promos del día
 *                    y no sólo sobre las 3 que vienen
 * @param promos      las 3 primeras, en el mismo orden que el calendario
 */
public record BankPromotionsTodayResponse(
        LocalDate date,
        @Nullable BigDecimal bestPercent,
        List<PromoView> promos
) {
}
