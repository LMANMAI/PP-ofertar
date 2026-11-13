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
import java.util.Comparator;
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
    private final PointsService pointsService;
    private final PushNotificationService pushNotificationService;

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
                        pushNotificationService.notifyTicketFailed(ticket);
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
                pushNotificationService.notifyTicketFailed(ticket);
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
        // Summing the items cannot produce the printed total while the OCR
        // reports the same line gross on one photo and net on the next, so
        // prefer what the receipt itself says. Derived values stay as the
        // fallback for a ticket photographed without its TOTAL line, and the
        // two are compared so a silent drift shows up in the log.
        OcrResult printed = printedTotals(pageResults);
        if (printed != null) {
            ticket.setSubtotal(printed.subtotal());
            ticket.setTotalDiscounts(printed.totalDiscounts());
            ticket.setTotal(printed.total());
            BigDecimal drift = printed.total().subtract(itemsTotal.subtract(itemsDiscounts)).abs();
            if (drift.compareTo(TOTAL_DRIFT_WARN) > 0) {
                log.warn("Ticket {}: el total impreso ({}) y la suma de los items ({}) difieren en {}",
                        ticket.getId(), printed.total(), itemsTotal.subtract(itemsDiscounts), drift);
            }
        } else {
            // itemsTotal is the sum of the printed line prices, i.e. the gross
            // the receipt calls its subtotal; the discounts below it are what
            // the shopper did not pay. Confirming a ticket
            // (TicketService.updateTicket) already resolves them this way, so
            // processing has to agree or the total changes the moment the user
            // edits anything.
            log.warn("Ticket {}: ninguna pagina reporto un total coherente; se deriva de los items",
                    ticket.getId());
            ticket.setSubtotal(itemsTotal);
            ticket.setTotalDiscounts(itemsDiscounts);
            ticket.setTotal(itemsTotal.subtract(itemsDiscounts));
        }
        ticket.setStatus(allItems.isEmpty() ? TicketStatus.FAILED : TicketStatus.PROCESSED);
        // Mutated, not replaced: Ticket.items is mapped with orphanRemoval, and
        // handing Hibernate a different List instance than the one it is
        // tracking makes it refuse the whole flush.
        ticket.getItems().clear();
        ticket.getItems().addAll(allItems);

        ticketRepository.save(ticket);
        log.info("Ticket {} procesado — {} items de {} paginas, super: {}",
                ticket.getId(), allItems.size(), pageResults.size(), ticket.getStoreName());

        if (ticket.getStatus() == TicketStatus.PROCESSED) {
            pointsService.onTicketProcessed(ticket);
            pushNotificationService.notifyTicketProcessed(ticket);
        } else {
            pushNotificationService.notifyTicketFailed(ticket);
        }
    }

    /** A page's three totals may disagree by this much and still be believed:
     * the model rounds its own arithmetic to the cent. */
    private static final BigDecimal TOTAL_CONSISTENCY_TOLERANCE = new BigDecimal("0.05");

    /** Above this the printed total and the item sum are reported as a drift
     * worth looking at rather than rounding noise. */
    private static final BigDecimal TOTAL_DRIFT_WARN = new BigDecimal("1.00");

    /**
     * The totals a page reports are the model's arithmetic over what that photo
     * showed, so a page holding only the middle of a receipt reports a partial
     * sum. The page that captured the printed TOTAL reports the real one, and
     * it is necessarily the largest: no partial sum of the same receipt can
     * exceed the whole. Pages whose own three numbers do not add up are
     * discarded first — that is exactly how a misread lands here.
     *
     * Returns null when no page is believable, leaving the caller to derive the
     * money from the items.
     */
    static OcrResult printedTotals(List<OcrResult> pages) {
        return pages.stream()
                .filter(p -> p.subtotal() != null && p.totalDiscounts() != null && p.total() != null)
                // Una página sin total vuelve con los ceros de la plantilla del OCR, y
                // 0 - 0 - 0 cierra: sin este filtro le ganaba a la suma de los ítems.
                .filter(p -> p.total().signum() > 0)
                .filter(p -> p.subtotal().subtract(p.totalDiscounts()).subtract(p.total()).abs()
                        .compareTo(TOTAL_CONSISTENCY_TOLERANCE) <= 0)
                .max(Comparator.comparing(OcrResult::total))
                .orElse(null);
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
            Seam seam = findSeam(merged, pageItems);
            merged.addAll(pageItems.subList(seam.skip() + seam.length(), pageItems.size()));
        }
        return merged;
    }

    /**
     * Where a new page stops repeating what is already collected: {@code skip}
     * leading lines to discard, then {@code length} lines that are the seam
     * itself. Everything after those is new.
     */
    record Seam(int skip, int length) {
    }

    /**
     * How many stray lines the head of a photo may show before the region that
     * actually continues the previous one. A long receipt curls while it is
     * held, so a strip from further up the ticket often lands above the seam
     * and the repeat does not start on line one.
     */
    private static final int MAX_HEAD_SKIP = 4;

    /**
     * With nothing skipped, one repeated line is enough to call it a seam.
     * Once lines are being discarded from the head of the new page, demand a
     * longer anchor: listing a purchase twice is a bad total, dropping one is
     * a purchase the user never sees again.
     */
    private static final int MIN_ANCHOR_WHEN_SKIPPING = 2;

    /**
     * Largest run of lines that closes the join, allowing the repeat to start
     * a little way into {@code next}. Anything above the seam in a later photo
     * is by construction earlier on the receipt, so it is already collected
     * and is dropped with the seam.
     */
    static Seam findSeam(List<OcrItem> collected, List<OcrItem> next) {
        Seam best = new Seam(0, 0);
        int maxSkip = Math.min(MAX_HEAD_SKIP, next.size());
        for (int skip = 0; skip <= maxSkip; skip++) {
            int max = Math.min(collected.size(), next.size() - skip);
            for (int k = max; k > 0; k--) {
                // k only shrinks from here, so nothing left can beat what we have.
                if (k <= best.length()) break;
                if (skip > 0 && k < MIN_ANCHOR_WHEN_SKIPPING) break;
                if (sameLines(collected.subList(collected.size() - k, collected.size()),
                        next.subList(skip, skip + k))) {
                    best = new Seam(skip, k);
                    break;
                }
            }
        }
        Seam loose = findLooseSeam(collected, next);
        return loose.length() > best.length() ? loose : best;
    }

    /** Lo mínimo que tiene que medir una unión tolerante: con menos líneas, el
     * 80% de parecido puede ser casualidad. */
    static final int MIN_LOOSE_SEAM = 3;
    /** Qué parte de la unión tolerante tiene que coincidir línea a línea. */
    static final double LOOSE_SEAM_SHARE = 0.8;
    /** Qué tan parecidas tienen que ser dos descripciones para ser la misma línea.
     * Dos toallas que sólo cambian de color quedan por debajo (0,83). */
    static final double SIMILAR_DESCRIPTION = 0.85;

    /**
     * La unión cuando la estricta no encontró nada. El OCR transcribe distinto
     * cada foto de un mismo ticket: en una pega el código a la descripción y en
     * la otra lo deja aparte, lee "233" donde dice "213" o "LIR" donde dice "LTR".
     * Con la comparación exacta no aparecía ninguna línea repetida y el ticket
     * sumaba todo dos veces.
     *
     * Acepta un tramo de al menos {@link #MIN_LOOSE_SEAM} líneas en el que las
     * dos puntas coinciden y el {@link #LOOSE_SEAM_SHARE} de las líneas se
     * parecen, por EAN o por descripción.
     */
    static Seam findLooseSeam(List<OcrItem> collected, List<OcrItem> next) {
        Seam best = new Seam(0, 0);
        int maxSkip = Math.min(MAX_HEAD_SKIP, next.size());
        for (int skip = 0; skip <= maxSkip; skip++) {
            int max = Math.min(collected.size(), next.size() - skip);
            for (int k = max; k >= MIN_LOOSE_SEAM; k--) {
                if (k <= best.length()) break;
                if (looselySame(collected.subList(collected.size() - k, collected.size()),
                        next.subList(skip, skip + k))) {
                    best = new Seam(skip, k);
                    break;
                }
            }
        }
        return best;
    }

    private static boolean looselySame(List<OcrItem> a, List<OcrItem> b) {
        int k = a.size();
        if (!similarLine(a.get(0), b.get(0)) || !similarLine(a.get(k - 1), b.get(k - 1))) {
            return false;
        }
        int matches = 0;
        for (int i = 0; i < k; i++) {
            if (similarLine(a.get(i), b.get(i))) matches++;
        }
        return matches >= Math.ceil(k * LOOSE_SEAM_SHARE);
    }

    /** La misma línea leída en dos fotos: el mismo EAN, o casi la misma descripción. */
    static boolean similarLine(OcrItem a, OcrItem b) {
        String eanA = eanOf(a.code());
        if (eanA != null && eanA.equals(eanOf(b.code()))) {
            return true;
        }
        String da = bareDescription(a.description());
        String db = bareDescription(b.description());
        return !da.isEmpty() && !db.isEmpty() && similarity(da, db) >= SIMILAR_DESCRIPTION;
    }

    /** El EAN de lo que el OCR puso como código, que a veces trae delante el
     * código interno de la cadena ("0000640498 02800004856980"). */
    static String eanOf(String code) {
        if (code == null || code.isBlank()) return null;
        String[] parts = code.trim().split("\\s+");
        String last = parts[parts.length - 1].replaceAll("\\D", "");
        return last.length() >= 8 ? ProductKeys.eanKey(last) : null;
    }

    /** La descripción sin la cantidad delante ("2,000 POR 4292,99") ni los
     * códigos pegados detrás, sólo letras y números. */
    static String bareDescription(String description) {
        if (description == null) return "";
        String s = description.toUpperCase()
                .replaceFirst("^\\s*\\d+[.,]\\d+\\s+(POR|X)\\s+\\d+(?:[.,]\\d+)?\\s+", "")
                .replaceAll("\\b\\d{6,}\\b", "");
        return s.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 1 si son iguales, 0 si no comparten nada (distancia de Levenshtein). */
    static double similarity(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
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
        // Measured against four photos of one 42-line receipt: the same line
        // comes back with a different description on one photo ("P/USAR MAR"
        // vs "P/USAR AMA, RC"), a different price on another (1625.01 gross on
        // one, 1462.52 net on the next), but the barcode holds. Comparing
        // description and price found none of the three seams; the barcode
        // found two of them and cut the item count from 68 to 46.
        if (hasText(a.code()) && hasText(b.code())) {
            String eanA = eanOf(a.code());
            return eanA != null ? eanA.equals(eanOf(b.code())) : a.code().trim().equals(b.code().trim());
        }
        return normalise(a.description()).equals(normalise(b.description()))
                && samePrice(a.price(), b.price());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
