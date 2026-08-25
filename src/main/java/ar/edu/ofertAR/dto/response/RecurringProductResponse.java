package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringProductResponse {

    private String description;
    private String barcode;
    private String category;
    /** Times this product appeared as a line item across all the user's tickets. */
    private long purchaseCount;
    /** Distinct tickets (i.e. separate shopping trips) that included it — the
     * better signal for "buys this regularly" than raw line-item count. */
    private long ticketCount;
    /** Whether the product appears in the reference ticket: the one passed as
     * {@code ticketId}, or the user's most recent one when none was given.
     * Drives both the shopping-list checklist and the "forgot to buy?" prompt. */
    private boolean inReferenceTicket;
    private BigDecimal totalDiscounts;
    /** Unit price the user actually paid the last time they bought it, so the
     * app can put a current offer against their own history instead of only
     * against the retailer's list price. Null if the ticket never recorded one.
     *
     * Careful with products sold by weight: this is per kilo, while a catalog
     * offer is per package, so the two are not comparable for those lines. */
    private BigDecimal lastPaidPrice;
    /** When that purchase happened, so the app can say "hace 3 semanas". */
    private LocalDateTime lastPaidAt;
    /** Null when no current offer was found for this product's brand. */
    private BestOffer bestOffer;
    /** Regional campaign promotions matching this product's brand. These are
     * the offers that carry a validity window; {@link BestOffer} is just the
     * current shelf price. Empty when none match. */
    @Builder.Default
    private List<CampaignOffer> campaignOffers = List.of();
    /** Offers on the same kind of product from other brands. Empty unless the
     * user enabled "marcas alternativas" in their profile. */
    @Builder.Default
    private List<AlternativeOffer> alternativeOffers = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BestOffer {
        private String retailerName;
        /** The catalog product this price belongs to. It shares the brand and
         * the kind of product with what the user bought, but not necessarily
         * the size or variety, so the app shows it rather than implying the
         * price is for the exact item on their receipt. */
        private String productName;
        private BigDecimal price;
        private BigDecimal listPrice;
        private BigDecimal discountPct;
        private String promoLabel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CampaignOffer {
        private String retailerName;
        private String province;
        private String legalText;
        /** ISO-8601 string as published by the retailer; the app formats it. */
        private String activeTo;
        private String imageUrl;
        /** Percentages the OCR read off the creative, e.g. [30, 40]. Best guess:
         * these come from reading a promo image, not from a structured field. */
        @Builder.Default
        private List<Integer> discountPercentages = List.of();
        /** second_unit, 3x2, 2x1, percentage_off, or null when unknown. Tells
         * the app whether the percentage is a straight discount or a
         * conditional one, which changes how it must be worded. */
        private String mechanic;
        /** True when the sources disagree on the percentage; the app hedges
         * the number instead of stating it. */
        private boolean percentagesConflict;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlternativeOffer {
        private String productName;
        private String brand;
        private String retailerName;
        private BigDecimal price;
        private BigDecimal listPrice;
        private BigDecimal discountPct;
    }
}
