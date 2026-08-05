package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringProductResponse {

    private String description;
    private String barcode;
    private String category;
    private long purchaseCount;
    private BigDecimal totalDiscounts;
    /** Null when no current offer was found for this product's brand. */
    private BestOffer bestOffer;

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
}
