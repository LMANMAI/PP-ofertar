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
    @Nullable
    private String ticketId;
    @Nullable
    private BigDecimal total;
    @Nullable
    private BigDecimal subtotal;
    @Nullable
    private BigDecimal totalDiscounts;
    private TicketStatus status;
    /** False until the user has opened and confirmed the finished ticket.
     * Drives whether the app still allows correcting the OCR output. */
    private boolean reviewed;
    private LocalDateTime createdAt;
    private List<TicketItemResponse> items;
}
