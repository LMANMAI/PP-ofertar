package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the scraper's whole offer catalog, restricted to a set of chains.
 *
 * Separate from {@link OfferMatchClient}, which asks "what is on offer for
 * these specific products". Same failure policy: the offers feed is an
 * enrichment, so every failure degrades to an empty page rather than breaking
 * the screen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferFeedClient {

    private final RestClient restClient;
    private final OfferProperties offerProperties;

    public OfferFeedResponse listOffers(List<String> chainSlugs, int page, int pageSize, String province) {
        List<OfferFeedResponse.Offer> campaigns = fetchCampaigns(chainSlugs, province);
        CatalogPage catalog = fetchCatalog(chainSlugs, page, pageSize);

        List<OfferFeedResponse.Offer> items = new ArrayList<>();
        // Campaigns lead: they expire, so they are the ones worth acting on
        // first. They are few (dozens), so they only ride along on page 1.
        if (page <= 1) items.addAll(campaigns);
        items.addAll(catalog.items());

        long total = catalog.total() + (page <= 1 ? campaigns.size() : 0);
        return OfferFeedResponse.builder()
                .page(page)
                .pageSize(pageSize)
                .total(total)
                .totalPages(catalog.totalPages())
                .items(items)
                .build();
    }

    private record CatalogPage(List<OfferFeedResponse.Offer> items, long total, int totalPages) {}

    @SuppressWarnings("unchecked")
    private CatalogPage fetchCatalog(List<String> chainSlugs, int page, int pageSize) {
        try {
            var uri = UriComponentsBuilder.fromUriString(offerProperties.getServiceUrl() + "/api/offers")
                    .queryParam("page", page)
                    .queryParam("pageSize", pageSize)
                    .queryParam("onlyAvailable", true);
            if (chainSlugs != null && !chainSlugs.isEmpty()) {
                uri.queryParam("chains", String.join(",", chainSlugs));
            }

            var response = (Map<String, Object>) restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(Map.class);
            if (response == null) return new CatalogPage(List.of(), 0, 0);

            List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) response.getOrDefault("items", List.of());
            List<OfferFeedResponse.Offer> items = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                BigDecimal discount = toBigDecimal(m.get("discountPct"));
                items.add(OfferFeedResponse.Offer.builder()
                        .id("catalog:" + m.get("retailer") + ":" + m.get("skuId"))
                        .kind("catalog")
                        .retailerSlug((String) m.get("retailer"))
                        .retailerName((String) m.get("retailerName"))
                        .headline(discount != null && discount.intValue() >= 1
                                ? "-" + discount.setScale(0, java.math.RoundingMode.HALF_UP) + "%"
                                : "Precio destacado")
                        .productName((String) m.get("name"))
                        .brand((String) m.get("brand"))
                        .category((String) m.get("topLevelCategory"))
                        .price(toBigDecimal(m.get("sellingPrice")))
                        .listPrice(toBigDecimal(m.get("listPrice")))
                        .discountPct(discount)
                        .imageUrl((String) m.get("imageUrl"))
                        .url((String) m.get("url"))
                        .build());
            }
            return new CatalogPage(items, toLong(response.get("total")), toInt(response.get("totalPages")));
        } catch (Exception e) {
            log.warn("No se pudo obtener el catalogo de ofertas ({}): {}",
                    offerProperties.getServiceUrl(), e.getMessage());
            return new CatalogPage(List.of(), 0, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private List<OfferFeedResponse.Offer> fetchCampaigns(List<String> chainSlugs, String province) {
        try {
            var uri = UriComponentsBuilder
                    .fromUriString(offerProperties.getServiceUrl() + "/api/campaigns/promotions")
                    .queryParam("activeOnly", true);
            if (chainSlugs != null && !chainSlugs.isEmpty()) {
                uri.queryParam("chains", String.join(",", chainSlugs));
            }
            if (province != null && !province.isBlank()) {
                uri.queryParam("province", province);
            }

            var rows = (List<Map<String, Object>>) restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(List.class);
            if (rows == null) return List.of();

            List<OfferFeedResponse.Offer> items = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<Integer> percentages = toIntList(row.get("bestGuessPercentages"));
                String mechanic = (String) row.get("mechanic");
                items.add(OfferFeedResponse.Offer.builder()
                        .id("campaign:" + row.get("external_id"))
                        .kind("campaign")
                        .retailerSlug((String) row.get("retailer_slug"))
                        .retailerName((String) row.get("retailer_slug"))
                        .headline(campaignHeadline(percentages, mechanic))
                        // The categories the vision model read off the creative
                        // are the closest thing a campaign has to a category.
                        .category(firstOf(row.get("categories")))
                        .discountPct(percentages.isEmpty()
                                ? null
                                : BigDecimal.valueOf(percentages.get(0)))
                        .province((String) row.get("province"))
                        .activeTo((String) row.get("active_to"))
                        .legalText((String) row.get("legal_text"))
                        .percentagesUnverified(Boolean.TRUE.equals(row.get("percentagesUnverified")))
                        .build());
            }
            return items;
        } catch (Exception e) {
            log.warn("No se pudieron obtener las campanas ({}): {}",
                    offerProperties.getServiceUrl(), e.getMessage());
            return List.of();
        }
    }

    /** Same wording rules the app uses per product, so a promotion reads the
     * same in the feed as it does on a product card. A conditional mechanic
     * must never be phrased as a straight discount. */
    private String campaignHeadline(List<Integer> percentages, String mechanic) {
        String list = percentages.isEmpty()
                ? ""
                : percentages.stream().map(p -> p + "%").reduce((a, b) -> a + " / " + b).orElse("");
        if (mechanic == null) return list.isEmpty() ? "Promoción vigente" : "Promoción de hasta " + list;
        return switch (mechanic) {
            case "second_unit" -> list.isEmpty() ? "Descuento en la 2da unidad" : list + " en la 2da unidad";
            case "3x2" -> "3x2";
            case "2x1" -> "2x1";
            case "percentage_off" -> list.isEmpty() ? "Promoción vigente" : list + " de descuento";
            default -> list.isEmpty() ? "Promoción vigente" : "Promoción de hasta " + list;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Integer> toIntList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (Object v : list) {
            if (v instanceof Number n) out.add(n.intValue());
        }
        return out;
    }

    private String firstOf(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        return first == null ? null : first.toString();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
