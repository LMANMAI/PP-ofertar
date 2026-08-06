package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private static final int MAX_RECURRING_PRODUCTS = 20;

    private final TicketRepository ticketRepository;
    private final OfferMatchClient offerMatchClient;

    public List<RecurringProductResponse> getRecurringProducts(User user, Long ticketId) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(t -> t.getStatus() == TicketStatus.PROCESSED)
                .toList();
        if (tickets.isEmpty()) return List.of();

        Ticket reference = resolveReferenceTicket(tickets, ticketId);
        List<TicketItem> referenceItems = reference == null ? List.of() : reference.getItems();

        List<ProductGroup> groups = groupPurchases(tickets);
        groups.sort(Comparator
                .comparingLong((ProductGroup g) -> g.ticketCount)
                .thenComparingLong(g -> g.purchaseCount)
                .reversed());
        List<ProductGroup> top = groups.stream().limit(MAX_RECURRING_PRODUCTS).toList();

        List<OfferMatch> matches = offerMatchClient.matchProducts(
                top.stream().map(g -> new ProductQuery(g.description, g.barcode)).toList());

        return IntStream.range(0, top.size())
                .mapToObj(i -> {
                    ProductGroup g = top.get(i);
                    OfferMatch match = i < matches.size() ? matches.get(i) : OfferMatch.none();
                    boolean inReference = referenceItems.stream()
                            .anyMatch(item -> ProductKeys.sameProduct(item, g.sample));
                    return RecurringProductResponse.builder()
                            .description(g.description)
                            .barcode(g.barcode)
                            .category(g.category)
                            .purchaseCount(g.purchaseCount)
                            .ticketCount(g.ticketCount)
                            .inReferenceTicket(inReference)
                            .totalDiscounts(g.totalDiscounts)
                            .bestOffer(match.hasOffer()
                                    ? RecurringProductResponse.BestOffer.builder()
                                            .retailerName(match.retailerName())
                                            .price(match.price())
                                            .listPrice(match.listPrice())
                                            .discountPct(match.discountPct())
                                            .promoLabel(match.promoLabel())
                                            .build()
                                    : null)
                            .build();
                })
                .toList();
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
        for (Ticket ticket : tickets) {
            for (TicketItem item : ticket.getItems()) {
                ProductGroup group = byKey.computeIfAbsent(ProductKeys.keyOf(item), k -> new ProductGroup(item));
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
        private long purchaseCount;
        private long ticketCount;
        private BigDecimal totalDiscounts = BigDecimal.ZERO;

        private ProductGroup(TicketItem sample) {
            this.sample = sample;
            this.description = sample.getDescription();
            this.barcode = sample.getBarcode();
            this.category = sample.getCategory();
        }
    }
}
