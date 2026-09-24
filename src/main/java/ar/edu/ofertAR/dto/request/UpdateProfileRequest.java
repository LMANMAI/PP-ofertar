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

    @Size(max = 2_097_152, message = "La imagen es demasiado grande (máx 2MB en base64)")
    private String profilePicture;

    @Size(max = 300)
    private String address;

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
}
