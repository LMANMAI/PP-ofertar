package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.FollowProductRequest;
import ar.edu.ofertAR.dto.response.FollowedProductResponse;
import ar.edu.ofertAR.dto.response.FollowedProductsResponse;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.BarcodeLastPurchase;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchClient.PromoMechanic;
import ar.edu.ofertAR.service.offer.OfferMatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Products the user follows without having bought them, to be told when they
 * go on promotion. The telling is {@link FollowedProductAlertService}; this is
 * the list itself.
 *
 * Deliberately not transactional: each method is one write, or a read followed
 * by a call to the offers service that should not hold a connection open.
 */
@Service
@RequiredArgsConstructor
public class FollowedProductService {

    /** Every followed product is one more line in the daily call to the
     * offers service, for every user. */
    static final int MAX_FOLLOWED = 30;

    private final FollowedProductRepository followedProductRepository;
    private final OfferMatchClient offerMatchClient;
    private final FavoriteStoreService favoriteStoreService;
    private final ProductoImagenService productoImagenService;
    private final TicketRepository ticketRepository;

    public FollowedProductsResponse list(User user) {
        List<FollowedProduct> followed = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        if (followed.isEmpty()) {
            return FollowedProductsResponse.builder().promosChecked(true).build();
        }

        Optional<List<OfferMatch>> matches = matchPromos(user, followed);
        Map<String, String> images = productoImagenService.imagenesPorEan(
                followed.stream().map(FollowedProduct::getEan).toList());
        List<BarcodeLastPurchase> purchases = ticketRepository.findLastPurchaseByBarcode(
                user.getId(), TicketStatus.PROCESSED);

        return FollowedProductsResponse.builder()
                .promosChecked(matches.isPresent())
                .items(IntStream.range(0, followed.size())
                        .mapToObj(i -> {
                            FollowedProduct f = followed.get(i);
                            OfferMatch match = matches
                                    .filter(found -> i < found.size())
                                    .map(found -> found.get(i))
                                    .orElseGet(OfferMatch::none);
                            return FollowedProductResponse.builder()
                                    .ean(f.getEan())
                                    .description(f.getDescription())
                                    .brand(f.getBrand())
                                    .imageUrl(images.get(ProductoImagenService.normalizarEan(f.getEan())))
                                    .followedAt(f.getCreatedAt())
                                    .lastPurchasedAt(lastPurchasedAt(f.getEan(), purchases))
                                    .inPromo(isInPromo(f.getDescription(), match))
                                    .bestOffer(OfferMatchMapper.bestOffer(match))
                                    .campaignOffers(OfferMatchMapper.campaignOffers(match))
                                    .promoMechanics(OfferMatchMapper.promoMechanics(match))
                                    .build();
                        })
                        .toList())
                .build();
    }

    public List<String> listEans(User user) {
        return followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .map(FollowedProduct::getEan)
                .toList();
    }

    /**
     * Following a product that is already followed is not an error.
     *
     * <p>"Ya seguido" se decide por EAN normalizado ({@link ProductKeys#sameEan}),
     * no por el texto exacto: seguir "07791234567890" cuando ya se sigue
     * "7791234567890" no agrega otra fila ni ocupa otro lugar del tope. El
     * código se guarda igual tal como vino, porque es con lo que el servicio de
     * ofertas lo busca.
     */
    public void follow(User user, FollowProductRequest request) {
        String ean = request.getEan().trim();
        List<FollowedProduct> current = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        if (current.stream().anyMatch(f -> ProductKeys.sameEan(f.getEan(), ean))) {
            return;
        }
        if (distinctProducts(current) >= MAX_FOLLOWED) {
            throw new IllegalArgumentException(
                    "Ya seguís " + MAX_FOLLOWED + " productos. Dejá de seguir alguno para sumar otro.");
        }
        try {
            followedProductRepository.save(FollowedProduct.builder()
                    .user(user)
                    .ean(ean)
                    .description(request.getDescription().trim())
                    .brand(blankToNull(request.getBrand()))
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Two taps on "Seguir" raced past the check above and the unique
            // key stopped the second one: the product is followed, which is
            // what was asked for.
        }
    }

    /**
     * Idempotent as well: not following it any more is the outcome either way.
     *
     * <p>Borra por EAN normalizado, así que el código puede venir en otro
     * formato que el guardado. Si quedaron dos filas del mismo producto (de
     * antes de comparar normalizado) se van las dos: el usuario pidió dejar de
     * seguir el producto, no una de sus formas de escribirlo.
     */
    public void unfollow(User user, String ean) {
        List<FollowedProduct> same = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream()
                .filter(f -> ProductKeys.sameEan(f.getEan(), ean))
                .toList();
        if (!same.isEmpty()) {
            followedProductRepository.deleteAll(same);
        }
    }

    /** Productos distintos entre los seguidos: dos filas del mismo EAN en
     * formatos distintos ocupan un solo lugar del tope. */
    private static long distinctProducts(List<FollowedProduct> followed) {
        Set<String> keys = new HashSet<>();
        for (FollowedProduct f : followed) {
            String key = ProductKeys.eanKey(f.getEan());
            keys.add(key != null ? key : f.getEan().trim());
        }
        return keys.size();
    }

    /**
     * Fecha del ticket procesado más reciente del usuario con un ítem cuyo
     * código de barras es este EAN (por EAN normalizado), o null si nunca lo
     * compró. Los ítems sin código no se pueden matchear y no cuentan: el OCR no
     * siempre lee el código, así que un producto comprado puede figurar como
     * nunca comprado. Se acepta: adivinar por descripción contra el nombre
     * guardado daría falsos positivos.
     */
    static LocalDateTime lastPurchasedAt(String ean, List<BarcodeLastPurchase> purchases) {
        LocalDateTime last = null;
        for (BarcodeLastPurchase p : purchases) {
            if (p.lastPurchasedAt() != null && ProductKeys.sameEan(ean, p.barcode())
                    && (last == null || p.lastPurchasedAt().isAfter(last))) {
                last = p.lastPurchasedAt();
            }
        }
        return last;
    }

    /**
     * What the offers service has for these products, in the same order, in
     * the chains the user shops at (every chain when they picked none). Empty
     * when the service could not be reached.
     *
     * <p>Alternative brands are left out on purpose: following a product is
     * asking about that one, not about something like it.
     */
    Optional<List<OfferMatch>> matchPromos(User user, List<FollowedProduct> followed) {
        return offerMatchClient.tryMatchProducts(
                followed.stream().map(f -> new ProductQuery(f.getDescription(), f.getEan())).toList(),
                false,
                favoriteStoreService.getFavoriteChainSlugs(user));
    }

    /**
     * A lower price, a condition on quantity (3x2, 2da unidad) or a campaign of
     * its brand. The one definition of "en promo" for the list and for the
     * alert, so the push never announces something the screen does not then
     * show.
     *
     * <p>Solo cuentan las promos de ESTE producto. El servicio de ofertas
     * matchea por marca y tipo, así que puede traer el precio o el 3x2 de otro
     * producto parecido de la misma marca; la pantalla los muestra como "Similar
     * en" / "Promo de otro producto", y un push que dijera "está en promo" por
     * eso sería mentira. Mismo producto es:
     * <ul>
     *   <li>oferta de catálogo: la encontró por EAN ({@code bestOfferMethod =
     *       "ean"}), o su nombre no es un match flojo
     *       ({@link ProductKeys#isLooseMatch}, el mismo criterio que la app);</li>
     *   <li>mecánica (3x2, 2da unidad): su nombre no es un match flojo. Estas
     *       filas no traen EAN ni método, así que el nombre es lo único que hay;</li>
     *   <li>campaña: siempre. No es la promo de otro producto sino de la marca
     *       en la categoría de este (el scraper ya filtra por marca y por las
     *       palabras del producto), no trae nombre de producto con qué comparar,
     *       y la pantalla la muestra sin la etiqueta de "otro producto".</li>
     * </ul>
     *
     * @param description el nombre del producto seguido, el mismo que la app
     *                    compara en pantalla
     */
    static boolean isInPromo(String description, OfferMatch match) {
        return sameProductCatalogPromo(description, match)
                || sameProductMechanic(description, match).isPresent()
                || !match.campaignOffers().isEmpty();
    }

    private static boolean sameProductCatalogPromo(String description, OfferMatch match) {
        return hasCatalogPromo(match)
                && (match.bestOfferByEan() || !ProductKeys.isLooseMatch(description, match.productName()));
    }

    /** La primera mecánica que es de este producto, en el orden del scraper
     * (la más alcanzable primero). */
    private static Optional<PromoMechanic> sameProductMechanic(String description, OfferMatch match) {
        return match.promoMechanics().stream()
                .filter(m -> !ProductKeys.isLooseMatch(description, m.productName()))
                .findFirst();
    }

    /**
     * Whether the catalog price itself is a promotion. Having a catalog match
     * is not enough: the offers service also answers with products at their
     * usual price whose only labels are the chain's card or bank discounts
     * ("Tarjeta Carrefour 15%"), and a push saying "está en promo" for
     * something only cheaper with a card the user may not have is the kind of
     * notification that gets the rest turned off. So it takes a price below
     * the list price, or a quantity condition the chain put a number on.
     */
    private static boolean hasCatalogPromo(OfferMatch match) {
        if (!match.hasOffer()) return false;
        if (match.discountPct() != null && match.discountPct().signum() > 0) return true;
        if (match.listPrice() != null && match.listPrice().compareTo(match.price()) > 0) return true;
        if (match.requiredQuantity() != null && match.requiredQuantity() > 1) return true;
        return match.promoUnitPrice() != null && match.promoUnitPrice().compareTo(match.price()) < 0;
    }

    /**
     * The chain to name in the push: where the promotion is. Con el mismo
     * criterio que {@link #isInPromo}: salta las promos de otro producto, así el
     * push nunca nombra la cadena de una promo que la pantalla marca como ajena.
     */
    static String promoRetailer(String description, OfferMatch match) {
        if (sameProductCatalogPromo(description, match)) return match.retailerName();
        Optional<PromoMechanic> mechanic = sameProductMechanic(description, match);
        if (mechanic.isPresent()) return mechanic.get().retailerName();
        if (!match.campaignOffers().isEmpty()) return match.campaignOffers().get(0).retailerName();
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
