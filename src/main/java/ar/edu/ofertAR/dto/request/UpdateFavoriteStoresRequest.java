package ar.edu.ofertAR.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateFavoriteStoresRequest {

    /** Chain slugs the user shops at. Replaces the whole selection; an empty
     * list means "no filter", i.e. show offers from every chain. */
    private List<String> chainSlugs;

    /** Null leaves the current radius unchanged. */
    @Min(value = 1, message = "El radio mínimo es 1 km")
    @Max(value = 20, message = "El radio máximo es 20 km")
    private Integer radiusKm;
}
