package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.dto.response.SavingsReportResponse.ProductSavings;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private static final int MAX_RECURRING_PRODUCTS = 20;

    private final SavingsService savingsService;
    private final OfferMatchClient offerMatchClient;

    public List<RecurringProductResponse> getRecurringProducts(User user) {
        List<ProductSavings> topProducts = savingsService.getTopProducts(user, MAX_RECURRING_PRODUCTS);
        if (topProducts.isEmpty()) return List.of();

        List<ProductQuery> queries = topProducts.stream()
                .map(p -> new ProductQuery(p.getDescription(), p.getBarcode()))
                .toList();
        List<OfferMatch> matches = offerMatchClient.matchProducts(queries);

        return IntStream.range(0, topProducts.size())
                .mapToObj(i -> {
                    ProductSavings p = topProducts.get(i);
                    OfferMatch match = i < matches.size() ? matches.get(i) : OfferMatch.none();
                    return RecurringProductResponse.builder()
                            .description(p.getDescription())
                            .barcode(p.getBarcode())
                            .category(p.getCategory())
                            .purchaseCount(p.getPurchaseCount())
                            .totalDiscounts(p.getTotalDiscounts())
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
}
