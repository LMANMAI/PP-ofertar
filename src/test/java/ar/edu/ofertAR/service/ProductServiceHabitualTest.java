package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.HabitualProduct;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.HabitualProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** La compra habitual: lo detectado en los tickets y lo sumado desde el escáner, en una sola respuesta. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceHabitualTest {

    private static final String EAN = "7790895000997";
    private static final String EAN_14 = "07790895000997";
    private static final String OTHER_EAN = "7791234567891";
    private static final String DESC = "Gaseosa Coca Cola 2.25 L";

    @Mock private TicketRepository ticketRepository;
    @Mock private OfferMatchClient offerMatchClient;
    @Mock private FavoriteStoreService favoriteStoreService;
    @Mock private FollowedProductRepository followedProductRepository;
    @Mock private HabitualProductRepository habitualProductRepository;

    private ProductService service;
    private User user;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        service = new ProductService(ticketRepository, offerMatchClient, favoriteStoreService,
                followedProductRepository, habitualProductRepository);
        user = new User();
        user.setId(1L);
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(offerMatchClient.matchProducts(any(), anyBoolean(), any())).thenAnswer(call -> {
            List<ProductQuery> queries = call.getArgument(0);
            return Stream.generate(OfferMatch::none).limit(queries.size()).toList();
        });
    }

    private void added(String... eans) {
        when(habitualProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(
                Stream.of(eans).map(ean -> HabitualProduct.builder().ean(ean).description(DESC).build()).toList());
    }

    private Ticket ticket(int daysAgo, TicketItem... lines) {
        Ticket ticket = new Ticket();
        ticket.setId(nextId++);
        ticket.setStatus(TicketStatus.PROCESSED);
        ticket.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        ticket.setItems(new ArrayList<>(List.of(lines)));
        return ticket;
    }

    private static TicketItem line(String barcode, String description) {
        TicketItem line = new TicketItem();
        line.setBarcode(barcode);
        line.setDescription(description);
        line.setUnitPrice(BigDecimal.TEN);
        return line;
    }

    private void tickets(Ticket... tickets) {
        when(ticketRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(tickets));
    }

    @Test
    @DisplayName("sin tickets ni productos sumados no consulta ofertas")
    void nothingAtAll() {
        assertEquals(List.of(), service.getRecurringProducts(user, null));
        verifyNoInteractions(offerMatchClient);
    }

    @Test
    @DisplayName("un producto sumado aparece aunque el usuario no tenga tickets")
    void addedWithoutTickets() {
        added(EAN);
        List<RecurringProductResponse> result = service.getRecurringProducts(user, null);

        assertEquals(1, result.size());
        RecurringProductResponse product = result.get(0);
        assertEquals(DESC, product.getDescription());
        assertEquals(EAN, product.getBarcode());
        assertTrue(product.isAddedToHabitual());
        assertEquals(0, product.getTicketCount());
        assertFalse(product.isInReferenceTicket());
    }

    @Test
    @DisplayName("se le consultan ofertas como a cualquier otro")
    @SuppressWarnings("unchecked")
    void addedGetsOffers() {
        added(EAN);
        service.getRecurringProducts(user, null);

        ArgumentCaptor<List<ProductQuery>> queries = ArgumentCaptor.forClass(List.class);
        verify(offerMatchClient).matchProducts(queries.capture(), anyBoolean(), any());
        assertEquals(List.of(new ProductQuery(DESC, EAN)), queries.getValue());
    }

    @Test
    @DisplayName("sumado y detectado en tickets es un solo producto, aunque el EAN cambie de formato")
    void noDuplicates() {
        added(EAN);
        tickets(ticket(1, line(EAN_14, "GASEOSA")), ticket(10, line(EAN_14, "GASEOSA")));

        List<RecurringProductResponse> result = service.getRecurringProducts(user, null);
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getTicketCount());
        assertTrue(result.get(0).isAddedToHabitual());
    }

    @Test
    @DisplayName("lo detectado que nadie sumó no viene marcado")
    void detectedIsNotAdded() {
        added(OTHER_EAN);
        tickets(ticket(1, line(EAN, DESC)), ticket(10, line(EAN, DESC)));

        RecurringProductResponse detected = service.getRecurringProducts(user, null).stream()
                .filter(p -> EAN.equals(p.getBarcode()))
                .findFirst().orElseThrow();
        assertFalse(detected.isAddedToHabitual());
    }

    @Test
    @DisplayName("comprado una sola vez conserva sus datos de esa compra")
    void addedAndBoughtOnce() {
        added(EAN);
        tickets(ticket(1, line(OTHER_EAN, "Otro")), ticket(10, line(EAN, DESC)));

        RecurringProductResponse product = service.getRecurringProducts(user, null).get(0);
        assertEquals(1, product.getTicketCount());
        assertEquals(BigDecimal.TEN, product.getLastPaidPrice());
        assertNotNull(product.getLastPaidAt());
    }

    @Test
    @DisplayName("falta en el ticket de referencia si no lo trae")
    void missingFromReference() {
        added(EAN);
        tickets(ticket(1, line(OTHER_EAN, "Otro")), ticket(10, line(EAN, DESC)));
        assertFalse(service.getRecurringProducts(user, null).get(0).isInReferenceTicket());
    }

    @Test
    @DisplayName("está en el ticket de referencia aunque la línea no tenga código")
    void inReferenceByName() {
        added(EAN);
        tickets(ticket(1, line(null, "GASEOSA COCA COLA 2.25 L")));
        assertTrue(service.getRecurringProducts(user, null).get(0).isInReferenceTicket());
    }

    @Test
    @DisplayName("está en un ticket pedido por id aunque ese ticket quede fuera de la ventana de 90 días")
    void inReferenceOutsideTheWindow() {
        added(EAN);
        Ticket recent = ticket(1, line(OTHER_EAN, "Otro"));
        Ticket old = ticket(200, line(EAN_14, "GASEOSA"));
        tickets(recent, old);

        RecurringProductResponse product = service.getRecurringProducts(user, old.getId()).get(0);
        assertTrue(product.isInReferenceTicket());
        assertEquals(0, product.getTicketCount());
    }

    @Test
    @DisplayName("los sumados no entran en el tope de los detectados")
    void addedSurviveTheCap() {
        added(EAN);
        TicketItem[] many = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> line("779" + String.format("%010d", i + 1), "Producto " + i))
                .toArray(TicketItem[]::new);
        tickets(ticket(1, many), ticket(10, many));

        List<RecurringProductResponse> result = service.getRecurringProducts(user, null);
        assertEquals(21, result.size());
        assertTrue(result.stream().anyMatch(RecurringProductResponse::isAddedToHabitual));
    }
}
