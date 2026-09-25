package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    private String name;

    private String profilePicture;

    @Size(max = 300)
    private String address;

<<<<<<< HEAD
    @Size(max = 20)
    private String phone;
=======
    /** Null leaves the current email untouched. A different email also needs
     * {@link #currentPassword}: it is the login identity and where password
     * reset codes are sent, so a stolen session alone must not be able to move it. */
    @Email(message = "Formato de email inválido")
    @Size(max = 150, message = "El email es demasiado largo")
    private String email;

    /** Only read when {@link #email} changes. */
    @Size(max = 100)
    private String currentPassword;

    /** Null leaves the current preference untouched (all fields here are
     * partial-update style), so the boxed type is deliberate. */
    private Boolean alternativeBrandsEnabled;

    /** Gates offer and re-engagement push notifications. Ticket/referral
     * pushes are transactional and not covered by this flag. */
    private Boolean offersPushEnabled;
>>>>>>> 1a707ae (Merge pull request #15 from LMANMAI/feature/change-email)
}
