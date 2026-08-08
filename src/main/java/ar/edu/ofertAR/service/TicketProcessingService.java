package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.ocr.OcrClient;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Turns an uploaded ticket's images into items, off the request thread.
 *
 * Transactions are opened explicitly rather than declared with
 * {@code @Transactional}: the OCR call has to stay outside one, and a failure
 * has to be recorded in a transaction of its own, since the one that failed is
 * rolling back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketProcessingService {

    private final TicketRepository ticketRepository;
    private final OcrClient ocrClient;
    private final ExecutorService ocrExecutor;
    private final TransactionTemplate transactionTemplate;

    /** Page images already read off the request, so the upload can return. */
    public record PagePayload(byte[] bytes, String contentType) {}

    public void process(Long ticketId, List<PagePayload> pages) {
        List<OcrResult> pageResults;
        try {
            // Outside any transaction on purpose: the OCR round trip runs for
            // as long as the slowest page takes, and holding a pooled database
            // connection open all that time starves the request threads.
            pageResults = runOcr(pages);
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null
                    ? e.getCause() : e;
            log.error("Fallo OCR en el ticket {}: {}", ticketId, cause.getMessage(), cause);
            markFailed(ticketId);
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> applyResults(ticketId, pageResults));
        } catch (Exception e) {
            // Whatever the commit itself throws lands here too. Marking the
            // ticket FAILED used to happen inside this very transaction, so a
            // failure at flush time rolled the status back along with it and
            // stranded the ticket in PENDING with nothing left to retry it.
            log.error("No se pudieron guardar los resultados del ticket {}: {}",
                    ticketId, e.getMessage(), e);
            markFailed(ticketId);
        }
    }

    /** Its own transaction, so it survives the rollback that caused it. */
    private void markFailed(Long ticketId) {
        try {
            transactionTemplate.executeWithoutResult(status -> ticketRepository.findById(ticketId)
                    .ifPresent(ticket -> {
                        ticket.setStatus(TicketStatus.FAILED);
                        ticketRepository.save(ticket);
                    }));
        } catch (Exception e) {
            log.error("Tampoco se pudo marcar como fallido el ticket {}: {}",
                    ticketId, e.getMessage(), e);
        }
    }

    private List<OcrResult> runOcr(List<PagePayload> pages) {
        // Pages are independent calls, so run them concurrently: a 5-page
        // ticket costs the slowest page instead of the sum of all of them.
        List<CompletableFuture<OcrResult>> futures = pages.stream()
                .map(p -> CompletableFuture.supplyAsync(
                        () -> ocrClient.processTicket(p.bytes(), p.contentType()), ocrExecutor))
                .toList();

        List<OcrResult> results = new ArrayList<>();
        // join() preserves page order, which matters because the item list is
        // shown to the user in the order it was printed.
        for (CompletableFuture<OcrResult> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    private void applyResults(Long ticketId, List<OcrResult> pageResults) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            // The retention limit may have deleted it while it queued.
            log.warn("Ticket {} ya no existe; se descarta su procesamiento", ticketId);
            return;
        }

        String mergedStoreName = null;
        String mergedTicketId = null;
        List<TicketItem> allItems = new ArrayList<>();

        List<List<OcrItem>> pages = new ArrayList<>();

        for (int i = 0; i < pageResults.size(); i++) {
            OcrResult page = pageResults.get(i);
            if (mergedStoreName == null || mergedStoreName.isEmpty()) {
                mergedStoreName = page.supermarketName();
            }
            if (mergedTicketId == null || mergedTicketId.isEmpty()) {
                mergedTicketId = page.ticketId();
            }
            pages.add(page.items() != null ? page.items() : List.of());
            log.info("Pagina {}/{} del ticket {} procesada — {} items",
                    i + 1, pageResults.size(), ticket.getId(), pages.get(i).size());
        }

        List<OcrItem> mergedItems = mergePages(pages);
        int dropped = pages.stream().mapToInt(List::size).sum() - mergedItems.size();
        if (dropped > 0) {
            log.info("Ticket {}: {} items descartados por solape entre fotos", ticket.getId(), dropped);
        }

        for (OcrItem ocrItem : mergedItems) {
            allItems.add(toItem(ticket, ocrItem));
        }

        if (mergedTicketId != null && !mergedTicketId.isEmpty()) {
            Optional<Ticket> existing = ticketRepository.findByUserIdAndTicketIdAndStatus(
                    ticket.getUser().getId(), mergedTicketId, TicketStatus.PROCESSED);
            if (existing.isPresent() && !existing.get().getId().equals(ticket.getId())) {
                log.info("Ticket {} duplicado de {} (ticketId={}), se marca como fallido",
                        ticket.getId(), existing.get().getId(), mergedTicketId);
                ticket.setStatus(TicketStatus.FAILED);
                ticketRepository.save(ticket);
                return;
            }
        }

        // The per-page totals come from arithmetic the model did on its own,
        // and that is the least reliable field it returns. The items are
        // individually readable off the receipt, so derive the money from them.
        BigDecimal itemsTotal = allItems.stream()
                .map(it -> it.getSubtotal() != null ? it.getSubtotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal itemsDiscounts = allItems.stream()
                .map(it -> it.getDiscountAmount() != null ? it.getDiscountAmount().abs() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ticket.setStoreName(mergedStoreName);
        ticket.setTicketId(mergedTicketId);
        ticket.setTotal(itemsTotal);
        ticket.setTotalDiscounts(itemsDiscounts);
        ticket.setSubtotal(itemsTotal.add(itemsDiscounts));
        ticket.setStatus(allItems.isEmpty() ? TicketStatus.FAILED : TicketStatus.PROCESSED);
        // Mutated, not replaced: Ticket.items is mapped with orphanRemoval, and
        // handing Hibernate a different List instance than the one it is
        // tracking makes it refuse the whole flush.
        ticket.getItems().clear();
        ticket.getItems().addAll(allItems);

        ticketRepository.save(ticket);
        log.info("Ticket {} procesado — {} items de {} paginas, super: {}",
                ticket.getId(), allItems.size(), pageResults.size(), ticket.getStoreName());
    }

    /**
     * Joins the pages of one ticket into a single item list.
     *
     * Nobody frames the second photo exactly where the first one ended, so the
     * last lines of a page normally reappear at the top of the next. Appending
     * blindly listed them twice and — since the ticket total is the sum of its
     * items — overcharged the ticket by whatever those lines cost.
     */
    static List<OcrItem> mergePages(List<List<OcrItem>> pages) {
        List<OcrItem> merged = new ArrayList<>();
        for (List<OcrItem> pageItems : pages) {
            int repeated = overlapLength(merged, pageItems);
            merged.addAll(pageItems.subList(repeated, pageItems.size()));
        }
        return merged;
    }

    /**
     * How many of {@code next}'s leading items are a repeat of what has already
     * been collected — i.e. the largest {@code k} where the last {@code k} of
     * {@code collected} are the same lines as the first {@code k} of
     * {@code next}.
     *
     * Deliberately anchored to the seam instead of deduplicating the whole
     * list: a receipt can legitimately print the same product on two separate
     * lines, and dropping those would delete a real purchase. Photo overlap,
     * on the other hand, is always contiguous and always at the join.
     *
     * Returns 0 when nothing lines up, which degrades to plain concatenation.
     */
    static int overlapLength(List<OcrItem> collected, List<OcrItem> next) {
        int max = Math.min(collected.size(), next.size());
        for (int k = max; k > 0; k--) {
            if (sameLines(collected.subList(collected.size() - k, collected.size()),
                    next.subList(0, k))) {
                return k;
            }
        }
        return 0;
    }

    private static boolean sameLines(List<OcrItem> a, List<OcrItem> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!sameLine(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Description plus price. Quantity is left out on purpose: the same
     * weighed line often reads 0.52 on one photo and 0.5 on the other, and
     * price is the field the model gets right most consistently.
     */
    private static boolean sameLine(OcrItem a, OcrItem b) {
        return normalise(a.description()).equals(normalise(b.description()))
                && samePrice(a.price(), b.price());
    }

    /** Two photos of one line rarely transcribe identically — "2.25L" on one
     * and "2,25L" on the other — so compare only the letters and digits. */
    private static String normalise(String value) {
        return value == null ? "" : value.toUpperCase().replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private TicketItem toItem(Ticket ticket, OcrItem ocrItem) {
        // OcrClient already floors this at one, but the divide below is
        // unforgiving and a zero here would take down the whole ticket.
        BigDecimal qty = ocrItem.quantity() != null && ocrItem.quantity().signum() > 0
                ? ocrItem.quantity()
                : BigDecimal.ONE;
        return TicketItem.builder()
                .ticket(ticket)
                .description(ocrItem.description())
                .rawDescription(ocrItem.rawDescription())
                .quantity(qty)
                .unitPrice(ocrItem.price().divide(qty, 2, RoundingMode.HALF_UP))
                .originalPrice(ocrItem.originalPrice())
                .subtotal(ocrItem.price())
                .barcode(ocrItem.code())
                .category(ocrItem.category())
                .discountAmount(ocrItem.discountAmount())
                .discountDescription(ocrItem.discountDescription())
                .build();
    }
}
