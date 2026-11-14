package ar.edu.ofertAR.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Una promo bancaria tal como la ve el usuario (§4 del SPEC de promos bancarias).
 * La arman el calendario semanal y la tira de Home.
 *
 * @param retailer     carrefour | jumbo | disco | vea | dia | coto
 * @param formats      formatos de tienda (hyper, market, express, maxi), o null si la fuente no lo dice
 * @param issuers      los emisores que nombra la promo; vacío si es para todos o si no se pudo inferir
 * @param allMethods   vale con cualquier medio de pago. Nunca es true si el emisor es desconocido
 * @param requires     lo que piden todos sus targets (red, tipo, paquete), o null si no piden nada
 * @param percentRange variantes por paquete de cuenta ("15–35% según tu paquete"), o null
 * @param conditions   condiciones de la persona (jubilado, plan_sueldo...)
 * @param regions      provincias canónicas ("CORDOBA", "RIO_NEGRO"), o null si es para todo el país
 * @param match        cómo le sirve al usuario: match, all, near o none
 * @param matchReason  "Con tu Banco Galicia Visa" (match), "Pide crédito", "Sólo en Córdoba",
 *                     "Para jubilados" (near); null en all y none
 * @param legalText    el legal de la fuente, o null si no lo trae
 * @param qualityFlags avisos del scraper sobre los datos: issuer_unknown, days_mismatch_legal...
 */
public record PromoView(
        long id,
        String retailer,
        String retailerName,
        String title,
        PromoDiscount discount,
        @Nullable PromoCap cap,
        List<String> channels,
        @Nullable List<String> formats,
        LocalDate validFrom,
        LocalDate validTo,
        List<PromoIssuer> issuers,
        boolean allMethods,
        @Nullable PromoRequires requires,
        @Nullable PercentRange percentRange,
        List<String> conditions,
        @Nullable List<String> regions,
        @Schema(allowableValues = {"match", "near", "all", "none"})
        String match,
        @Nullable String matchReason,
        @Nullable String legalText,
        List<String> qualityFlags
) {

    /**
     * El descuento. Sólo vienen los campos que aplican al {@code kind}; {@code text}
     * viene siempre, listo para mostrar.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromoDiscount(
            @Schema(allowableValues = {"percent", "installments", "amount", "other"})
            String kind,
            @Nullable BigDecimal percent,
            @Nullable Integer installments,
            @Nullable BigDecimal amount,
            String text
    ) {
    }

    /** Tope de reintegro. {@code period} es null si la fuente no dice por cuánto tiempo. */
    public record PromoCap(
            BigDecimal amount,
            @Nullable
            @Schema(allowableValues = {"purchase", "day", "week", "month"})
            String period
    ) {
    }

    public record PromoIssuer(String slug, String name) {
    }

    /** Lo que exige la promo; sólo vienen los campos que exige. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromoRequires(
            @Nullable String network,
            @Nullable String cardType,
            @Nullable @JsonProperty("package") String packageName
    ) {
    }

    public record PercentRange(BigDecimal min, BigDecimal max) {
    }
}
