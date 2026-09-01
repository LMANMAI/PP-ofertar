package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Every offer currently running in the supermarkets the user follows —
 * independent of what they buy. The per-product view lives in
 * {@link RecurringProductResponse}; this one answers "what is on sale at my
 * supermarkets", which is a different question and needs the whole catalog
 * rather than the handful of products matched to a receipt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferFeedResponse {

    private int page;
    private int pageSize;
    private long total;
    private int totalPages;
    @Builder.Default
    private List<Offer> items = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Offer {
        private String id;
        /** "catalog" for a shelf price, "campaign" for a promotion with a
         * validity window and legal terms. */
        private String kind;
        private String retailerSlug;
        private String retailerName;
        /** Ready to display: "-25%", "50% en la 2da unidad". */
        private String headline;
        private String productName;
        private String brand;
        private String category;
        private BigDecimal price;
        private BigDecimal listPrice;
        private BigDecimal discountPct;
        private String imageUrl;
        private String url;
        /** Campaign only. */
        private String province;
        private String activeTo;
        private String legalText;
        private boolean percentagesUnverified;
        /** Campaign only: how the discount applies — "second_unit", "3x2",
         * "2x1", "percentage_off", or null when the scraper could not tell.
         * The app words the card from this rather than parsing the headline
         * back apart, so the two can never drift. */
        private String mechanic;
        /** Campaign only: every percentage the creative advertises. The
         * headline shows the ceiling of these; the app needs the set to know
         * whether the ceiling is one number or several. */
        private List<Integer> discountPercentages;
    }
}
