package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /bank-promotions/week: siete días de promos bancarias desde {@code from}.
 *
 * @param mine              si se filtró a las promos que le sirven (sin las {@code none})
 * @param hasPaymentMethods si el usuario cargó al menos un medio de pago
 * @param notes             avisos para la UI; hoy sólo "rollover" (cambio de mes)
 */
public record BankPromotionsWeekResponse(
        LocalDate from,
        LocalDate to,
        boolean mine,
        boolean hasPaymentMethods,
        List<PromoDay> days,
        List<PromoNote> notes
) {

    /**
     * @param weekday     1 = lunes … 7 = domingo
     * @param bestPercent el mejor % del día entre las promos que le sirven (con
     *                    mine=true) o entre todas (mine=false); null si no hay
     *                    ninguna con porcentaje
     * @param promos      ordenadas: match y all, después near y al final none; en
     *                    cada grupo, % de mayor a menor y las cuotas y montos al final
     */
    public record PromoDay(
            LocalDate date,
            int weekday,
            @Nullable BigDecimal bestPercent,
            List<PromoView> promos
    ) {
    }

    /**
     * "rollover": desde {@code fromDate} vencieron las promos del mes y las del mes
     * que viene todavía no aparecen. La UI muestra "Las promos del mes que viene
     * suelen publicarse los primeros días".
     */
    public record PromoNote(
            @Schema(allowableValues = {"rollover"})
            String kind,
            LocalDate fromDate
    ) {
    }
}
