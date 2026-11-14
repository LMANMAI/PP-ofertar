package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddHabitualProductRequest {

    /** The barcode exactly as the product lookup returned it. */
    @NotBlank(message = "Falta el código de barras")
    @Pattern(regexp = "\\d{8,14}", message = "El código de barras tiene entre 8 y 14 números")
    private String ean;

    @NotBlank(message = "Falta el nombre del producto")
    @Size(max = 300, message = "El nombre es demasiado largo")
    private String description;

    @Size(max = 255, message = "La marca es demasiado larga")
    private String brand;
}
