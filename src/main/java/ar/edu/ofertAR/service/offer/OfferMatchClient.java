package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Talks to the external offers-scraper microservice (Node/Express, scrapes
 * Carrefour and — over time — other supermarkets). Unlike {@code OcrClient},
 * a failure here must never break ticket/product listing: offer matching is
 * an enrichment on top of data we already have, so every failure mode
 * degrades to "no offer found" instead of throwing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferMatchClient {

    private final RestClient restClient;
    private final OfferProperties offerProperties;

    public List<OfferMatch> matchProducts(
            List<ProductQuery> items, boolean includeAlternativeBrands, List<String> chainSlugs
    ) {
        if (items.isEmpty()) return List.of();

        StringBuilder query = new StringBuilder();
        if (includeAlternativeBrands) query.append("alternativeBrands=true");
        if (chainSlugs != null && !chainSlugs.isEmpty()) {
            if (query.length() > 0) query.append("&");
            query.append("chains=").append(String.join(",", chainSlugs));
        }

        try {
            @SuppressWarnings("unchecked")
            var response = (Map<String, Object>) restClient.post()
                    .uri(offerProperties.getServiceUrl() + "/api/offers/match"
                            + (query.length() > 0 ? "?" + query : ""))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("items", items.stream().map(i -> Map.of(
                            "description", i.description() == null ? "" : i.description(),
                            "barcode", i.barcode() == null ? "" : i.barcode()
                    )).toList()))
                    .retrieve()
                    .body(Map.class);

            return mapResults(response);
        } catch (Exception e) {
            log.warn("No se pudo obtener ofertas del servicio de scraping ({}): {}",
                    offerProperties.getServiceUrl(), e.getMessage());
            return items.stream().map(i -> OfferMatch.none()).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<OfferMatch> mapResults(Map<String, Object> response) {
        List<Map<String, Object>> results = response == null
                ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("results", List.of());

        List<OfferMatch> matches = new ArrayList<>();
        for (Map<String, Object> result : results) {
            String matchedBrand = (String) result.get("matchedBrand");
            Map<String, Object> best = (Map<String, Object>) result.get("bestCatalogOffer");
            List<AlternativeOffer> alternatives = mapAlternatives(result.get("alternativeBrandOffers"));
            List<CampaignOffer> campaigns = mapCampaigns(result.get("campaignOffers"));
            // Read off the result, not off the offer, and mapped before the
            // early return on purpose: these are exactly the products that have
            // no unit discount, so the line with no `bestCatalogOffer` at all is
            // the one most likely to carry a 3x2.
            List<PromoMechanic> mechanics = mapPromoMechanics(result.get("promoMechanics"));

            if (best == null) {
                matches.add(new OfferMatch(matchedBrand, null, null, null, null, null, null,
                        null, List.of(), null, null, mechanics, alternatives, campaigns));
                continue;
            }

            List<String> promoLabels = promoLabels(best.get("promoLabels"));
            matches.add(new OfferMatch(
                    matchedBrand,
                    (String) best.get("retailerName"),
                    (String) best.get("productName"),
                    (String) best.get("imageUrl"),
                    toBigDecimal(best.get("sellingPrice")),
                    toBigDecimal(best.get("listPrice")),
                    toBigDecimal(best.get("discountPct")),
                    firstPromoLabel(promoLabels),
                    promoLabels,
                    toRequiredQuantity(best.get("requiredQuantity")),
                    toBigDecimal(best.get("promoUnitPrice")),
                    mechanics,
                    alternatives,
                    campaigns
            ));
        }
        return matches;
    }

    @SuppressWarnings("unchecked")
    private List<AlternativeOffer> mapAlternatives(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<AlternativeOffer> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) entry;
            out.add(new AlternativeOffer(
                    (String) m.get("productName"),
                    (String) m.get("brand"),
                    (String) m.get("retailerName"),
                    toBigDecimal(m.get("sellingPrice")),
                    toBigDecimal(m.get("listPrice")),
                    toBigDecimal(m.get("discountPct"))
            ));
        }
        return out;
    }

    /**
     * The regional campaign promotions the scraper matched to this brand. These
     * are the only offers that carry a validity window and legal terms — the
     * catalog offer is just today's shelf price — so dropping them, as this
     * client used to, left the app with no way to tell the user until when a
     * promotion runs.
     */
    @SuppressWarnings("unchecked")
    private List<CampaignOffer> mapCampaigns(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<CampaignOffer> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) entry;
            out.add(new CampaignOffer(
                    (String) m.get("externalId"),
                    (String) m.get("retailerName"),
                    (String) m.get("province"),
                    (String) m.get("legalText"),
                    (String) m.get("activeTo"),
                    (String) m.get("imageUrl"),
                    toIntList(m.get("bestGuessPercentages")),
                    (String) m.get("mechanic"),
                    Boolean.TRUE.equals(m.get("percentagesUnverified"))
            ));
        }
        return out;
    }

    /**
     * The promotions that are not price cuts: "3x2", "2x1", "80% en la 2da
     * unidad" — the mechanics the user asked to see and that never arrived.
     *
     * <p>They travel beside the best offer rather than inside it because the
     * scraper answers two different questions with two different queries. The
     * best offer answers "where is it cheapest", which requires an actual unit
     * discount; a product on 3x2 has none — its first unit costs the usual
     * shelf price — so it can never appear there, and putting it there would
     * file a full-price product under "Mejor en X". Measured on COTO: of 40
     * products taken from the "3X2" and "2X1" aisles, 40 carry the mechanic
     * and 0 carry a unit discount.
     *
     * <p>Empty for an older scraper that sends no {@code promoMechanics} key,
     * for the same reason the scraper itself sends {@code []} on the no-brand
     * branch: no consumer should have to tell "there are none" from "we did
     * not look".
     */
    @SuppressWarnings("unchecked")
    private List<PromoMechanic> mapPromoMechanics(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<PromoMechanic> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) entry;
            out.add(new PromoMechanic(
                    (String) m.get("retailerName"),
                    (String) m.get("productName"),
                    (String) m.get("imageUrl"),
                    // sellingPrice, and deliberately not called `price`: on
                    // these rows the promotion has not been applied to it.
                    toBigDecimal(m.get("sellingPrice")),
                    toBigDecimal(m.get("listPrice")),
                    promoLabels(m.get("promoLabels")),
                    toRequiredQuantity(m.get("requiredQuantity")),
                    toBigDecimal(m.get("promoUnitPrice"))
            ));
        }
        return List.copyOf(out);
    }

    private List<Integer> toIntList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (Object v : list) {
            if (v instanceof Number n) out.add(n.intValue());
        }
        return out;
    }

    /**
     * Every promo label the scraper published for the SKU, in its order.
     *
     * <p>Reading only the first one is what hid the promotion mechanics from
     * "Productos que comprás seguido": measured against the live Carrefour
     * catalog, the array is usually led by a bank promotion ("Tarjeta Carrefour
     * 15%", "PROMO-Mi CRF ... Doble Precio") and the "3x2" or "80% en la 2da
     * unidad" — the only part the user can act on at the shelf — sits behind
     * it. So the whole array travels and the client decides what to show.
     *
     * <p>Never null: absent, empty and "not a list at all" are all the same
     * thing to a consumer — this SKU advertises no mechanic.
     */
    private List<String> promoLabels(Object promoLabels) {
        if (!(promoLabels instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object label : list) {
            if (label == null) continue;
            String text = label.toString().trim();
            if (!text.isEmpty()) out.add(text);
        }
        return List.copyOf(out);
    }

    /** The legacy single label: literally the first of {@link #promoLabels}.
     * Kept because the app reads {@code promoLabel} as a string today, and a
     * backend that stopped sending it would blank the line it already shows. */
    private String firstPromoLabel(List<String> promoLabels) {
        return promoLabels.isEmpty() ? null : promoLabels.get(0);
    }

    /**
     * Units that must be bought for the promotion to apply, or null when the
     * scraper did not say.
     *
     * <p>Null is deliberate and is not the same as 1. The scraper deploys on
     * its own schedule, so a response without the field comes from a build that
     * never computed it — and the difference between "one unit already costs
     * less" and "the second one is 70% off" is exactly what the user is being
     * told at the shelf. Defaulting the absent case to 1 would assert an
     * unconditional discount we have no evidence for; defaulting it to 2 would
     * invent a condition outright. Unknown stays unknown, and the app shows
     * what it showed before the field existed.
     *
     * <p>A value of 1 <em>is</em> an answer — "no condition" — and travels as 1.
     * Zero and negatives are impossible (you cannot buy nothing to earn a
     * promotion) so they are read as a broken payload, not as a quantity.
     */
    private Integer toRequiredQuantity(Object value) {
        if (!(value instanceof Number n)) return null;
        int quantity = n.intValue();
        return quantity >= 1 ? quantity : null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    public record ProductQuery(String description, String barcode) {}

    /** Same kind of product, different brand — only populated when the user
     * opted in via their profile preference. */
    public record AlternativeOffer(
            String productName,
            String brand,
            String retailerName,
            BigDecimal price,
            BigDecimal listPrice,
            BigDecimal discountPct
    ) {}

    /**
     * A catalog product whose promotion is a condition on quantity rather than
     * a lower price: 3x2, 2x1, "70% en la 2da unidad".
     *
     * <p>A separate type from {@link OfferMatch} instead of reusing it, and the
     * price component is called {@code unitPrice} instead of {@code price},
     * because the meaning is not the same and the name is the only thing
     * stopping the mix-up. In an {@code OfferMatch}, {@code price} is what the
     * user pays now — it is the offer. Here the promotion has <em>not</em> been
     * applied: {@code unitPrice} is the ordinary shelf price of one unit, and
     * it only becomes a deal once {@code requiredQuantity} of them are in the
     * cart. Rendering one as the other puts a full price under a discount
     * headline, which is the exact bug the scraper split these queries to
     * avoid.
     *
     * <p>No {@code discountPct} either: these rows have none (that is what
     * makes them mechanics), and an always-null percentage on the record is an
     * invitation to draw "0% OFF" on a card.
     */
    public record PromoMechanic(
            String retailerName,
            String productName,
            String imageUrl,
            /** What ONE unit costs today, with the promotion not yet applied. */
            BigDecimal unitPrice,
            BigDecimal listPrice,
            /** The labels that state the mechanic; never null, rarely empty —
             * a row is only here because a condition on quantity exists. */
            List<String> promoLabels,
            /** Units needed for the promotion to apply; always > 1 here in
             * practice, null only if the scraper omitted it. */
            Integer requiredQuantity,
            /** Effective price per unit once the condition is met, for the
             * chains that publish it (COTO). Null everywhere else, and there
             * the label is what must be shown — never a computed guess. */
            BigDecimal promoUnitPrice
    ) {
        public PromoMechanic {
            promoLabels = promoLabels == null ? List.of() : List.copyOf(promoLabels);
        }
    }

    /** A regional campaign promotion (the /promociones creatives), which unlike
     * a catalog price has a validity window and legal terms attached. */
    public record CampaignOffer(
            /** The promotion's own id. The offers feed keys the same promotion
             * as "campaign:<externalId>", which is what lets the app open this
             * match in the offer detail instead of retelling it in a card. */
            String externalId,
            String retailerName,
            String province,
            String legalText,
            /** ISO-8601 string, exactly as the scraper stores it. */
            String activeTo,
            String imageUrl,
            List<Integer> discountPercentages,
            /** How the discount applies: second_unit, 3x2, 2x1, percentage_off
             * or null. A "70% en la 2da unidad" is not comparable to a straight
             * price, and without this the app could only show the bare number. */
            String mechanic,
            /** The percentage came from OCR alone, with no campaign metadata to
             * confirm it — the only case that actually warrants a hedge. When
             * the two sources disagree the metadata is right, so that is not a
             * reason to doubt the number. */
            boolean percentagesUnverified
    ) {}

    public record OfferMatch(
            String matchedBrand,
            String retailerName,
            /** Name of the catalog SKU the price belongs to. Shown to the user
             * so a same-brand-but-different-product match is visible instead of
             * silently passing as the price of what they actually bought. */
            String productName,
            /** The retailer's photo of that same SKU, or null when the catalog
             * published none. Sits next to {@code productName} because the two
             * describe the same thing and must never come from different rows:
             * a photo of one product over the name of another is worse than no
             * photo at all. Every consumer needs a fallback regardless. */
            String imageUrl,
            BigDecimal price,
            BigDecimal listPrice,
            BigDecimal discountPct,
            /** First element of {@code promoLabels}, kept for the app's
             * existing {@code promoLabel} string. Prefer the list. */
            String promoLabel,
            /** Every label the retailer published for this SKU, in order and
             * never null. This is where "3x2" and "80% en la 2da unidad" live
             * when a bank promotion happens to be listed first. */
            List<String> promoLabels,
            /** Units required for the promotion to apply; 1 = none, null =
             * the scraper did not report it. See
             * {@link OfferMatchClient#toRequiredQuantity(Object)}. */
            Integer requiredQuantity,
            /** Effective unit price once {@code requiredQuantity} units are
             * bought, when the chain publishes it (COTO does, VTEX teasers do
             * not). Null means "we don't know what it ends up costing" — the
             * label has to be shown instead of a made-up number, never that
             * there is no promotion. */
            BigDecimal promoUnitPrice,
            /** Promotions on this brand that are conditions on quantity, not
             * price cuts. Independent of the fields above: a product can have
             * a 3x2 and no unit discount at all — in fact that is the common
             * case — so this list is populated even when there is no offer. */
            List<PromoMechanic> promoMechanics,
            List<AlternativeOffer> alternativeOffers,
            List<CampaignOffer> campaignOffers
    ) {
        /** Every list here is read straight onto a screen, so they are
         * normalised once instead of at every use: absent and empty mean the
         * same thing to the app, and a null would only ever show up as a crash. */
        public OfferMatch {
            promoLabels = promoLabels == null ? List.of() : List.copyOf(promoLabels);
            promoMechanics = promoMechanics == null ? List.of() : List.copyOf(promoMechanics);
            alternativeOffers = alternativeOffers == null ? List.of() : List.copyOf(alternativeOffers);
            campaignOffers = campaignOffers == null ? List.of() : List.copyOf(campaignOffers);
        }

        public static OfferMatch none() {
            return new OfferMatch(null, null, null, null, null, null, null,
                    null, List.of(), null, null, List.of(), List.of(), List.of());
        }

        public boolean hasOffer() {
            return retailerName != null && price != null;
        }
    }
}
