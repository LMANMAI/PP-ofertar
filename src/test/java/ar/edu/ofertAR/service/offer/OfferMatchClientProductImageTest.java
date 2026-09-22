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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The "Productos que comprás seguido" cards showed a generic cart glyph even
 * though the scraper's catalog has carried {@code products.image_url} all
 * along. The photo now travels with the offer, and this pins the two ways that
 * journey breaks silently.
 *
 * <p>The first is <em>ordering</em>: {@code OfferMatch} is a record built
 * positionally, and {@code imageUrl}, {@code retailerName}, {@code productName}
 * and {@code promoLabel} are all {@code String}. Swap any two of them and the
 * compiler is perfectly happy — the app just renders a Makro banner where the
 * product photo belongs. So every string in the record is asserted here, not
 * only the new one, and each fixture value is distinct enough that a swap
 * cannot pass.
 *
 * <p>The second is <em>absence</em>: the scraper deploys separately, so a
 * response with no {@code imageUrl} at all has to keep producing exactly the
 * offer it produced before.
 */
class OfferMatchClientProductImageTest {

    private static final String SCRAPER_URL = "http://scraper.test";

    /**
     * Shaped after the deployed scraper's response for a barcode hit. Three
     * different URLs on purpose: the product photo, the SKU's page, and the
     * campaign creative. Only the first belongs in {@code OfferMatch.imageUrl}.
     */
    private static final String WITH_IMAGE = """
            {"results":[{
              "query":{"description":"ACEITE GIRASOL COCINERO","barcode":"7790060023684"},
              "matchedBrand":"COCINERO",
              "method":"barcode",
              "bestOfferMethod":"ean",
              "bestCatalogOffer":{
                "productName":"Aceite de girasol Cocinero 1.5 l.",
                "brand":"Cocinero","listPrice":4290,"sellingPrice":3290,
                "discountPct":23.31,"promoLabels":["2da unidad 70%"],
                "url":"https://carrefourar.com.ar/aceite-cocinero-15l/p",
                "imageUrl":"https://carrefourar.vteximg.com.br/arquivos/ids/740784/7791720019054_E01.jpg",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"},
              "campaignOffers":[{
                "externalId":"32eb1986-8520-4e34-9f47-7e6bbed4a695",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina",
                "province":"CABA/GBA","legalText":"PROMOCION VALIDA DEL 01/09/2026 AL 08/09/2026",
                "activeTo":"2026-09-08T23:59:00",
                "imageUrl":"https://example.test/creative-80-marcas.jpg",
                "bestGuessPercentages":[80],
                "mechanic":"second_unit",
                "percentagesUnverified":false}],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** The same offer as an older scraper sends it: no {@code imageUrl} key. */
    private static final String WITHOUT_IMAGE = """
            {"results":[{
              "query":{"description":"ACEITE GIRASOL COCINERO","barcode":"7790060023684"},
              "matchedBrand":"COCINERO",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Aceite de girasol Cocinero 1.5 l.",
                "brand":"Cocinero","listPrice":4290,"sellingPrice":3290,
                "discountPct":23.31,"promoLabels":["2da unidad 70%"],
                "url":"https://carrefourar.com.ar/aceite-cocinero-15l/p",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** A line with no catalog offer at all, which builds the record from nulls. */
    private static final String NO_OFFER = """
            {"results":[{
              "query":{"description":"FIAMBRE VARIADO","barcode":""},
              "matchedBrand":null,
              "method":"none",
              "bestCatalogOffer":null,
              "campaignOffers":[],
              "alternativeBrandOffers":[]
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

    private OfferMatch matchOne(String responseBody) {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
        List<OfferMatch> matches = client.matchProducts(
                List.of(new ProductQuery("ACEITE GIRASOL COCINERO", "7790060023684")), false, List.of());
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    @Test
    @DisplayName("the catalog photo reaches the match, and lands in its own field")
    void carriesProductImage() {
        OfferMatch match = matchOne(WITH_IMAGE);

        assertEquals(
                "https://carrefourar.vteximg.com.br/arquivos/ids/740784/7791720019054_E01.jpg",
                match.imageUrl(),
                "the product photo was dropped between the scraper and the app");

        // The rest of the strings, so a positional slip in the record shows up
        // as a failure here rather than as the wrong picture on a card.
        assertEquals("COCINERO", match.matchedBrand());
        assertEquals("Carrefour Argentina", match.retailerName());
        assertEquals("Aceite de girasol Cocinero 1.5 l.", match.productName());
        assertEquals("2da unidad 70%", match.promoLabel());
        assertEquals(new BigDecimal("3290.0"), match.price());
        assertEquals(new BigDecimal("4290.0"), match.listPrice());
        assertTrue(match.hasOffer());

        // The campaign creative is a different picture of a different thing and
        // must stay where it was.
        assertEquals(1, match.campaignOffers().size());
        assertEquals("https://example.test/creative-80-marcas.jpg",
                match.campaignOffers().get(0).imageUrl());
    }

    @Test
    @DisplayName("an older scraper that sends no photo still yields the same offer")
    void degradesWhenScraperOmitsImage() {
        OfferMatch match = matchOne(WITHOUT_IMAGE);

        assertNull(match.imageUrl(), "a missing photo must be null, not a stray value from another field");
        // Everything the card showed before is untouched: the missing photo
        // costs the user an icon, never the price.
        assertEquals("Carrefour Argentina", match.retailerName());
        assertEquals("Aceite de girasol Cocinero 1.5 l.", match.productName());
        assertEquals("2da unidad 70%", match.promoLabel());
        assertEquals(new BigDecimal("3290.0"), match.price());
        assertTrue(match.hasOffer(), "the offer is still an offer without a picture");
    }

    @Test
    @DisplayName("a line with no offer at all is unaffected")
    void noOfferStillMapsCleanly() {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match"))
                .andRespond(withSuccess(NO_OFFER, MediaType.APPLICATION_JSON));
        List<OfferMatch> matches = client.matchProducts(
                List.of(new ProductQuery("FIAMBRE VARIADO", "")), false, List.of());

        assertEquals(1, matches.size());
        OfferMatch match = matches.get(0);
        assertNull(match.imageUrl());
        assertNull(match.retailerName());
        assertNull(match.price());
        assertTrue(!match.hasOffer());
        assertNotNull(match.campaignOffers());
    }

    @Test
    @DisplayName("the fallback built when the scraper is unreachable carries no photo either")
    void noneCarriesNoImage() {
        assertNull(OfferMatch.none().imageUrl());
        assertNull(OfferMatch.none().productName());
        assertTrue(!OfferMatch.none().hasOffer());
    }
}
