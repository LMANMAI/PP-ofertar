package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.config.OfferProperties;
import ar.edu.ofertAR.service.offer.OfferMatchClient.CampaignOffer;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The campaign promotions the scraper matches to a brand ("70% en la 2da
 * unidad") are the only offers that carry a validity window, and they reach the
 * app through this client. The body below is a verbatim capture of the
 * scraper's /api/offers/match response, so a field renamed on either side shows
 * up here instead of as an empty section on the recurring-products screen.
 */
class OfferMatchClientCampaignTest {

    private static final String SCRAPER_URL = "http://scraper.test";

    /** Captured from the deployed scraper for "SHAMPOO PANTENE". */
    private static final String RESPONSE = """
            {"results":[{
              "query":{"description":"SHAMPOO PANTENE","barcode":""},
              "matchedBrand":"PANTENE",
              "method":"brand_text",
              "bestCatalogOffer":{
                "productName":"PANTENE SHAMPOO/ACONDICIONADOR X510ML",
                "brand":"PANTENE","listPrice":14574,"sellingPrice":9999,
                "discountPct":31.39,"promoLabels":["del 20 al 26 de Agosto 2026"],
                "url":"https://example.test/flyer.pdf",
                "retailerSlug":"makro","retailerName":"Makro Argentina"},
              "campaignOffers":[{
                "externalId":"32eb1986-8520-4e34-9f47-7e6bbed4a695",
                "retailerSlug":"carrefour","retailerName":"Carrefour Argentina",
                "province":"CABA/GBA","legalText":"PROMOCION VALIDA DEL 01/09/2026 AL 08/09/2026",
                "activeTo":"2026-09-08T23:59:00",
                "imageUrl":"https://example.test/80marcas.jpg",
                "bestGuessPercentages":[80],
                "mechanic":"second_unit",
                "percentagesUnverified":false}],
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

    @Test
    @DisplayName("a campaign promotion survives the hop from the scraper with every field intact")
    void keepsCampaignOffers() {
        server.expect(requestTo(SCRAPER_URL + "/api/offers/match"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        List<OfferMatch> matches = client.matchProducts(
                List.of(new ProductQuery("SHAMPOO PANTENE", "")), false, List.of());

        assertEquals(1, matches.size());
        OfferMatch match = matches.get(0);
        // The catalog price and the campaign are independent: the bug this
        // guards against showed the first and silently dropped the second.
        assertEquals("Makro Argentina", match.retailerName());
        assertEquals(1, match.campaignOffers().size(), "the campaign promotion was dropped");

        CampaignOffer campaign = match.campaignOffers().get(0);
        assertEquals("32eb1986-8520-4e34-9f47-7e6bbed4a695", campaign.externalId());
        assertEquals("Carrefour Argentina", campaign.retailerName());
        assertEquals("CABA/GBA", campaign.province());
        assertEquals("2026-09-08T23:59:00", campaign.activeTo());
        assertEquals("https://example.test/80marcas.jpg", campaign.imageUrl());
        assertEquals(List.of(80), campaign.discountPercentages());
        assertEquals("second_unit", campaign.mechanic());
        assertFalse(campaign.percentagesUnverified());
    }
}
