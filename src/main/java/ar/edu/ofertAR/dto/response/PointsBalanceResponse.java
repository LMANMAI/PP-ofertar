package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsBalanceResponse {

    private int balance;
    @Nullable
    private String referralCode;
}
