package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketItemResponse {

    private Long id;
    private String description;
    @Nullable
    private String rawDescription;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    @Nullable
    private BigDecimal originalPrice;
    @Nullable
    private BigDecimal subtotal;
    @Nullable
    private String barcode;
    @Nullable
    private String category;
    @Nullable
    private BigDecimal discountAmount;
    @Nullable
    private String discountDescription;
}
