package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedeemRequest {

    @NotBlank(message = "El id de la recompensa es obligatorio")
    private String rewardId;

    /** Reference only — the real cost always comes from the server-side
     * catalog, never from this field. */
    private int points;
}
