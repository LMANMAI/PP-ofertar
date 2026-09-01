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

            if (best == null) {
                matches.add(new OfferMatch(matchedBrand, null, null, null, null, null, null, alternatives, campaigns));
                continue;
            }

            matches.add(new OfferMatch(
                    matchedBrand,
                    (String) best.get("retailerName"),
                    (String) best.get("productName"),
                    toBigDecimal(best.get("sellingPrice")),
                    toBigDecimal(best.get("listPrice")),
                    toBigDecimal(best.get("discountPct")),
                    firstPromoLabel(best.get("promoLabels")),
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

    private List<Integer> toIntList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (Object v : list) {
            if (v instanceof Number n) out.add(n.intValue());
        }
        return out;
    }

    private String firstPromoLabel(Object promoLabels) {
        if (!(promoLabels instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        return first == null ? null : first.toString();
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
            BigDecimal price,
            BigDecimal listPrice,
            BigDecimal discountPct,
            String promoLabel,
            List<AlternativeOffer> alternativeOffers,
            List<CampaignOffer> campaignOffers
    ) {
        public static OfferMatch none() {
            return new OfferMatch(null, null, null, null, null, null, null, List.of(), List.of());
        }

        public boolean hasOffer() {
            return retailerName != null && price != null;
        }
    }
}
