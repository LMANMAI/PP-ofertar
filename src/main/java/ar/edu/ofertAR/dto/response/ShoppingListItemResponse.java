package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingListItemResponse {

    private Long id;
    private String ean;
    private String description;
    @Nullable
    private String brand;
    /** Resolved by EAN when the list is read; null until a provider has one. */
    @Nullable
    private String imageUrl;
    private int quantity;
    /** Already in the cart. */
    private boolean checked;
    private LocalDateTime addedAt;
}
