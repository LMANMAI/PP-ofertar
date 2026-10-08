package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The user's shopping list. Every change to it answers with the whole list,
 * so the app never has to patch its copy by hand. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingListResponse {

    private String name;
    /** In the order they were added. */
    @Builder.Default
    private List<ShoppingListItemResponse> items = List.of();
}
