package ar.edu.ofertAR.service.offer;

import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Narrowing the feed by category. The catalog side is filtered in SQL; campaign
 * categories are read off the promo image, so there is no column to filter and
 * they are narrowed here instead.
 */
class OfferFeedCategoryFilterTest {

    private static OfferFeedResponse.Offer offer(String id, String category) {
        return OfferFeedResponse.Offer.builder().id(id).category(category).build();
    }

    private static List<String> ids(List<OfferFeedResponse.Offer> offers) {
        return offers.stream().map(OfferFeedResponse.Offer::getId).toList();
    }

    private final List<OfferFeedResponse.Offer> feed = List.of(
            offer("a", "Almacén"),
            offer("b", "Bebidas"),
            offer("c", null),
            offer("d", "Almacén"));

    @Test
    @DisplayName("no categories means everything, not nothing")
    void emptyFilterKeepsEverything() {
        assertEquals(4, OfferFeedClient.keepCategories(feed, List.of()).size());
        assertEquals(4, OfferFeedClient.keepCategories(feed, null).size());
    }

    @Test
    @DisplayName("only the chosen categories survive")
    void keepsTheChosenCategories() {
        assertEquals(List.of("a", "d"), ids(OfferFeedClient.keepCategories(feed, List.of("Almacén"))));
        assertEquals(List.of("a", "b", "d"),
                ids(OfferFeedClient.keepCategories(feed, List.of("Almacén", "Bebidas"))));
    }

    @Test
    @DisplayName("an offer with no category is not silently kept")
    void dropsUncategorisedOffers() {
        // Makro's flyer-scraped rows carry no category at all, so a category
        // filter must exclude them rather than let them through unchecked.
        assertEquals(List.of("b"), ids(OfferFeedClient.keepCategories(feed, List.of("Bebidas"))));
    }

    @Test
    @DisplayName("a category nobody has yields an empty feed, not the whole one")
    void unknownCategoryYieldsNothing() {
        assertEquals(List.of(), ids(OfferFeedClient.keepCategories(feed, List.of("Perfumería"))));
    }

    @Test
    @DisplayName("filtering campaigns first keeps the catalog offsets consistent")
    void slotCountFollowsTheFilteredCampaigns() {
        // Two of the four survive, so the first page reserves slots for two and
        // the catalog behind it resumes at the matching row.
        int shown = OfferFeedClient.campaignsOnFirstPage(
                OfferFeedClient.keepCategories(feed, List.of("Almacén")).size(), 50);
        assertEquals(2, shown);
        assertEquals(new OfferFeedClient.CatalogWindow(0, 48), OfferFeedClient.catalogWindow(1, 50, shown));
        assertEquals(new OfferFeedClient.CatalogWindow(48, 50), OfferFeedClient.catalogWindow(2, 50, shown));
    }
}
