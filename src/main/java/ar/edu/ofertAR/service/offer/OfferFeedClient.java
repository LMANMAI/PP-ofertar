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
        boolean firstPage = page <= 1;
        List<OfferFeedResponse.Offer> campaigns =
                firstPage ? fetchCampaigns(chainSlugs, province) : List.of();

        // Campaigns lead — they expire, so they are what is worth acting on
        // first — but they must not swallow the page. They used to be appended
        // whole: 55 active promotions turned a pageSize=8 request into 63
        // items, and the home carousel rendered every one of them. Half the
        // page at most, so the catalog is always represented too.
        int campaignSlots = campaigns.isEmpty() ? 0 : Math.max(1, pageSize / 2);
        List<OfferFeedResponse.Offer> shownCampaigns =
                campaigns.subList(0, Math.min(campaignSlots, campaigns.size()));

        int catalogSlots = Math.max(1, pageSize - shownCampaigns.size());
        CatalogPage catalog = fetchCatalog(chainSlugs, page, catalogSlots);

        List<OfferFeedResponse.Offer> items = new ArrayList<>(shownCampaigns);
        items.addAll(catalog.items());

        return OfferFeedResponse.builder()
                .page(page)
                .pageSize(pageSize)
                .total(catalog.total() + campaigns.size())
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
                        // Falls back to the slug only for a scraper that
                        // predates retailer_name; the app shows this verbatim.
                        .retailerName(row.get("retailer_name") instanceof String name
                                ? name
                                : (String) row.get("retailer_slug"))
                        .headline(campaignHeadline(percentages, mechanic))
                        // The app words the card itself from these two, with the
                        // same rules as campaignHeadline; the headline above is
                        // the fallback for app versions that predate them.
                        .mechanic(mechanic)
                        .discountPercentages(percentages)
                        // The categories the vision model read off the creative
                        // are the closest thing a campaign has to a category.
                        .category(firstOf(row.get("categories")))
                        // The largest of them, not the first: several
                        // percentages on one creative are several offers
                        // sharing a banner, and the headline reads "hasta", so
                        // the number beside it has to be the same ceiling.
                        // Taking get(0) made a "25_35" campaign say "hasta 35%"
                        // and carry a discountPct of 25.
                        .discountPct(percentages.stream()
                                .max(Integer::compareTo)
                                .map(BigDecimal::valueOf)
                                .orElse(null))
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

    /**
     * Same wording rules the app uses per product ({@code describePromo} in
     * offersApi.ts), so a promotion reads the same in the feed as it does on a
     * product card. Two rules carry the weight here:
     *
     * <ul>
     *   <li>A conditional mechanic is never phrased as a straight discount —
     *       "70% en la 2da unidad" is worth half of what it looks like.</li>
     *   <li>Several percentages are collapsed to their maximum, prefixed with
     *       "Hasta". They come off a single creative and nothing in the
     *       pipeline records which condition each one belongs to, so joining
     *       them with " / " ("50% / 12% de descuento") claimed a relationship
     *       the data never had.</li>
     * </ul>
     */
    private String campaignHeadline(List<Integer> percentages, String mechanic) {
        // Distinct: the same number twice is still one advertised discount, and
        // the filename hint ("50_50...") does not deduplicate the way OCR does.
        List<Integer> valid = percentages.stream().filter(p -> p > 0 && p <= 100).distinct().toList();
        Integer top = valid.stream().max(Integer::compareTo).orElse(null);
        String pct = top == null ? null : top + "%";
        String prefix = valid.size() > 1 ? "Hasta " : "";

        if ("3x2".equals(mechanic)) return "3x2";
        if ("2x1".equals(mechanic)) return "2x1";
        if ("second_unit".equals(mechanic)) {
            return pct == null ? "Descuento en la 2da unidad" : prefix + pct + " en la 2da unidad";
        }
        if ("percentage_off".equals(mechanic) && pct != null) {
            return prefix + pct + " de descuento";
        }
        // Unknown or absent mechanic, or a percentage_off with nothing readable.
        return pct == null ? "Promoción vigente" : "Promoción de hasta " + pct;
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
