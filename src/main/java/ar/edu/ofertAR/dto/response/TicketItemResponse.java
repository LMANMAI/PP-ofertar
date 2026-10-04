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
<<<<<<< HEAD
    private Integer quantity;
    private BigDecimal unitPrice;
=======
    @Nullable
    private String rawDescription;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    @Nullable
    private BigDecimal originalPrice;
    @Nullable
>>>>>>> 6c49074 (Merge pull request #24 from LMANMAI/fase1-contrato-tipado)
    private BigDecimal subtotal;
    @Nullable
    private String barcode;
<<<<<<< HEAD
=======
    @Nullable
    private String category;
    @Nullable
    private BigDecimal discountAmount;
    @Nullable
    private String discountDescription;
>>>>>>> 6c49074 (Merge pull request #24 from LMANMAI/fase1-contrato-tipado)
}
