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
public class SavingsReportResponse {

    private Summary summary;
    private List<CategorySavings> byCategory;
    private List<StoreSavings> byStore;
    private List<TimelineSavings> timeline;
    private List<ProductSavings> topProducts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private BigDecimal totalSavings;
        private BigDecimal totalSpent;
        private int ticketCount;
        private BigDecimal averageSavings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySavings {
        private String category;
        private BigDecimal totalDiscounts;
        private long itemCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoreSavings {
        private String storeName;
        private BigDecimal totalDiscounts;
        private long ticketCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineSavings {
        private String period;
        private BigDecimal totalDiscounts;
        private int ticketCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSavings {
        private String description;
        private String barcode;
        private String category;
        private long purchaseCount;
        private BigDecimal totalDiscounts;
    }
}
