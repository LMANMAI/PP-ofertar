package ar.edu.ofertAR.service;

import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    @DisplayName("a curled fragment above the seam does not defeat the overlap")
    void dropsRepeatedTailWhenNextStartsWithAnEarlierFragment() {
        // Photographing a long receipt, the paper curls: the top of the fourth
        // photo shows a strip from further up the ticket (BOLSA CAMISETA)
        // before the region that actually continues the third photo. The
        // overlap is therefore not the first line of the new page.
        List<OcrItem> third = List.of(
                item("BOLSA CAMISETA 60X70 VER", "569.69"),
                item("MOZZ CILINDRO 4 HNOS X 5", "5760.87"),
                item("PIMENTON ALICANTE PQX50G", "1625.01"),
                item("MAYO NATURA DPX500CC", "2728.26"));
        List<OcrItem> fourth = List.of(
                item("BOLSA CAMISETA 60X70 VER", "569.69"),
                item("PIMENTON ALICANTE PQX50G", "1625.01"),
                item("MAYO NATURA DPX500CC", "2728.26"),
                item("CAFE INST CRUZEIRO LIOFI", "12000.01"));

        List<OcrItem> merged = TicketProcessingService.mergePages(List.of(third, fourth));

        assertEquals(
                List.of("BOLSA CAMISETA 60X70 VER", "MOZZ CILINDRO 4 HNOS X 5",
                        "PIMENTON ALICANTE PQX50G", "MAYO NATURA DPX500CC",
                        "CAFE INST CRUZEIRO LIOFI"),
                merged.stream().map(OcrItem::description).toList());
    }

    @Test
    @DisplayName("a genuinely new page is not swallowed by the skip window")
    void keepsEveryItemWhenPagesDoNotOverlap() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));
        List<OcrItem> second = List.of(
                item("LECHE LA SERENISIMA", "1780.00"),
                item("PAN LACTAL BIMBO", "2300.00"));

        List<OcrItem> merged = TicketProcessingService.mergePages(List.of(first, second));

        assertEquals(4, merged.size());
    }

    @Test
    @DisplayName("one coincidental line above the seam is not enough to discard a real item")
    void doesNotSkipAheadOnASingleMatch() {
        // The new page opens with a genuine purchase (PAN) and only then
        // repeats a line. Treating that lone repeat as the seam would drop the
        // bread from the ticket entirely.
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("LECHE LA SERENISIMA", "1780.00"));
        List<OcrItem> second = List.of(
                item("PAN LACTAL BIMBO", "2300.00"),
                item("LECHE LA SERENISIMA", "1780.00"),
                item("QUESO CREMOSO", "4000.00"));

        List<OcrItem> merged = TicketProcessingService.mergePages(List.of(first, second));

        assertEquals(
                List.of("YERBA PLAYADITO 1KG", "LECHE LA SERENISIMA", "PAN LACTAL BIMBO",
                        "LECHE LA SERENISIMA", "QUESO CREMOSO"),
                merged.stream().map(OcrItem::description).toList());
    }

    private static OcrItem coded(String description, String price, String code) {
        return new OcrItem(description, description, new BigDecimal(price),
                new BigDecimal(price), code, BigDecimal.ONE, "Almacen", null, null);
    }

    private static OcrResult page(String subtotal, String discounts, String total) {
        return new OcrResult("Supermercado", "0001", new BigDecimal(subtotal),
                new BigDecimal(discounts), new BigDecimal(total), List.of());
    }

    @Test
    @DisplayName("the barcode closes a seam the price disagrees about")
    void matchesOnBarcodeWhenThePriceDiffers() {
        // Real output from two photos of the same receipt: the third reports
        // these lines gross, the fourth reports them with the 10% already
        // taken off. Description and price comparison finds no seam at all.
        List<OcrItem> third = List.of(
                coded("1X1625,00 7790150565735 PIMENTON ALICANTE PQX50G(21.00)", "1625.01", "7790150565735"),
                coded("1X2728,25 7791866001364 MAYO NATURA DPX500CC(21.00)", "2728.26", "7791866001364"));
        List<OcrItem> fourth = List.of(
                coded("1X1625,00 7790150565735 PIMENTON ALICANTE PQX50G(21.00)", "1462.52", "7790150565735"),
                coded("1X2728,25 7791866001364 MAYO NATURA DPX500CC(21.00)", "2455.43", "7791866001364"),
                coded("2X4048,99 7790060023684 ACEITE GIRASOL COCINERO(21.00)", "8098.00", "7790060023684"));

        List<OcrItem> merged = TicketProcessingService.mergePages(List.of(third, fourth));

        assertEquals(3, merged.size());
    }

    @Test
    @DisplayName("without barcodes the seam still falls back to description and price")
    void fallsBackToDescriptionWhenTheBarcodeIsMissing() {
        List<OcrItem> first = List.of(
                item("YERBA PLAYADITO 1KG", "4200.00"),
                item("FIDEOS MATARAZZO", "1150.00"));
        List<OcrItem> second = List.of(
                item("FIDEOS MATARAZZO", "1150.00"),
                item("LECHE LA SERENISIMA", "1780.00"));

        assertEquals(3, TicketProcessingService.mergePages(List.of(first, second)).size());
    }

    @Test
    @DisplayName("two different products are not merged just because both lack a barcode")
    void doesNotMergeUnrelatedUncodedLines() {
        List<OcrItem> first = List.of(item("YERBA PLAYADITO 1KG", "4200.00"));
        List<OcrItem> second = List.of(item("LECHE LA SERENISIMA", "4200.00"));

        assertEquals(2, TicketProcessingService.mergePages(List.of(first, second)).size());
    }

    @Test
    @DisplayName("the printed total comes from the page that captured it")
    void prefersThePageHoldingThePrintedTotal() {
        // The four pages of the receipt as the OCR actually returned them: the
        // first three report partial sums of what each photo showed, the last
        // one holds the printed TOTAL.
        OcrResult partialOne = page("34065.72", "6477.62", "27588.10");
        OcrResult misread = page("5663.04", "1939.50", "4599.00");
        OcrResult partialThree = page("70985.00", "5539.78", "65445.22");
        OcrResult printed = page("155220.98", "13799.59", "141421.40");

        OcrResult chosen = TicketProcessingService.printedTotals(
                List.of(partialOne, misread, partialThree, printed));

        assertSame(printed, chosen);
    }

    @Test
    @DisplayName("a page whose own totals do not add up is never believed")
    void ignoresAPageWhoseTotalsDoNotAddUp() {
        // 5663.04 - 1939.50 is 3723.54, not 4599: the model misread a number.
        // Believing it because it is the only page would report a false total.
        OcrResult misread = page("5663.04", "1939.50", "4599.00");

        assertNull(TicketProcessingService.printedTotals(List.of(misread)));
    }

    @Test
    @DisplayName("with no believable page the caller is left to derive the total")
    void returnsNothingWhenNoPageReportsTotals() {
        assertNull(TicketProcessingService.printedTotals(List.of()));
    }
}
