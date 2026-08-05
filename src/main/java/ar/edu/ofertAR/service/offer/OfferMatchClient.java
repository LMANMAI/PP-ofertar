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

    public List<OfferMatch> matchProducts(List<ProductQuery> items) {
        if (items.isEmpty()) return List.of();

        try {
            @SuppressWarnings("unchecked")
            var response = (Map<String, Object>) restClient.post()
                    .uri(offerProperties.getServiceUrl() + "/api/offers/match")
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

            if (best == null) {
                matches.add(OfferMatch.none());
                continue;
            }

            matches.add(new OfferMatch(
                    matchedBrand,
                    (String) best.get("retailerName"),
                    toBigDecimal(best.get("sellingPrice")),
                    toBigDecimal(best.get("listPrice")),
                    toBigDecimal(best.get("discountPct")),
                    firstPromoLabel(best.get("promoLabels"))
            ));
        }
        return matches;
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

    public record OfferMatch(
            String matchedBrand,
            String retailerName,
            BigDecimal price,
            BigDecimal listPrice,
            BigDecimal discountPct,
            String promoLabel
    ) {
        public static OfferMatch none() {
            return new OfferMatch(null, null, null, null, null, null);
        }

        public boolean hasOffer() {
            return retailerName != null && price != null;
        }
    }
}
