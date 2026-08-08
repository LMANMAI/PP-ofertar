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
public class TicketItemResponse {

    private Long id;
    private String description;
    private String rawDescription;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal originalPrice;
    private BigDecimal subtotal;
    private String barcode;
    private String category;
    private BigDecimal discountAmount;
    private String discountDescription;
}
