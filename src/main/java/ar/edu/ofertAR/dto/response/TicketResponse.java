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
    private BigDecimal total;
    private TicketStatus status;
    private LocalDateTime createdAt;
    private List<TicketItemResponse> items;
}
