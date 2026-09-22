package ar.edu.ofertAR.dto.response;

import ar.edu.ofertAR.model.PointsReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsHistoryEntryResponse {

    private Long id;
    private PointsReason reason;
    private String description;
    private int points;
    private LocalDateTime createdAt;
}
