package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPushTokenRequest {

    @NotBlank(message = "El token es obligatorio")
    private String token;

    @NotBlank(message = "La plataforma es obligatoria")
    private String platform;
}
