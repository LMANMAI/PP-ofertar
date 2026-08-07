package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
    /** Null when no current offer was found for this product's brand. */
    private BestOffer bestOffer;
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
        private BigDecimal price;
        private BigDecimal listPrice;
        private BigDecimal discountPct;
        private String promoLabel;
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
