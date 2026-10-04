package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private Long id;
    private String name;
    private String email;
    @Nullable
    private String profilePicture;
    @Nullable
    private String address;
<<<<<<< HEAD
    private String phone;
=======
    private boolean alternativeBrandsEnabled;
    @Nullable
    private String referralCode;
    private int points;
    private boolean offersPushEnabled;
>>>>>>> 6c49074 (Merge pull request #24 from LMANMAI/fase1-contrato-tipado)
    private LocalDateTime createdAt;
}
