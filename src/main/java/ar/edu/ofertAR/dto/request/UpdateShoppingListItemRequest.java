package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Both fields are optional: a null one is left as it is. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateShoppingListItemRequest {

    @Min(value = 1, message = "La cantidad mínima es 1")
    @Max(value = 99, message = "La cantidad máxima es 99")
    private Integer quantity;

    private Boolean checked;
}
