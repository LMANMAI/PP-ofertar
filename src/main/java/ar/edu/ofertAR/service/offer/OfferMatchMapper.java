package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;

import java.util.List;

/**
 * Turns what the offers service matched into the shapes the app reads. Shared
 * by the recurring products and the followed products, so a promotion carries
 * the same fields whichever list it shows up in.
 */
public final class OfferMatchMapper {

    private OfferMatchMapper() {}

    /** Null when the match has no catalog price. */
    public static RecurringProductResponse.BestOffer bestOffer(OfferMatch match) {
        if (!match.hasOffer()) return null;
        return RecurringProductResponse.BestOffer.builder()
                .retailerName(match.retailerName())
                .productName(match.productName())
                .imageUrl(match.imageUrl())
                .price(match.price())
                .listPrice(match.listPrice())
                .discountPct(match.discountPct())
                .promoLabel(match.promoLabel())
                .promoLabels(match.promoLabels())
                .requiredQuantity(match.requiredQuantity())
                .promoUnitPrice(match.promoUnitPrice())
                .build();
    }

    public static List<RecurringProductResponse.CampaignOffer> campaignOffers(OfferMatch match) {
        return match.campaignOffers().stream()
                .map(c -> RecurringProductResponse.CampaignOffer.builder()
                        .offerId(c.externalId() != null ? "campaign:" + c.externalId() : null)
                        .retailerName(c.retailerName())
                        .province(c.province())
                        .legalText(c.legalText())
                        .activeTo(c.activeTo())
                        .imageUrl(c.imageUrl())
                        .discountPercentages(c.discountPercentages())
                        .mechanic(c.mechanic())
                        .percentagesUnverified(c.percentagesUnverified())
                        .build())
                .toList();
    }

    /**
     * Independent of {@link #bestOffer}: these products have no unit discount
     * by definition, so gating them on {@code hasOffer()} would drop exactly
     * the promotions the user complained about never seeing.
     */
    public static List<RecurringProductResponse.PromoMechanic> promoMechanics(OfferMatch match) {
        return match.promoMechanics().stream()
                .map(m -> RecurringProductResponse.PromoMechanic.builder()
                        .retailerName(m.retailerName())
                        .productName(m.productName())
                        .imageUrl(m.imageUrl())
                        .unitPrice(m.unitPrice())
                        .listPrice(m.listPrice())
                        .promoLabels(m.promoLabels())
                        .requiredQuantity(m.requiredQuantity())
                        .promoUnitPrice(m.promoUnitPrice())
                        .build())
                .toList();
    }

    public static List<RecurringProductResponse.AlternativeOffer> alternativeOffers(OfferMatch match) {
        return match.alternativeOffers().stream()
                .map(a -> RecurringProductResponse.AlternativeOffer.builder()
                        .productName(a.productName())
                        .brand(a.brand())
                        .retailerName(a.retailerName())
                        .price(a.price())
                        .listPrice(a.listPrice())
                        .discountPct(a.discountPct())
                        .build())
                .toList();
    }
}
