package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code RecurringProductResponse.followed}: el producto que el usuario compra
 * y además sigue tiene que reconocerse como el mismo, aunque el ticket y el
 * seguimiento escriban el código de barras distinto.
 */
class ProductServiceFollowedTest {

    private TicketRepository ticketRepository;
    private FollowedProductRepository followedProductRepository;
    private ProductService productService;
    private User user;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        OfferMatchClient offerMatchClient = mock(OfferMatchClient.class);
        FavoriteStoreService favoriteStoreService = mock(FavoriteStoreService.class);
        followedProductRepository = mock(FollowedProductRepository.class);
        productService = new ProductService(ticketRepository, offerMatchClient, favoriteStoreService, followedProductRepository);

        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        when(favoriteStoreService.getFavoriteChainSlugs(any())).thenReturn(List.of());
        when(offerMatchClient.matchProducts(any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(q -> OfferMatch.none()).toList());
    }

    private void ticketWith(TicketItem... items) {
        Ticket ticket = Ticket.builder().id(1L).user(user).status(TicketStatus.PROCESSED).storeName("Coto").build();
        List<TicketItem> list = new ArrayList<>();
        for (TicketItem item : items) {
            item.setTicket(ticket);
            list.add(item);
        }
        ticket.setItems(list);
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of(ticket));
    }

    private static TicketItem item(String description, String barcode) {
        return TicketItem.builder().description(description).barcode(barcode)
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("1000")).build();
    }

    private void follows(String... eans) {
        List<FollowedProduct> followed = new ArrayList<>();
        for (String ean : eans) {
            followed.add(FollowedProduct.builder().user(user).ean(ean).description("Seguido " + ean).build());
        }
        when(followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(followed);
    }

    private Map<String, RecurringProductResponse> byDescription() {
        return productService.getRecurringProducts(user, null).stream()
                .collect(Collectors.toMap(RecurringProductResponse::getDescription, Function.identity()));
    }

    @Test
    @DisplayName("el producto del ticket que el usuario sigue sale marcado como seguido, aunque el código venga en otro formato")
    void followedAcrossFormats() {
        ticketWith(
                item("GASEOSA COCA COLA 2.25L", "07790895000997"),
                item("YERBA PLAYADITO 1KG", "7790387000017"),
                item("CHICLE BELDENT", "0000012345670"));
        follows("7790895000997", "12345670");

        Map<String, RecurringProductResponse> products = byDescription();

        assertTrue(products.get("GASEOSA COCA COLA 2.25L").isFollowed());
        assertTrue(products.get("CHICLE BELDENT").isFollowed());
        assertFalse(products.get("YERBA PLAYADITO 1KG").isFollowed());
    }

    @Test
    @DisplayName("un ítem sin código de barras nunca figura como seguido")
    void noBarcode_isNotFollowed() {
        ticketWith(item("PAN FRANCES", null), item("QUESO CREMOSO", " "));
        follows("7790895000997");

        Map<String, RecurringProductResponse> products = byDescription();

        assertFalse(products.get("PAN FRANCES").isFollowed());
        assertFalse(products.get("QUESO CREMOSO").isFollowed());
    }

    @Test
    @DisplayName("los productos seguidos se leen una sola vez por pedido, no una por producto")
    void oneQueryPerRequest() {
        ticketWith(item("A", "7790895000997"), item("B", "7790387000017"), item("C", "7791234567898"));
        follows("7790895000997");

        productService.getRecurringProducts(user, null);

        verify(followedProductRepository, times(1)).findByUserIdOrderByCreatedAtDescIdDesc(7L);
    }

    @Test
    @DisplayName("sin productos seguidos, ninguno sale marcado")
    void nothingFollowed() {
        ticketWith(item("GASEOSA COCA COLA 2.25L", "7790895000997"));
        follows();

        assertEquals(List.of(false), productService.getRecurringProducts(user, null).stream()
                .map(RecurringProductResponse::isFollowed).toList());
    }
}
