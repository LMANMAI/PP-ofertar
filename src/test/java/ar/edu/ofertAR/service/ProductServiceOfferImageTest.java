package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The last hop of the product photo: from the scraper client's
 * {@code OfferMatch} into the {@code BestOffer} the app actually reads.
 *
 * <p>Worth its own test because nothing else notices when it is missed. The DTO
 * is a Lombok builder, so simply forgetting {@code .imageUrl(...)} compiles,
 * serialises, and ships — the field is just absent from the JSON and the
 * carousel goes on drawing its cart icon, which is indistinguishable from a
 * product the catalog has no photo for.
 */
class ProductServiceOfferImageTest {

    private static final String PHOTO =
            "https://carrefourar.vteximg.com.br/arquivos/ids/740784/7791720019054_E01.jpg";

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
                .description("ACEITE GIRASOL COCINERO")
                .barcode("7790060023684")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("3290.00"))
                .category("Almacen")
                .build()));
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of(ticket));
    }

    private RecurringProductResponse.BestOffer bestOfferFrom(OfferMatch match) {
        when(offerMatchClient.matchProducts(any(), anyBoolean(), any())).thenReturn(List.of(match));
        List<RecurringProductResponse> products = productService.getRecurringProducts(user, null);
        assertEquals(1, products.size());
        return products.get(0).getBestOffer();
    }

    @Test
    @DisplayName("the photo the scraper matched is exposed on the product's best offer")
    void exposesProductImage() {
        RecurringProductResponse.BestOffer offer = bestOfferFrom(new OfferMatch(
                "COCINERO",
                "Carrefour Argentina",
                "Aceite de girasol Cocinero 1.5 l.",
                PHOTO,
                new BigDecimal("3290"),
                new BigDecimal("4290"),
                new BigDecimal("23.31"),
                "2da unidad 70%",
                List.of(),
                List.of()));

        assertNotNull(offer, "the offer itself must survive");
        assertEquals(PHOTO, offer.getImageUrl(), "the product photo never reached the app");
        // Asserted alongside so a field landing in the wrong slot of the record
        // shows up here rather than as the wrong picture on a card.
        assertEquals("Carrefour Argentina", offer.getRetailerName());
        assertEquals("Aceite de girasol Cocinero 1.5 l.", offer.getProductName());
        assertEquals("2da unidad 70%", offer.getPromoLabel());
        assertEquals(new BigDecimal("3290"), offer.getPrice());
    }

    @Test
    @DisplayName("an offer with no photo is still a complete offer")
    void offerWithoutImageIsUnchanged() {
        RecurringProductResponse.BestOffer offer = bestOfferFrom(new OfferMatch(
                "COCINERO",
                "Carrefour Argentina",
                "Aceite de girasol Cocinero 1.5 l.",
                null,
                new BigDecimal("3290"),
                new BigDecimal("4290"),
                new BigDecimal("23.31"),
                null,
                List.of(),
                List.of()));

        assertNotNull(offer);
        assertNull(offer.getImageUrl());
        assertEquals("Carrefour Argentina", offer.getRetailerName());
        assertEquals(new BigDecimal("3290"), offer.getPrice());
    }

    @Test
    @DisplayName("no offer at all still means no offer, not an empty one")
    void noOfferStaysNull() {
        assertNull(bestOfferFrom(OfferMatch.none()));
    }
}
