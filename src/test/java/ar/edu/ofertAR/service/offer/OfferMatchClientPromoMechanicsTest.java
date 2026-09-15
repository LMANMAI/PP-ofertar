package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchClient.PromoMechanic;
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
 * The promotion mechanics — "3x2", "80% en la 2da unidad" — never reached
 * "Productos que comprás seguido": the client read {@code promoLabels[0]} and
 * threw the rest away.
 *
 * <p>That is worse than it sounds with the real catalog. Carrefour leads the
 * array with its card promotions, so the single label that survived was almost
 * always the bank one and the mechanic — the only part the user can act on
 * standing at the shelf — was the one dropped. The fixtures below are shaped
 * after live responses for that reason: the mechanic is never first.
 *
 * <p>Also pinned here is what an <em>older</em> scraper must keep producing.
 * The scraper deploys on its own schedule, so a response with no
 * {@code requiredQuantity} at all is a normal response, and the answer to
 * "how many units?" in that case is "unknown" — not 1 and not 2, either of
 * which would put a claim on screen that nothing supports.
 */
class OfferMatchClientPromoMechanicsTest {

    private static final String SCRAPER_URL = "http://scraper.test";

    /** Carrefour, as measured: three bank promotions and then the mechanic. */
    private static final String MANY_LABELS = """
            {"results":[{
              "query":{"description":"GASEOSA COCA COLA 2.25L","barcode":"7790895000997"},
              "matchedBrand":"COCA COLA",
              "method":"barcode",
              "bestOfferMethod":"ean",
              "bestCatalogOffer":{
                "productName":"Gaseosa Coca Cola 2.25 l.",
                "brand":"Coca Cola","listPrice":3500,"sellingPrice":3500,
                "discountPct":null,
                "promoLabels":["Tarjeta Carrefour 15%","Tarjeta Carrefour 20% Off Martes",
                               "PROMO-Mi CRF -mfl-1-7-Dto de 7% Doble Precio","80% en la 2da unidad"],
                "requiredQuantity":2,
                "promoUnitPrice":null,
                "url":"https://carrefourar.com.ar/coca-cola-225/p",
                "imageUrl":"https://carrefourar.vteximg.com.br/arquivos/ids/111/coca.jpg",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** COTO: one label, a real condition, and the effective unit price. */
    private static final String CONDITIONAL_WITH_UNIT_PRICE = """
            {"results":[{
              "query":{"description":"BOCADITOS BAGLEY","barcode":"7790040999992"},
              "matchedBrand":"BAGLEY",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Bocaditos Bagley 250 g.",
                "brand":"Bagley","listPrice":7999,"sellingPrice":7999,
                "discountPct":null,
                "promoLabels":["2X1"],
                "requiredQuantity":2,
                "promoUnitPrice":3999.5,
                "url":"https://www.cotodigital.com.ar/bocaditos/p",
                "imageUrl":"https://static.cotodigital.com.ar/bocaditos.jpg",
                "retailerSlug":"coto","retailerName":"COTO"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** A plain unit discount: no condition, and the chain says so with a 1. */
    private static final String NO_LABELS = """
            {"results":[{
              "query":{"description":"LECHE LA SERENISIMA","barcode":"7790742000019"},
              "matchedBrand":"LA SERENISIMA",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Leche La Serenísima 1 l.",
                "brand":"La Serenisima","listPrice":1800,"sellingPrice":1400,
                "discountPct":22.22,
                "promoLabels":[],
                "requiredQuantity":1,
                "promoUnitPrice":null,
                "url":"https://www.cotodigital.com.ar/leche/p",
                "imageUrl":null,
                "retailerSlug":"coto","retailerName":"COTO"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** The deployed scraper before today: no {@code requiredQuantity}, no
     * {@code promoUnitPrice}, and not even a {@code promoLabels} key. */
    private static final String OLD_SCRAPER = """
            {"results":[{
              "query":{"description":"ACEITE GIRASOL COCINERO","barcode":"7790060023684"},
              "matchedBrand":"COCINERO",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Aceite de girasol Cocinero 1.5 l.",
                "brand":"Cocinero","listPrice":4290,"sellingPrice":3290,
                "discountPct":23.31,
                "url":"https://carrefourar.com.ar/aceite-cocinero-15l/p",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** A quantity that cannot be one: nobody buys zero units to earn a promo. */
    private static final String BROKEN_QUANTITY = """
            {"results":[{
              "query":{"description":"ARROZ GALLO","barcode":"7790070410016"},
              "matchedBrand":"GALLO",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Arroz Gallo Oro 1 kg.",
                "brand":"Gallo","listPrice":2500,"sellingPrice":2000,
                "discountPct":20,
                "promoLabels":[null,"","3X2"],
                "requiredQuantity":0,
                "promoUnitPrice":null,
                "url":"https://carrefourar.com.ar/arroz-gallo/p",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"},
              "campaignOffers":[],
              "alternativeBrandOffers":[]
            }]}
            """;

    /**
     * The case the whole {@code promoMechanics} array exists for: a brand whose
     * promotions are all conditions on quantity, so there is no
     * {@code bestCatalogOffer} at all — the scraper's best-offer query requires
     * a unit discount and these rows have none.
     */
    private static final String MECHANICS_WITHOUT_OFFER = """
            {"results":[{
              "query":{"description":"BOCADITOS BAGLEY","barcode":"7790040999992"},
              "matchedBrand":"BAGLEY",
              "method":"brand_text",
              "bestOfferMethod":"brand_text",
              "bestCatalogOffer":null,
              "campaignOffers":[],
              "promoMechanics":[
                {"productName":"Bocaditos Bagley 250 g.","brand":"Bagley",
                 "listPrice":7999,"sellingPrice":7999,"discountPct":null,
                 "promoLabels":["2X1","Llevando 2 unidades"],
                 "requiredQuantity":2,"promoUnitPrice":3999.5,
                 "url":"https://www.cotodigital.com.ar/bocaditos/p",
                 "imageUrl":"https://static.cotodigital.com.ar/bocaditos.jpg",
                 "retailerSlug":"coto","retailerName":"COTO"},
                {"productName":"Galletitas Bagley Rumba 350 g.","brand":"Bagley",
                 "listPrice":null,"sellingPrice":4200,"discountPct":null,
                 "promoLabels":["3X2"],
                 "requiredQuantity":3,"promoUnitPrice":null,
                 "url":"https://carrefourar.com.ar/rumba/p",
                 "imageUrl":null,
                 "retailerSlug":"carrefour","retailerName":"Carrefour Argentina"}],
              "alternativeBrandOffers":[]
            }]}
            """;

    /** A brand with a discount but no quantity promotion: the key is there and
     * empty, which is what the scraper sends on the no-brand branch too. */
    private static final String MECHANICS_EMPTY = """
            {"results":[{
              "query":{"description":"LECHE LA SERENISIMA","barcode":"7790742000019"},
              "matchedBrand":"LA SERENISIMA",
              "method":"barcode",
              "bestCatalogOffer":{
                "productName":"Leche La Serenísima 1 l.",
                "brand":"La Serenisima","listPrice":1800,"sellingPrice":1400,
                "discountPct":22.22,"promoLabels":[],
                "requiredQuantity":1,"promoUnitPrice":null,
                "url":"https://www.cotodigital.com.ar/leche/p","imageUrl":null,
                "retailerSlug":"coto","retailerName":"COTO"},
              "campaignOffers":[],
              "promoMechanics":[],
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
                List.of(new ProductQuery("cualquier producto", "7790000000000")), false, List.of());
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    @Test
    @DisplayName("every label survives, mechanic included, when the chain publishes several")
    void keepsEveryLabel() {
        OfferMatch match = matchOne(MANY_LABELS);

        // The assertion that fails the moment someone goes back to keeping
        // only promoLabels[0]: the mechanic is the *last* element here, exactly
        // as the live Carrefour catalog orders it.
        assertEquals(
                List.of("Tarjeta Carrefour 15%", "Tarjeta Carrefour 20% Off Martes",
                        "PROMO-Mi CRF -mfl-1-7-Dto de 7% Doble Precio", "80% en la 2da unidad"),
                match.promoLabels(),
                "the promo labels were truncated — the mechanic is not the first one");
        assertTrue(match.promoLabels().contains("80% en la 2da unidad"),
                "the only label the user can act on at the shelf was dropped");

        // The legacy single field keeps meaning what it always meant, so the
        // app that reads it today reads the same thing today.
        assertEquals("Tarjeta Carrefour 15%", match.promoLabel());

        assertEquals(Integer.valueOf(2), match.requiredQuantity());
        // Carrefour's teaser says what the promotion is but never what it ends
        // up costing, and a price invented here would be a price on screen.
        assertNull(match.promoUnitPrice(), "a unit price the chain never published must stay null");

        // Same positional-slip guard the image test documents: every string in
        // the record is distinct, so a swap cannot pass silently.
        assertEquals("COCA COLA", match.matchedBrand());
        assertEquals("Carrefour Argentina", match.retailerName());
        assertEquals("Gaseosa Coca Cola 2.25 l.", match.productName());
        assertEquals("https://carrefourar.vteximg.com.br/arquivos/ids/111/coca.jpg", match.imageUrl());
        assertEquals(new BigDecimal("3500.0"), match.price());
        assertTrue(match.hasOffer());
        // This fixture carries no promoMechanics key either, as an older
        // scraper would send it.
        assertTrue(match.promoMechanics().isEmpty());
    }

    @Test
    @DisplayName("a conditional promo carries its quantity and its effective unit price")
    void carriesConditionalPromo() {
        OfferMatch match = matchOne(CONDITIONAL_WITH_UNIT_PRICE);

        assertEquals(List.of("2X1"), match.promoLabels());
        assertEquals("2X1", match.promoLabel(), "one label still fills the legacy field");
        assertEquals(Integer.valueOf(2), match.requiredQuantity());
        assertEquals(new BigDecimal("3999.5"), match.promoUnitPrice());
        // sellingPrice is still what ONE unit costs — the promo has not been
        // applied to it — and mixing the two up would halve the shelf price.
        assertEquals(new BigDecimal("7999.0"), match.price());
        assertEquals("COTO", match.retailerName());
    }

    @Test
    @DisplayName("an empty label array is no labels, and an explicit 1 is no condition")
    void emptyLabelsAndNoCondition() {
        OfferMatch match = matchOne(NO_LABELS);

        assertNotNull(match.promoLabels(), "the list is never null, so no consumer has to guard it");
        assertTrue(match.promoLabels().isEmpty());
        assertNull(match.promoLabel());
        // 1 is an answer, not an absence: the chain did say "no condition".
        assertEquals(Integer.valueOf(1), match.requiredQuantity());
        assertNull(match.promoUnitPrice());
        assertEquals(new BigDecimal("1400.0"), match.price());
        assertTrue(match.hasOffer());
    }

    @Test
    @DisplayName("an older scraper that sends none of the new fields yields the same offer as before")
    void degradesWhenScraperOmitsFields() {
        OfferMatch match = matchOne(OLD_SCRAPER);

        assertTrue(match.promoLabels().isEmpty(), "a missing key is no labels, not a crash");
        assertNull(match.promoLabel());
        // The decision this test exists to pin: unknown stays unknown. A 1 here
        // would claim the price is unconditional, a 2 would invent a condition.
        assertNull(match.requiredQuantity(),
                "an absent requiredQuantity must not be defaulted into a claim");
        assertNull(match.promoUnitPrice());

        // And everything the card showed before is untouched.
        assertEquals("Carrefour Argentina", match.retailerName());
        assertEquals("Aceite de girasol Cocinero 1.5 l.", match.productName());
        assertEquals(new BigDecimal("3290.0"), match.price());
        assertEquals(new BigDecimal("4290.0"), match.listPrice());
        assertTrue(match.hasOffer());
    }

    @Test
    @DisplayName("an impossible quantity is unknown, and blank labels are dropped")
    void rejectsBrokenQuantityAndBlankLabels() {
        OfferMatch match = matchOne(BROKEN_QUANTITY);

        assertNull(match.requiredQuantity(), "0 units is not a quantity, it is a broken payload");
        assertEquals(List.of("3X2"), match.promoLabels(),
                "nulls and blanks are not labels and must not reach the card");
        assertEquals("3X2", match.promoLabel());
    }

    @Test
    @DisplayName("quantity promotions arrive even when the product has no price offer")
    void carriesPromoMechanicsWithoutAnOffer() {
        OfferMatch match = matchOne(MECHANICS_WITHOUT_OFFER);

        // Not mapped inside the "there is an offer" branch: a 3x2 product has
        // no unit discount, so gating on the offer loses all of them.
        List<PromoMechanic> mechanics = match.promoMechanics();
        assertEquals(2, mechanics.size(), "the quantity promotions never left the client");
        assertTrue(!match.hasOffer(), "there is no price offer here, and none must be invented");
        assertNull(match.price());

        // Order is the scraper's — most reachable first — and must survive.
        PromoMechanic first = mechanics.get(0);
        assertEquals("COTO", first.retailerName());
        assertEquals("Bocaditos Bagley 250 g.", first.productName());
        assertEquals("https://static.cotodigital.com.ar/bocaditos.jpg", first.imageUrl());
        assertEquals(List.of("2X1", "Llevando 2 unidades"), first.promoLabels(),
                "the labels of a mechanic get truncated as easily as the offer's did");
        assertEquals(Integer.valueOf(2), first.requiredQuantity());
        // sellingPrice is the price of ONE unit with the promo not applied; the
        // effective one is a separate number and lands in a separate field.
        assertEquals(new BigDecimal("7999.0"), first.unitPrice());
        assertEquals(new BigDecimal("3999.5"), first.promoUnitPrice());
        assertEquals(new BigDecimal("7999.0"), first.listPrice());

        PromoMechanic second = mechanics.get(1);
        assertEquals("Carrefour Argentina", second.retailerName());
        assertEquals("Galletitas Bagley Rumba 350 g.", second.productName());
        assertEquals(List.of("3X2"), second.promoLabels());
        assertEquals(Integer.valueOf(3), second.requiredQuantity());
        assertEquals(new BigDecimal("4200.0"), second.unitPrice());
        assertNull(second.promoUnitPrice(),
                "Carrefour publishes no effective price, so there must be none to show");
        assertNull(second.listPrice());
        assertNull(second.imageUrl());
    }

    @Test
    @DisplayName("an explicitly empty mechanics array is simply no mechanics")
    void emptyMechanicsArray() {
        OfferMatch match = matchOne(MECHANICS_EMPTY);

        assertNotNull(match.promoMechanics());
        assertTrue(match.promoMechanics().isEmpty());
        // And the ordinary offer beside it is untouched.
        assertTrue(match.hasOffer());
        assertEquals(new BigDecimal("1400.0"), match.price());
    }

    @Test
    @DisplayName("an older scraper that sends no mechanics key yields an empty list, not a null")
    void missingMechanicsKeyDegrades() {
        // One response per test: MockRestServiceServer refuses further
        // expectations once a request has gone through it.
        assertTrue(matchOne(OLD_SCRAPER).promoMechanics().isEmpty(),
                "a missing key must not reach the app as a null list");
    }

    @Test
    @DisplayName("the fallback used when the scraper is unreachable invents nothing")
    void noneCarriesNothing() {
        assertTrue(OfferMatch.none().promoLabels().isEmpty());
        assertNull(OfferMatch.none().promoLabel());
        assertNull(OfferMatch.none().requiredQuantity());
        assertNull(OfferMatch.none().promoUnitPrice());
        assertTrue(OfferMatch.none().promoMechanics().isEmpty());
    }
}
