package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.HabitualProduct;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.HabitualProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private static final int MAX_RECURRING_PRODUCTS = 20;

    /** Consumo Inteligente: un producto es recurrente si aparece en al menos
     * {@code minTickets} tickets de los últimos {@code windowDays} días. Una
     * compra aislada, como una tele, nunca entra. La app explica esta regla en
     * pantalla ({@code RECURRING_RULE_TEXT}): si cambian estos valores, hay que
     * cambiar también ese texto. */
    @Value("${recurring.window-days:90}")
    private int windowDays = 90;

    @Value("${recurring.min-tickets:2}")
    private int minTickets = 2;

    private final TicketRepository ticketRepository;
    private final OfferMatchClient offerMatchClient;
    private final FavoriteStoreService favoriteStoreService;
    private final FollowedProductRepository followedProductRepository;
    private final HabitualProductRepository habitualProductRepository;

    /**
     * La compra habitual: lo detectado en los tickets más lo que el usuario sumó
     * desde el escáner, cada producto una sola vez. Lo sumado va después y no
     * entra en {@link #MAX_RECURRING_PRODUCTS}: el usuario lo pidió por nombre.
     */
    public List<RecurringProductResponse> getRecurringProducts(User user, Long ticketId) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(t -> t.getStatus() == TicketStatus.PROCESSED)
                .toList();
        List<HabitualProduct> added = habitualProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        if (tickets.isEmpty() && added.isEmpty()) return List.of();

        Ticket reference = tickets.isEmpty() ? null : resolveReferenceTicket(tickets, ticketId);
        List<TicketItem> referenceItems = reference == null ? List.of() : reference.getItems();

        // El ticket de referencia se busca entre todos, porque el cliente puede
        // pedir uno viejo; la ventana sólo decide qué cuenta como recurrente.
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowDays);
        List<Ticket> inWindow = tickets.stream()
                .filter(t -> t.getCreatedAt().isAfter(cutoff))
                .toList();

        List<ProductGroup> purchases = groupPurchases(inWindow);
        List<ProductGroup> groups = new ArrayList<>(purchases.stream()
                .filter(g -> g.ticketCount >= minTickets)
                .toList());
        groups.sort(Comparator
                .comparingLong((ProductGroup g) -> g.ticketCount)
                .thenComparingLong(g -> g.purchaseCount)
                .reversed());
        List<ProductGroup> top = groups.stream().limit(MAX_RECURRING_PRODUCTS).toList();

        List<HabitualEntry> entries = new ArrayList<>(top.stream()
                .map(g -> new HabitualEntry(g, addedMatching(g, added)))
                .toList());
        for (HabitualProduct product : added) {
            boolean detected = entries.stream().anyMatch(e -> e.added().filter(product::equals).isPresent());
            if (!detected) {
                entries.add(new HabitualEntry(purchaseOf(product, purchases), Optional.of(product)));
            }
        }

        // Only surface offers from chains the user actually shops at; an empty
        // favourites list means they haven't chosen, so nothing gets filtered.
        List<OfferMatch> matches = offerMatchClient.matchProducts(
                entries.stream().map(HabitualEntry::query).toList(),
                user.isAlternativeBrandsEnabled(),
                favoriteStoreService.getFavoriteChainSlugs(user));

        // Una sola query por request, no una por producto.
        List<String> followedEans = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream()
                .map(FollowedProduct::getEan)
                .toList();

        return IntStream.range(0, entries.size())
                .mapToObj(i -> {
                    HabitualEntry entry = entries.get(i);
                    OfferMatch match = i < matches.size() ? matches.get(i) : OfferMatch.none();
                    String barcode = entry.barcode();
                    return RecurringProductResponse.builder()
                            .description(entry.description())
                            .barcode(barcode)
                            .category(entry.purchase() == null ? null : entry.purchase().category)
                            .purchaseCount(entry.purchase() == null ? 0 : entry.purchase().purchaseCount)
                            .ticketCount(entry.purchase() == null ? 0 : entry.purchase().ticketCount)
                            .inReferenceTicket(entry.isIn(referenceItems))
                            .followed(followedEans.stream().anyMatch(ean -> ProductKeys.sameEan(ean, barcode)))
                            .addedToHabitual(entry.added().isPresent())
                            .totalDiscounts(entry.purchase() == null ? BigDecimal.ZERO : entry.purchase().totalDiscounts)
                            .lastPaidPrice(entry.purchase() == null ? null : entry.purchase().lastPaidPrice)
                            .lastPaidAt(entry.purchase() == null ? null : entry.purchase().lastPaidAt)
                            .campaignOffers(OfferMatchMapper.campaignOffers(match))
                            .bestOffer(OfferMatchMapper.bestOffer(match))
                            .promoMechanics(OfferMatchMapper.promoMechanics(match))
                            .alternativeOffers(OfferMatchMapper.alternativeOffers(match))
                            .build();
                })
                .toList();
    }

    /** El producto sumado que corresponde a lo detectado en los tickets, si hay uno. */
    private static Optional<HabitualProduct> addedMatching(ProductGroup group, List<HabitualProduct> added) {
        return added.stream().filter(p -> HabitualProductService.isSameProduct(p, group.sample)).findFirst();
    }

    /** Lo comprado de un producto sumado dentro de la ventana, o null si no aparece en ningún ticket. */
    private static ProductGroup purchaseOf(HabitualProduct product, List<ProductGroup> purchases) {
        return purchases.stream()
                .filter(g -> HabitualProductService.isSameProduct(product, g.sample))
                .findFirst()
                .orElse(null);
    }

    /** Un producto de la compra habitual: comprado, sumado desde el escáner, o las dos cosas. */
    private record HabitualEntry(ProductGroup purchase, Optional<HabitualProduct> added) {

        /** El código y el nombre sumados mandan: vienen del catálogo, no del OCR. */
        private String description() {
            return added.map(HabitualProduct::getDescription).orElseGet(() -> purchase.description);
        }

        private String barcode() {
            return added.map(HabitualProduct::getEan).orElseGet(() -> purchase.barcode);
        }

        private ProductQuery query() {
            return new ProductQuery(description(), barcode());
        }

        private boolean isIn(List<TicketItem> ticketItems) {
            return ticketItems.stream().anyMatch(item ->
                    added.filter(p -> HabitualProductService.isSameProduct(p, item)).isPresent()
                            || (purchase != null && ProductKeys.sameProduct(item, purchase.sample)));
        }
    }

    private Ticket resolveReferenceTicket(List<Ticket> processedDesc, Long ticketId) {
        if (ticketId == null) return processedDesc.get(0);
        return processedDesc.stream()
                .filter(t -> t.getId().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));
    }

    private List<ProductGroup> groupPurchases(List<Ticket> tickets) {
        Map<String, ProductGroup> byKey = new LinkedHashMap<>();
        // `tickets` arrives newest-first, so the item that creates a group is
        // the most recent purchase of that product — which is what makes the
        // constructor's unit price the "last paid" one.
        for (Ticket ticket : tickets) {
            for (TicketItem item : ticket.getItems()) {
                ProductGroup group = byKey.computeIfAbsent(
                        ProductKeys.keyOf(item), k -> new ProductGroup(item, ticket));
                group.purchaseCount += 1;
                group.ticketIds.add(ticket.getId());
                if (item.getDiscountAmount() != null) {
                    group.totalDiscounts = group.totalDiscounts.add(item.getDiscountAmount());
                }
            }
        }
        List<ProductGroup> groups = new ArrayList<>(byKey.values());
        groups.forEach(g -> g.ticketCount = g.ticketIds.size());
        return groups;
    }

    private static final class ProductGroup {
        private final TicketItem sample;
        private final String description;
        private final String barcode;
        private final String category;
        private final java.util.Set<Long> ticketIds = new java.util.HashSet<>();
        private final BigDecimal lastPaidPrice;
        private final LocalDateTime lastPaidAt;
        private long purchaseCount;
        private long ticketCount;
        private BigDecimal totalDiscounts = BigDecimal.ZERO;

        private ProductGroup(TicketItem sample, Ticket ticket) {
            this.sample = sample;
            this.description = sample.getDescription();
            this.barcode = sample.getBarcode();
            this.category = sample.getCategory();
            this.lastPaidPrice = sample.getUnitPrice();
            this.lastPaidAt = ticket.getCreatedAt();
        }
    }
}
