package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowedProductsResponse {

    /**
     * Whether the offers service answered. When it did not, every item comes
     * with {@code inPromo = false} and that means "we could not check", not
     * "it has no promotion" — the app has to say so instead of showing a list
     * of products without offers.
     */
    private boolean promosChecked;
    /** Most recently followed first. */
    @Builder.Default
    private List<FollowedProductResponse> items = List.of();
}
