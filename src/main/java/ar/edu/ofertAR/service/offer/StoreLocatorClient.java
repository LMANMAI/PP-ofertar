package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Reads supermarket branch data from the scraper service. Like
 * {@link OfferMatchClient}, failures degrade to an empty result instead of
 * throwing: an unreachable scraper should leave the map empty, not break the
 * screen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreLocatorClient {

    private final RestClient restClient;
    private final OfferProperties offerProperties;

    public List<Map<String, Object>> findNearbyStores(
            double lat, double lng, int radiusKm, List<String> chainSlugs
    ) {
        try {
            var uri = UriComponentsBuilder.fromUriString(offerProperties.getServiceUrl() + "/api/stores")
                    .queryParam("lat", lat)
                    .queryParam("lng", lng)
                    .queryParam("radiusKm", radiusKm);
            if (chainSlugs != null && !chainSlugs.isEmpty()) {
                uri.queryParam("chains", String.join(",", chainSlugs));
            }

            @SuppressWarnings("unchecked")
            var response = (Map<String, Object>) restClient.get()
                    .uri(uri.build().toUri())
                    .retrieve()
                    .body(Map.class);

            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) (response == null
                    ? List.of()
                    : response.getOrDefault("items", List.of()));
            return items;
        } catch (Exception e) {
            log.warn("No se pudieron obtener sucursales del servicio de scraping: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> listChains() {
        try {
            @SuppressWarnings("unchecked")
            var chains = (List<Map<String, Object>>) restClient.get()
                    .uri(offerProperties.getServiceUrl() + "/api/stores/chains")
                    .retrieve()
                    .body(List.class);
            return chains == null ? List.of() : chains;
        } catch (Exception e) {
            log.warn("No se pudo obtener el listado de cadenas: {}", e.getMessage());
            return List.of();
        }
    }
}
