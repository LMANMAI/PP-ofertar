package ar.edu.ofertAR.service;

import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The seam between two photos of one receipt. The user cannot frame the second
 * shot exactly where the first ended, so the join has to be worked out from
 * the items themselves.
 */
class TicketProcessingServiceOverlapTest {

    private static OcrItem item(String description, String price) {
        return new OcrItem(description, description, new BigDecimal(price),
                new BigDecimal(price), "", BigDecimal.ONE, "Almacen", null, null);
    }

    private static OcrItem item(String description, String price, String quantity) {
        return new OcrItem(description, description, new BigDecimal(price),
                new BigDecimal(price), "", new BigDecimal(quantity), "Almacen", null, null);
    }

    @Test
    @DisplayName("the tail of one photo reappearing at the head of the next is counted once")
    void dropsRepeatedTail() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"),
                item("ACEITE NATURA 900ML", "3890.00"));
        List<OcrItem> second = List.of(
                item("FIDEOS MATARAZZO", "1150.00"),
                item("ACEITE NATURA 900ML", "3890.00"),
                item("LECHE LA SERENISIMA", "1780.00"));

        assertEquals(2, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("photos that do not overlap are concatenated whole")
    void keepsEverythingWhenThereIsNoOverlap() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));
        List<OcrItem> second = List.of(
                item("LECHE LA SERENISIMA", "1780.00"),
                item("PAN LACTAL BIMBO", "2450.00"));

        assertEquals(0, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("the same line transcribed differently across photos still matches")
    void toleratesPunctuationAndCaseDrift() {
        List<OcrItem> first = List.of(item("COCA COLA 2.25L", "3200.00"));
        List<OcrItem> second = List.of(
                item("coca-cola 2,25 L", "3200.00"),
                item("PAN LACTAL BIMBO", "2450.00"));

        assertEquals(1, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("the longest overlap wins, not the first one that happens to match")
    void prefersTheLongestOverlap() {
        // "AGUA MINERAL" appears twice in the first photo, so a naive scan
        // could settle for a 1-item overlap and keep three duplicated lines.
        List<OcrItem> first = List.of(
                item("AGUA MINERAL", "900.00"),
                item("GALLETITAS OREO", "1600.00"),
                item("AGUA MINERAL", "900.00"),
                item("GALLETITAS OREO", "1600.00"),
                item("QUESO CREMOSO", "5400.00"));
        List<OcrItem> second = List.of(
                item("AGUA MINERAL", "900.00"),
                item("GALLETITAS OREO", "1600.00"),
                item("QUESO CREMOSO", "5400.00"),
                item("JABON EN POLVO", "7800.00"));

        assertEquals(3, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("a line repeated legitimately on one page is not deduplicated")
    void keepsGenuineRepeatsWithinAPage() {
        // Both photos read the same two-line purchase of the same product.
        // Only the seam is collapsed; the pair inside the page survives.
        List<OcrItem> first = List.of(
                item("YOGUR FIRME FRUTILLA", "1200.00"),
                item("YOGUR FIRME FRUTILLA", "1200.00"),
                item("MANTECA SANCOR", "2300.00"));
        List<OcrItem> second = List.of(
                item("MANTECA SANCOR", "2300.00"),
                item("HARINA 0000", "1050.00"));

        assertEquals(1, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("same description at a different price is a different line")
    void priceDiscriminatesEqualDescriptions() {
        List<OcrItem> first = List.of(item("BANANA", "1500.00"));
        List<OcrItem> second = List.of(item("BANANA", "2300.00"));

        assertEquals(0, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("a weighed line matches even when the two photos read the weight differently")
    void ignoresQuantityDrift() {
        List<OcrItem> first = List.of(item("PALETA COCIDA", "3120.00", "0.52"));
        List<OcrItem> second = List.of(
                item("PALETA COCIDA", "3120.00", "0.5"),
                item("PAN LACTAL BIMBO", "2450.00"));

        assertEquals(1, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("a photo fully contained in the previous one adds nothing")
    void handlesFullyContainedPage() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));
        List<OcrItem> second = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));

        assertEquals(2, TicketProcessingService.overlapLength(first, second));
    }

    @Test
    @DisplayName("an empty page is harmless")
    void handlesEmptyPages() {
        List<OcrItem> first = List.of(item("YERBA PLAYADITO 1KG", "4200.00"));

        assertEquals(0, TicketProcessingService.overlapLength(first, List.of()));
        assertEquals(0, TicketProcessingService.overlapLength(List.of(), first));
    }

    @Test
    @DisplayName("five photos of one receipt merge into each line exactly once, in order")
    void mergesFivePagesInOrder() {
        // The whole receipt, as it was printed.
        List<OcrItem> receipt = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"),
                item("ACEITE NATURA 900ML", "3890.00"),
                item("LECHE LA SERENISIMA", "1780.00"),
                item("PAN LACTAL BIMBO", "2450.00"),
                item("QUESO CREMOSO", "5400.00"),
                item("JABON EN POLVO", "7800.00"),
                item("PALETA COCIDA", "3120.00", "0.52"),
                item("BANANA", "1500.00"),
                item("HARINA 0000", "1050.00"));

        // Five overlapping photos of it, each repeating one or two lines of
        // the one before.
        List<List<OcrItem>> photos = List.of(
                receipt.subList(0, 3),
                receipt.subList(2, 5),
                receipt.subList(4, 7),
                receipt.subList(5, 9),
                receipt.subList(8, 10));

        assertEquals(15, photos.stream().mapToInt(List::size).sum(),
                "the photos really do overlap, otherwise this proves nothing");
        assertEquals(receipt, TicketProcessingService.mergePages(photos));
    }

    @Test
    @DisplayName("photos that miss a chunk of the receipt are still joined, gap and all")
    void concatenatesWhenPhotosLeaveAGap() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));
        List<OcrItem> second = List.of(
                item("QUESO CREMOSO", "5400.00"),
                item("JABON EN POLVO", "7800.00"));

        assertEquals(4, TicketProcessingService.mergePages(List.of(first, second)).size());
    }

    @Test
    @DisplayName("a single photo passes through untouched")
    void singlePageIsUnchanged() {
        List<OcrItem> only = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("YERBA PLAYADITO 1KG", "4200.00"));

        assertEquals(only, TicketProcessingService.mergePages(List.of(only)));
    }
}
