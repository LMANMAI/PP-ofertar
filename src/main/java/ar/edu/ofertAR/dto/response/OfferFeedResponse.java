package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;
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
        @Schema(allowableValues = {"catalog", "campaign"})
        private String kind;
        @Nullable
        private String retailerSlug;
        @Nullable
        private String retailerName;
        /** Ready to display: "-25%", "50% en la 2da unidad". */
        private String headline;
        @Nullable
        private String productName;
        @Nullable
        private String brand;
        @Nullable
        private String category;
        @Nullable
        private BigDecimal price;
        @Nullable
        private BigDecimal listPrice;
        @Nullable
        private BigDecimal discountPct;
        @Nullable
        private String imageUrl;
        @Nullable
        private String url;
        /** Campaign only. */
        @Nullable
        private String province;
        @Nullable
        private String activeTo;
        @Nullable
        private String legalText;
        private boolean percentagesUnverified;
        /** Campaign only: how the discount applies — "second_unit", "3x2",
         * "2x1", "percentage_off", or null when the scraper could not tell.
         * The app words the card from this rather than parsing the headline
         * back apart, so the two can never drift. */
        @Nullable
        @Schema(allowableValues = {"second_unit", "3x2", "2x1", "percentage_off"})
        private String mechanic;
        /** Campaign only: every percentage the creative advertises. The
         * headline shows the ceiling of these; the app needs the set to know
         * whether the ceiling is one number or several. */
        @Nullable
        private List<Integer> discountPercentages;
    }
}
