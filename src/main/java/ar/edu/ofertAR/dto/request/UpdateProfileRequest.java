package ar.edu.ofertAR.dto.request;

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

    @Size(max = 20)
    private String phone;
}
