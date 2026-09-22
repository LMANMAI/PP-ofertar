package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.PromoMechanic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The last hop of the promotion mechanics: from the scraper client's
 * {@code OfferMatch} into the {@code RecurringProductResponse} the app reads.
 *
 * <p>It needs its own test for the same reason the product photo did. The DTO
 * is a Lombok builder, so leaving {@code .promoLabels(...)} or
 * {@code .promoMechanics(...)} out of it compiles, serialises and ships — the
 * key is simply absent from the JSON, and the card goes back to saying "mejor
 * en Carrefour" with no mention of the 3x2, which is indistinguishable from a
 * product that has no promotion at all.
 *
 * <p>The case that matters most is the last one here: a product with a 3x2 has
 * no unit discount, so it has no best offer either. If the mechanics were
 * mapped inside that branch they would vanish for exactly the products the
 * user was complaining about.
 */
class ProductServicePromoMechanicsTest {

    private TicketRepository ticketRepository;
    private OfferMatchClient offerMatchClient;
    private FavoriteStoreService favoriteStoreService;
    private ProductService productService;
    private User user;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        offerMatchClient = mock(OfferMatchClient.class);
        favoriteStoreService = mock(FavoriteStoreService.class);
        productService = new ProductService(ticketRepository, offerMatchClient, favoriteStoreService);

        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        when(favoriteStoreService.getFavoriteChainSlugs(any())).thenReturn(List.of());

        Ticket ticket = Ticket.builder()
                .id(1L)
                .user(user)
                .status(TicketStatus.PROCESSED)
                .storeName("Carrefour")
                .build();
        ticket.setItems(List.of(TicketItem.builder()
                .id(10L)
                .ticket(ticket)
                .description("GASEOSA COCA COLA 2.25L")
                .barcode("7790895000997")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("3500.00"))
                .category("Bebidas")
                .build()));
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of(ticket));
    }

    private RecurringProductResponse productFrom(OfferMatch match) {
        when(offerMatchClient.matchProducts(any(), anyBoolean(), any())).thenReturn(List.of(match));
        List<RecurringProductResponse> products = productService.getRecurringProducts(user, null);
        assertEquals(1, products.size());
        return products.get(0);
    }

    private RecurringProductResponse.BestOffer bestOfferFrom(OfferMatch match) {
        return productFrom(match).getBestOffer();
    }

    @Test
    @DisplayName("the whole label array reaches the app, not just the bank promotion in front of it")
    void exposesEveryPromoLabel() {
        List<String> labels = List.of(
                "Tarjeta Carrefour 15%", "Tarjeta Carrefour 20% Off Martes", "80% en la 2da unidad");

        RecurringProductResponse.BestOffer offer = bestOfferFrom(new OfferMatch(
                "COCA COLA",
                "Carrefour Argentina",
                "Gaseosa Coca Cola 2.25 l.",
                "https://carrefourar.vteximg.com.br/arquivos/ids/111/coca.jpg",
                new BigDecimal("3500"),
                new BigDecimal("3500"),
                null,
                labels.get(0),
                labels,
                2,
                null,
                List.of(),
                List.of(),
                List.of()));

        assertNotNull(offer);
        assertEquals(labels, offer.getPromoLabels(),
                "the app is back to seeing only the first label — the mechanic is the last one here");
        assertTrue(offer.getPromoLabels().contains("80% en la 2da unidad"));
        // Unchanged contract: the string the app reads today still arrives.
        assertEquals("Tarjeta Carrefour 15%", offer.getPromoLabel());
        assertEquals(Integer.valueOf(2), offer.getRequiredQuantity());
        assertNull(offer.getPromoUnitPrice());
        assertEquals("Carrefour Argentina", offer.getRetailerName());
        assertEquals(new BigDecimal("3500"), offer.getPrice());
    }

    @Test
    @DisplayName("a conditional promo's effective unit price reaches the app too")
    void exposesConditionalUnitPrice() {
        RecurringProductResponse.BestOffer offer = bestOfferFrom(new OfferMatch(
                "BAGLEY",
                "COTO",
                "Bocaditos Bagley 250 g.",
                null,
                new BigDecimal("7999"),
                new BigDecimal("7999"),
                null,
                "2X1",
                List.of("2X1"),
                2,
                new BigDecimal("3999.5"),
                List.of(),
                List.of(),
                List.of()));

        assertNotNull(offer);
        assertEquals(List.of("2X1"), offer.getPromoLabels());
        assertEquals(Integer.valueOf(2), offer.getRequiredQuantity());
        assertEquals(new BigDecimal("3999.5"), offer.getPromoUnitPrice());
        // The shelf price of one unit is untouched by the promotion.
        assertEquals(new BigDecimal("7999"), offer.getPrice());
    }

    @Test
    @DisplayName("an offer from an older scraper carries no invented condition")
    void oldScraperOfferStaysUnknown() {
        RecurringProductResponse product = productFrom(new OfferMatch(
                "COCA COLA",
                "Carrefour Argentina",
                "Gaseosa Coca Cola 2.25 l.",
                null,
                new BigDecimal("3290"),
                new BigDecimal("4290"),
                new BigDecimal("23.31"),
                null,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of()));

        RecurringProductResponse.BestOffer offer = product.getBestOffer();
        assertNotNull(offer);
        assertNotNull(offer.getPromoLabels(), "an absent array is an empty one, never null");
        assertTrue(offer.getPromoLabels().isEmpty());
        assertNull(offer.getPromoLabel());
        assertNull(offer.getRequiredQuantity(), "unknown must not be rendered as 'no condition'");
        assertNull(offer.getPromoUnitPrice());
        assertNotNull(product.getPromoMechanics(), "an older scraper gets an empty list, not a null");
        assertTrue(product.getPromoMechanics().isEmpty());
        // The offer itself is exactly the one the app already showed.
        assertEquals(new BigDecimal("3290"), offer.getPrice());
        assertEquals(new BigDecimal("23.31"), offer.getDiscountPct());
    }

    @Test
    @DisplayName("quantity promotions reach the app in the order the scraper ranked them")
    void exposesPromoMechanics() {
        PromoMechanic twoForOne = new PromoMechanic(
                "COTO",
                "Bocaditos Bagley 250 g.",
                "https://static.cotodigital.com.ar/bocaditos.jpg",
                new BigDecimal("7999"),
                new BigDecimal("7999"),
                List.of("2X1", "Llevando 2 unidades"),
                2,
                new BigDecimal("3999.5"));
        PromoMechanic threeForTwo = new PromoMechanic(
                "Carrefour Argentina",
                "Hamburguesas Paty 4 u.",
                null,
                new BigDecimal("7599"),
                null,
                List.of("3X2"),
                3,
                null);

        RecurringProductResponse product = productFrom(new OfferMatch(
                "BAGLEY", null, null, null, null, null, null,
                null, List.of(), null, null,
                List.of(twoForOne, threeForTwo),
                List.of(),
                List.of()));

        List<RecurringProductResponse.PromoMechanic> mechanics = product.getPromoMechanics();
        assertEquals(2, mechanics.size(), "a quantity promotion was dropped on the way to the app");

        // Most reachable first, as the scraper ordered them: a 2x1 asks less
        // than a 3x2. Re-sorting here would quietly change what the card leads with.
        assertEquals("Bocaditos Bagley 250 g.", mechanics.get(0).getProductName());
        assertEquals("COTO", mechanics.get(0).getRetailerName());
        assertEquals("https://static.cotodigital.com.ar/bocaditos.jpg", mechanics.get(0).getImageUrl());
        assertEquals(List.of("2X1", "Llevando 2 unidades"), mechanics.get(0).getPromoLabels());
        assertEquals(Integer.valueOf(2), mechanics.get(0).getRequiredQuantity());
        // What one unit costs, and what each costs taking two: two different
        // numbers that must stay in two different fields.
        assertEquals(new BigDecimal("7999"), mechanics.get(0).getUnitPrice());
        assertEquals(new BigDecimal("3999.5"), mechanics.get(0).getPromoUnitPrice());

        assertEquals("Hamburguesas Paty 4 u.", mechanics.get(1).getProductName());
        assertEquals(Integer.valueOf(3), mechanics.get(1).getRequiredQuantity());
        assertEquals(new BigDecimal("7599"), mechanics.get(1).getUnitPrice());
        assertNull(mechanics.get(1).getPromoUnitPrice(),
                "Carrefour never publishes the effective price — the app must show the label instead");
    }

    @Test
    @DisplayName("a product with a 3x2 and no discount still shows its promotion")
    void mechanicsSurviveWithoutABestOffer() {
        // The whole point of the feature: these products have no unit discount,
        // so there is no bestOffer to hang them off. Mapping them inside that
        // branch would lose every one of them.
        RecurringProductResponse product = productFrom(new OfferMatch(
                "BAGLEY", null, null, null, null, null, null,
                null, List.of(), null, null,
                List.of(new PromoMechanic("COTO", "Bocaditos Bagley 250 g.", null,
                        new BigDecimal("7999"), null, List.of("2X1"), 2, null)),
                List.of(),
                List.of()));

        assertNull(product.getBestOffer(), "there is no price offer here, and none must be invented");
        assertEquals(1, product.getPromoMechanics().size(),
                "the only promotion this product has was dropped because it had no best offer");
        assertEquals(List.of("2X1"), product.getPromoMechanics().get(0).getPromoLabels());
    }

    @Test
    @DisplayName("no mechanics is an empty list, never null")
    void noMechanicsIsEmpty() {
        RecurringProductResponse product = productFrom(OfferMatch.none());

        assertNull(product.getBestOffer());
        assertNotNull(product.getPromoMechanics());
        assertTrue(product.getPromoMechanics().isEmpty());
    }
}
