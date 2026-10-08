package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.FollowProductRequest;
import ar.edu.ofertAR.dto.response.FollowedProductResponse;
import ar.edu.ofertAR.dto.response.FollowedProductsResponse;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public FollowedProductsResponse list(User user) {
        List<FollowedProduct> followed = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        if (followed.isEmpty()) {
            return FollowedProductsResponse.builder().promosChecked(true).build();
        }

        Optional<List<OfferMatch>> matches = matchPromos(user, followed);
        Map<String, String> images = productoImagenService.imagenesPorEan(
                followed.stream().map(FollowedProduct::getEan).toList());

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
                                    .inPromo(isInPromo(match))
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

    /** Following a product that is already followed is not an error. */
    public void follow(User user, FollowProductRequest request) {
        String ean = request.getEan().trim();
        if (followedProductRepository.findByUserIdAndEan(user.getId(), ean).isPresent()) {
            return;
        }
        if (followedProductRepository.countByUserId(user.getId()) >= MAX_FOLLOWED) {
            throw new IllegalArgumentException(
                    "Ya seguís " + MAX_FOLLOWED + " productos. Dejá de seguir alguno para sumar otro.");
        }
        try {
            followedProductRepository.save(FollowedProduct.builder()
                    .user(user)
                    .ean(ean)
                    .description(request.getDescription().trim())
                    .brand(ShoppingListService.blankToNull(request.getBrand()))
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Two taps on "Seguir" raced past the check above and the unique
            // key stopped the second one: the product is followed, which is
            // what was asked for.
        }
    }

    /** Idempotent as well: not following it any more is the outcome either way. */
    public void unfollow(User user, String ean) {
        followedProductRepository.findByUserIdAndEan(user.getId(), ean.trim())
                .ifPresent(followedProductRepository::delete);
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
     */
    static boolean isInPromo(OfferMatch match) {
        return hasCatalogPromo(match) || !match.promoMechanics().isEmpty() || !match.campaignOffers().isEmpty();
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

    /** The chain to name in the push: where the promotion is. */
    static String promoRetailer(OfferMatch match) {
        if (hasCatalogPromo(match)) return match.retailerName();
        if (!match.promoMechanics().isEmpty()) return match.promoMechanics().get(0).retailerName();
        if (!match.campaignOffers().isEmpty()) return match.campaignOffers().get(0).retailerName();
        return null;
    }
}
