package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OFT-20: al menos 2 tickets en los últimos 90 días. */
class ProductServiceRecurringRuleTest {

    private TicketRepository ticketRepository;
    private ProductService productService;
    private User user;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        OfferMatchClient offerMatchClient = mock(OfferMatchClient.class);
        FavoriteStoreService favoriteStoreService = mock(FavoriteStoreService.class);
        productService = new ProductService(ticketRepository, offerMatchClient, favoriteStoreService,
                mock(FollowedProductRepository.class), org.mockito.Mockito.mock(ar.edu.ofertAR.repository.HabitualProductRepository.class));
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        when(favoriteStoreService.getFavoriteChainSlugs(any())).thenReturn(List.of());
        when(offerMatchClient.matchProducts(any(), anyBoolean(), any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(q -> OfferMatch.none()).toList());
    }

    private Ticket ticket(int daysAgo, String... products) {
        Ticket ticket = Ticket.builder().id(nextId++).user(user).status(TicketStatus.PROCESSED)
                .createdAt(LocalDateTime.now().minusDays(daysAgo)).build();
        List<TicketItem> items = new ArrayList<>();
        for (String product : products) {
            items.add(TicketItem.builder().ticket(ticket).description(product)
                    .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("1000")).build());
        }
        ticket.setItems(items);
        return ticket;
    }

    private List<String> recurring(Ticket... tickets) {
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of(tickets));
        return productService.getRecurringProducts(user, null).stream()
                .map(RecurringProductResponse::getDescription)
                .toList();
    }

    @Test
    @DisplayName("un producto comprado una sola vez no es recurrente: el caso de la tele")
    void boughtOnce_isNotRecurring() {
        assertEquals(List.of("YERBA"), recurring(
                ticket(1, "YERBA", "TELEVISOR 50"),
                ticket(10, "YERBA")));
    }

    @Test
    @DisplayName("dos unidades en un mismo ticket siguen siendo una sola compra")
    void twoUnitsOnOneTicket_isNotRecurring() {
        assertTrue(recurring(ticket(1, "PILAS", "PILAS")).isEmpty());
    }

    @Test
    @DisplayName("una compra de hace más de 90 días no cuenta para el mínimo")
    void purchaseOutsideWindow_doesNotCount() {
        assertTrue(recurring(ticket(1, "YERBA"), ticket(91, "YERBA")).isEmpty());
    }

    @Test
    @DisplayName("dos compras dentro de los 90 días alcanzan")
    void twoPurchasesInsideWindow_isRecurring() {
        assertEquals(List.of("YERBA"), recurring(ticket(1, "YERBA"), ticket(89, "YERBA")));
    }

    @Test
    @DisplayName("se puede pedir un ticket de referencia viejo, aunque no cuente para la regla")
    void oldReferenceTicket_isStillFound() {
        Ticket viejo = ticket(200, "ARROZ");
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(anyLong()))
                .thenReturn(List.of(ticket(1, "YERBA"), ticket(2, "YERBA"), viejo));

        List<RecurringProductResponse> products = productService.getRecurringProducts(user, viejo.getId());

        assertEquals(1, products.size());
        assertFalse(products.get(0).isInReferenceTicket());
    }
}
