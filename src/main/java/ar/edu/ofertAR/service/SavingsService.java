package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SavingsReportResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavingsService {

    private final TicketRepository ticketRepository;

    /** All-time top products for a user (no month filter), reusing the same
     * grouping logic as the monthly savings report's topProducts — used by
     * ProductService for the "productos recurrentes" feature, which cares
     * about full purchase history rather than a specific month. */
    public List<SavingsReportResponse.ProductSavings> getTopProducts(User user, int limit) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(t -> t.getStatus() == TicketStatus.PROCESSED)
                .toList();
        return buildTopProducts(tickets, limit);
    }

    public SavingsReportResponse getReport(User user, YearMonth from, YearMonth to) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(t -> t.getStatus() == TicketStatus.PROCESSED)
                .filter(t -> {
                    YearMonth ticketMonth = YearMonth.from(t.getCreatedAt());
                    if (from != null && ticketMonth.isBefore(from)) return false;
                    if (to != null && ticketMonth.isAfter(to)) return false;
                    return true;
                })
                .toList();

        return SavingsReportResponse.builder()
                .summary(buildSummary(tickets))
                .byCategory(buildByCategory(tickets))
                .byStore(buildByStore(tickets))
                .timeline(buildTimeline(tickets))
                .topProducts(buildTopProducts(tickets, 10))
                .build();
    }

    private SavingsReportResponse.Summary buildSummary(List<Ticket> tickets) {
        BigDecimal totalSavings = tickets.stream()
                .map(t -> Optional.ofNullable(t.getTotalDiscounts()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSpent = tickets.stream()
                .map(t -> Optional.ofNullable(t.getTotal()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal averageSavings = tickets.isEmpty()
                ? BigDecimal.ZERO
                : totalSavings.divide(BigDecimal.valueOf(tickets.size()), 2, RoundingMode.HALF_UP);

        return SavingsReportResponse.Summary.builder()
                .totalSavings(totalSavings)
                .totalSpent(totalSpent)
                .ticketCount(tickets.size())
                .averageSavings(averageSavings)
                .build();
    }

    private List<SavingsReportResponse.CategorySavings> buildByCategory(List<Ticket> tickets) {
        Map<String, BigDecimal> categoryDiscounts = new java.util.LinkedHashMap<>();
        Map<String, Long> categoryCounts = new java.util.LinkedHashMap<>();

        tickets.stream()
                .flatMap(t -> t.getItems().stream())
                .filter(item -> item.getCategory() != null && !item.getCategory().isBlank())
                .forEach(item -> {
                    BigDecimal discount = item.getDiscountAmount() != null
                            ? item.getDiscountAmount() : BigDecimal.ZERO;
                    categoryDiscounts.merge(item.getCategory(), discount, BigDecimal::add);
                    categoryCounts.merge(item.getCategory(), 1L, Long::sum);
                });

        return categoryDiscounts.entrySet().stream()
                .map(e -> SavingsReportResponse.CategorySavings.builder()
                        .category(e.getKey())
                        .totalDiscounts(e.getValue())
                        .itemCount(categoryCounts.get(e.getKey()))
                        .build())
                .sorted(Comparator.comparing(SavingsReportResponse.CategorySavings::getTotalDiscounts).reversed())
                .toList();
    }

    private List<SavingsReportResponse.StoreSavings> buildByStore(List<Ticket> tickets) {
        Map<String, List<Ticket>> storeMap = tickets.stream()
                .filter(t -> t.getStoreName() != null && !t.getStoreName().isBlank())
                .collect(Collectors.groupingBy(Ticket::getStoreName));

        return storeMap.entrySet().stream()
                .map(e -> {
                    BigDecimal totalDisc = e.getValue().stream()
                            .map(t -> Optional.ofNullable(t.getTotalDiscounts()).orElse(BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return SavingsReportResponse.StoreSavings.builder()
                            .storeName(e.getKey())
                            .totalDiscounts(totalDisc)
                            .ticketCount(e.getValue().size())
                            .build();
                })
                .sorted(Comparator.comparing(SavingsReportResponse.StoreSavings::getTotalDiscounts).reversed())
                .toList();
    }

    private List<SavingsReportResponse.TimelineSavings> buildTimeline(List<Ticket> tickets) {
        return tickets.stream()
                .collect(Collectors.groupingBy(t -> YearMonth.from(t.getCreatedAt())))
                .entrySet().stream()
                .map(e -> {
                    BigDecimal totalDisc = e.getValue().stream()
                            .map(t -> Optional.ofNullable(t.getTotalDiscounts()).orElse(BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return SavingsReportResponse.TimelineSavings.builder()
                            .period(e.getKey().toString())
                            .totalDiscounts(totalDisc)
                            .ticketCount(e.getValue().size())
                            .build();
                })
                .sorted(Comparator.comparing(SavingsReportResponse.TimelineSavings::getPeriod))
                .toList();
    }

    private List<SavingsReportResponse.ProductSavings> buildTopProducts(List<Ticket> tickets, int limit) {
        Map<String, List<TicketItem>> productMap = tickets.stream()
                .flatMap(t -> t.getItems().stream())
                .collect(Collectors.groupingBy(ProductKeys::keyOf));

        return productMap.entrySet().stream()
                .map(e -> {
                    TicketItem sample = e.getValue().get(0);
                    BigDecimal totalDisc = e.getValue().stream()
                            .map(item -> item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return SavingsReportResponse.ProductSavings.builder()
                            .description(sample.getDescription())
                            .barcode(sample.getBarcode())
                            .category(sample.getCategory())
                            .purchaseCount(e.getValue().size())
                            .totalDiscounts(totalDisc)
                            .build();
                })
                .sorted(Comparator.comparing(SavingsReportResponse.ProductSavings::getPurchaseCount).reversed())
                .limit(limit)
                .toList();
    }
}
