package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El servicio de ofertas caído y "ninguna oferta" tienen que poder distinguirse:
 * el job de productos seguidos guarda si cada uno estaba en promo, y leer una
 * caída como "terminó la promo" la anunciaría de nuevo al día siguiente. Las
 * pantallas, en cambio, siguen recibiendo una línea vacía por producto como
 * antes de separar {@code tryMatchProducts}.
 */
class OfferMatchClientOutageTest {

    private static final String SCRAPER_URL = "http://scraper.test";

    private static final String NO_OFFER = """
            {"results":[{
              "query":{"description":"YERBA","barcode":""},
              "matchedBrand":null,"method":"none",
              "bestCatalogOffer":null,"campaignOffers":[],"alternativeBrandOffers":[]
            }]}
            """;

    private OfferMatchClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OfferProperties properties = new OfferProperties();
        properties.setServiceUrl(SCRAPER_URL);
        client = new OfferMatchClient(builder.build(), properties);
    }

    @Test
    @DisplayName("con el servicio caído, tryMatchProducts no responde nada en vez de 'sin ofertas'")
    void outage_isEmpty() {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match")).andRespond(withServerError());

        Optional<List<OfferMatch>> answer = client.tryMatchProducts(
                List.of(new ProductQuery("YERBA", ""), new ProductQuery("ARROZ", "")), false, List.of());

        assertTrue(answer.isEmpty());
    }

    @Test
    @DisplayName("con el servicio caído, matchProducts sigue devolviendo una línea vacía por producto")
    void outage_matchProductsKeepsTheOldFallback() {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match")).andRespond(withServerError());

        List<OfferMatch> matches = client.matchProducts(
                List.of(new ProductQuery("YERBA", ""), new ProductQuery("ARROZ", "")), false, List.of());

        assertEquals(2, matches.size());
        assertTrue(matches.stream().noneMatch(OfferMatch::hasOffer));
    }

    @Test
    @DisplayName("con el servicio arriba y sin ofertas, la respuesta existe y viene vacía")
    void noOffer_isPresent() {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match"))
                .andRespond(withSuccess(NO_OFFER, MediaType.APPLICATION_JSON));

        Optional<List<OfferMatch>> answer = client.tryMatchProducts(
                List.of(new ProductQuery("YERBA", "")), false, List.of());

        assertTrue(answer.isPresent());
        assertEquals(1, answer.get().size());
        assertFalse(answer.get().get(0).hasOffer());
    }

    @Test
    @DisplayName("sin productos no se llama al servicio")
    void noItems_doesNotCall() {
        assertEquals(Optional.of(List.of()), client.tryMatchProducts(List.of(), false, List.of()));
        server.verify();
    }
}
