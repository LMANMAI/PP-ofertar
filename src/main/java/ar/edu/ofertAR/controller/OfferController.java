package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import ar.edu.ofertAR.model.User;

import java.util.Arrays;
import java.util.List;
import ar.edu.ofertAR.service.FavoriteStoreService;
import ar.edu.ofertAR.service.offer.OfferFeedClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/offers")
@RequiredArgsConstructor
public class OfferController {

    private static final int MAX_PAGE_SIZE = 50;

    private final OfferFeedClient offerFeedClient;
    private final FavoriteStoreService favoriteStoreService;

    /**
     * Everything on offer at the chains the user follows, whether or not they
     * buy it. Deliberately not the same as {@code /products/recurring}, which
     * only ever knows about products already on a receipt.
     *
     * An empty favourites list means no preference, so nothing gets filtered —
     * showing an empty screen to a user who never picked chains would look
     * broken.
     */
    @GetMapping
    public ResponseEntity<OfferFeedResponse> listOffers(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String chains,
            @RequestParam(required = false) String categories
    ) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        List<String> favourites = favoriteStoreService.getFavoriteChainSlugs(user);
        return ResponseEntity.ok(offerFeedClient.listOffers(
                resolveChains(chains, favourites), safePage, safePageSize, province,
                splitCsv(categories)));
    }

    /** A comma-separated query parameter as a list, trimmed and de-duplicated.
     * Empty means no filter rather than an empty result. */
    static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Which chains one page is built from.
     *
     * The app's chain filter used to run over the page it already had, so
     * picking Makro showed the handful of Makro rows that happened to survive a
     * feed four fifths of which is Carrefour — three, against 628 Makro offers
     * in the catalog. Narrowing has to happen before the page is cut.
     *
     * A request can only narrow, never widen: the screen is "ofertas en tus
     * super", so anything outside the user's favourites is dropped. With no
     * favourites set there is nothing to narrow against and the request stands
     * on its own.
     */
    static List<String> resolveChains(String requested, List<String> favourites) {
        if (requested == null || requested.isBlank()) return favourites;
        List<String> asked = Arrays.stream(requested.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (asked.isEmpty()) return favourites;
        if (favourites == null || favourites.isEmpty()) return asked;
        List<String> allowed = asked.stream().filter(favourites::contains).toList();
        // Asking only for chains the user does not follow is a stale filter,
        // not a request for an empty screen.
        return allowed.isEmpty() ? favourites : allowed;
    }
}
