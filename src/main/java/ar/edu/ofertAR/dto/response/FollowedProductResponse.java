package ar.edu.ofertAR.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A followed product with the promotions found for it right now. The offer
 * fields are the same ones a recurring product carries, so the app words them
 * with the same code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowedProductResponse {

    private String ean;
    private String description;
    @Nullable
    private String brand;
    @Nullable
    private String imageUrl;
    private LocalDateTime followedAt;
    /** Something below is populated. Always false when the list answers with
     * {@code promosChecked = false}. */
    private boolean inPromo;
    @Nullable
    private RecurringProductResponse.BestOffer bestOffer;
    @Builder.Default
    private List<RecurringProductResponse.CampaignOffer> campaignOffers = List.of();
    @Builder.Default
    private List<RecurringProductResponse.PromoMechanic> promoMechanics = List.of();
}
