package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import ar.edu.ofertAR.model.TicketStatus;
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
public class TicketResponse {

    private Long id;
    @Nullable
    private String storeName;
<<<<<<< HEAD
    private BigDecimal total;
=======
    @Nullable
    private String ticketId;
    @Nullable
    private BigDecimal total;
    @Nullable
    private BigDecimal subtotal;
    @Nullable
    private BigDecimal totalDiscounts;
>>>>>>> 6c49074 (Merge pull request #24 from LMANMAI/fase1-contrato-tipado)
    private TicketStatus status;
    private LocalDateTime createdAt;
    private List<TicketItemResponse> items;
}
