package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identity of a product across different tickets. Receipts have no stable
 * product id: the OCR gives a barcode when the receipt printed one, and free
 * text otherwise, so the same item can key differently between two scans.
 * Centralised here so every feature that groups purchases (savings report,
 * recurring products, followed products) agrees on what "the same product" means.
 */
public final class ProductKeys {

    private ProductKeys() {}

    /**
     * Clave de comparación de un código de barras: el EAN-13 que devuelve
     * {@link ProductoImagenService#normalizarEan}, o null si no es un EAN usable.
     *
     * <p>Solo para comparar, nunca para guardar ni para consultar ofertas: el
     * servicio de ofertas busca con el código tal como vino, y normalizarlo ahí
     * podría romper el match. SEPA mezcla códigos de 13 y 14 dígitos, el código
     * del ticket sale del OCR y un EAN-8 llega completado con ceros, así que el
     * mismo producto puede venir escrito de varias formas.
     */
    public static String eanKey(String code) {
        return ProductoImagenService.normalizarEan(code);
    }

    /**
     * Si dos códigos de barras son el mismo producto, aunque vengan en formatos
     * distintos ("07791234567890" y "7791234567890"). El único criterio de
     * "mismo producto por EAN": entre un producto seguido y el código de un ítem
     * de ticket, y entre dos productos seguidos.
     *
     * <p>Si alguno no se puede normalizar (ceros, basura del OCR) se compara el
     * texto tal cual, sin espacios alrededor: así un código raro sigue siendo
     * igual a sí mismo, pero nunca igual a otro.
     */
    public static boolean sameEan(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String ka = eanKey(a);
        String kb = eanKey(b);
        if (ka != null && kb != null) return ka.equals(kb);
        return a.trim().equals(b.trim());
    }

    /** Palabras que no dicen qué producto es; ver {@link #isLooseMatch}. */
    private static final Set<String> FILLER = Set.of(
            "del", "los", "las", "con", "sin", "por", "para",
            "sobre", "sob", "gramo", "grm", "grs", "unidad", "kilo", "litro", "paquete", "caja", "pack");

    /**
     * Si el producto de catálogo al que pertenece una promo probablemente NO es
     * el que el usuario compró o sigue. El servicio de ofertas matchea por marca
     * y tipo de producto, así que una bolsa de residuos puede quedar con el
     * precio de un rollo de cocina de la misma marca.
     *
     * <p>Port literal de {@code isLooseMatch} de {@code src/utils/productMatch.ts}
     * del frontend, que es el que pone "Promo de otro producto" y "Similar en"
     * en pantalla: si cambia uno, tiene que cambiar el otro, o el push vuelve a
     * anunciar lo que la pantalla después marca como de otro producto. La primera
     * palabra de un nombre en castellano es el producto ("Laurel molido", "Bolsa
     * maxi"): tiene que aparecer en el nombre de catálogo, junto con al menos el
     * 60% de las demás.
     *
     * <p>Falso cuando no hay con qué comparar, para no degradar un match
     * desconocido por una suposición.
     */
    public static boolean isLooseMatch(String bought, String matched) {
        if (matched == null || matched.isEmpty()) return false;
        List<String> want = matchTokens(bought);
        if (want.isEmpty()) return false;
        Set<String> have = new HashSet<>(matchTokens(matched));
        if (!have.contains(want.get(0))) return true;
        long covered = want.stream().filter(have::contains).count();
        return (double) covered / want.size() < 0.6;
    }

    private static List<String> matchTokens(String value) {
        if (value == null) return List.of();
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String raw : plain.split("[^a-z0-9]+")) {
            // Plural y singular son la misma palabra.
            String t = raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
            // Números y unidades ("1", "500", "g") cambian con la presentación,
            // y el tamaño no es lo que distingue un producto de otro acá.
            if (t.length() > 2 && !FILLER.contains(t) && !Character.isDigit(t.charAt(0))) {
                out.add(t);
            }
        }
        return out;
    }

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
