package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final FavoriteStoreService favoriteStoreService;
    private final FollowedProductRepository followedProductRepository;

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

        // Only surface offers from chains the user actually shops at; an empty
        // favourites list means they haven't chosen, so nothing gets filtered.
        List<OfferMatch> matches = offerMatchClient.matchProducts(
                top.stream().map(g -> new ProductQuery(g.description, g.barcode)).toList(),
                user.isAlternativeBrandsEnabled(),
                favoriteStoreService.getFavoriteChainSlugs(user));

        // Una sola query por request, no una por producto.
        List<String> followedEans = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream()
                .map(FollowedProduct::getEan)
                .toList();

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
                            .followed(followedEans.stream().anyMatch(ean -> ProductKeys.sameEan(ean, g.barcode)))
                            .totalDiscounts(g.totalDiscounts)
                            .lastPaidPrice(g.lastPaidPrice)
                            .lastPaidAt(g.lastPaidAt)
                            .campaignOffers(OfferMatchMapper.campaignOffers(match))
                            .bestOffer(OfferMatchMapper.bestOffer(match))
                            .promoMechanics(OfferMatchMapper.promoMechanics(match))
                            .alternativeOffers(OfferMatchMapper.alternativeOffers(match))
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
