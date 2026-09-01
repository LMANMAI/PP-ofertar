package ar.edu.ofertAR.dto.response;

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
    private String storeName;
    private String ticketId;
    private BigDecimal total;
    private BigDecimal subtotal;
    private BigDecimal totalDiscounts;
    private TicketStatus status;
    /** False until the user has opened and confirmed the finished ticket.
     * Drives whether the app still allows correcting the OCR output. */
    private boolean reviewed;
    private LocalDateTime createdAt;
    private List<TicketItemResponse> items;
}
