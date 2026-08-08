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
import org.springframework.transaction.annotation.Transactional;

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
 * Lives in its own bean rather than inside {@link TicketService} because the
 * work is submitted to an executor: it has to be invoked through a Spring
 * proxy for {@code @Transactional} to apply on the background thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketProcessingService {

    private final TicketRepository ticketRepository;
    private final OcrClient ocrClient;
    private final ExecutorService ocrExecutor;

    /** Page images already read off the request, so the upload can return. */
    public record PagePayload(byte[] bytes, String contentType) {}

    @Transactional
    public void process(Long ticketId, List<PagePayload> pages) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            // The retention limit may have deleted it while it queued.
            log.warn("Ticket {} ya no existe; se descarta su procesamiento", ticketId);
            return;
        }

        List<OcrResult> pageResults;
        try {
            pageResults = runOcr(pages);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Fallo OCR en el ticket {}: {}", ticketId, cause.getMessage());
            ticket.setStatus(TicketStatus.FAILED);
            ticketRepository.save(ticket);
            return;
        } catch (Exception e) {
            log.error("Error inesperado procesando el ticket {}: {}", ticketId, e.getMessage(), e);
            ticket.setStatus(TicketStatus.FAILED);
            ticketRepository.save(ticket);
            return;
        }

        applyResults(ticket, pageResults);
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

    private void applyResults(Ticket ticket, List<OcrResult> pageResults) {
        String mergedStoreName = null;
        String mergedTicketId = null;
        List<TicketItem> allItems = new ArrayList<>();

        for (int i = 0; i < pageResults.size(); i++) {
            OcrResult page = pageResults.get(i);
            if (mergedStoreName == null || mergedStoreName.isEmpty()) {
                mergedStoreName = page.supermarketName();
            }
            if (mergedTicketId == null || mergedTicketId.isEmpty()) {
                mergedTicketId = page.ticketId();
            }
            for (OcrItem ocrItem : page.items()) {
                allItems.add(toItem(ticket, ocrItem));
            }
            log.info("Pagina {}/{} del ticket {} procesada — {} items",
                    i + 1, pageResults.size(), ticket.getId(), page.items().size());
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
        ticket.setItems(allItems);

        ticketRepository.save(ticket);
        log.info("Ticket {} procesado — {} items de {} paginas, super: {}",
                ticket.getId(), allItems.size(), pageResults.size(), ticket.getStoreName());
    }

    private TicketItem toItem(Ticket ticket, OcrItem ocrItem) {
        int qty = Math.max(1, ocrItem.quantity());
        return TicketItem.builder()
                .ticket(ticket)
                .description(ocrItem.description())
                .rawDescription(ocrItem.rawDescription())
                .quantity(qty)
                .unitPrice(ocrItem.price().divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP))
                .originalPrice(ocrItem.originalPrice())
                .subtotal(ocrItem.price())
                .barcode(ocrItem.code())
                .category(ocrItem.category())
                .discountAmount(ocrItem.discountAmount())
                .discountDescription(ocrItem.discountDescription())
                .build();
    }
}
