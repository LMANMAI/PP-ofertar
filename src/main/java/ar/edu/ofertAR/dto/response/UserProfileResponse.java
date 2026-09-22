package ar.edu.ofertAR.dto.response;

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
    private String profilePicture;
    private String address;
    private String phone;
<<<<<<< HEAD
=======
    private boolean alternativeBrandsEnabled;
    private String referralCode;
    private int points;
>>>>>>> 248bfcb (Merge pull request #9 from LMANMAI/feature/referral-points)
    private LocalDateTime createdAt;
}
