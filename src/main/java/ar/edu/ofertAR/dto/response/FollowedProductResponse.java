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
    /** Fecha del ticket procesado más reciente del usuario con un ítem cuyo
     * código de barras es este producto (comparado por EAN normalizado). Null
     * si nunca lo compró, o si lo compró pero el ticket no trajo el código: los
     * ítems sin código de barras no se pueden reconocer. */
    @Nullable
    private LocalDateTime lastPurchasedAt;
    /** Hay una promo de ESTE producto: una oferta de catálogo encontrada por
     * EAN o con un nombre que no es de otro producto, una mecánica (3x2, 2da
     * unidad) de este producto, o una campaña de su marca. Las listas de abajo
     * pueden traer además promos de productos parecidos, que la app marca como
     * "Similar" / "Promo de otro producto" y que por sí solas no ponen esto en
     * true. Always false when the list answers with
     * {@code promosChecked = false}. */
    private boolean inPromo;
    @Nullable
    private RecurringProductResponse.BestOffer bestOffer;
    @Builder.Default
    private List<RecurringProductResponse.CampaignOffer> campaignOffers = List.of();
    @Builder.Default
    private List<RecurringProductResponse.PromoMechanic> promoMechanics = List.of();
}
