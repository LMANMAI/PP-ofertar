package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.TicketItem;

/**
 * Identity of a product across different tickets. Receipts have no stable
 * product id: the OCR gives a barcode when the receipt printed one, and free
 * text otherwise, so the same item can key differently between two scans.
 * Centralised here so every feature that groups purchases (savings report,
 * recurring products, shopping list) agrees on what "the same product" means.
 */
public final class ProductKeys {

    private ProductKeys() {}

    public static String keyOf(TicketItem item) {
        if (item.getBarcode() != null && !item.getBarcode().isBlank()) {
            return item.getBarcode().trim();
        }
        return normalizeDescription(item.getDescription());
    }

    public static String normalizeDescription(String description) {
        if (description == null) return "";
        return description.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Whether two items refer to the same product. Deliberately looser than
     * comparing {@link #keyOf} results: an item carrying a barcode in one
     * scan and only text in another still matches on the description.
     */
    public static boolean sameProduct(TicketItem a, TicketItem b) {
        boolean bothHaveBarcode = a.getBarcode() != null && !a.getBarcode().isBlank()
                && b.getBarcode() != null && !b.getBarcode().isBlank();
        if (bothHaveBarcode && a.getBarcode().trim().equals(b.getBarcode().trim())) {
            return true;
        }
        String da = normalizeDescription(a.getDescription());
        return !da.isEmpty() && da.equals(normalizeDescription(b.getDescription()));
    }
}
