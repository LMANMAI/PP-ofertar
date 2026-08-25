package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import ar.edu.ofertAR.model.User;
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
            @RequestParam(required = false) String province
    ) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        return ResponseEntity.ok(offerFeedClient.listOffers(
                favoriteStoreService.getFavoriteChainSlugs(user), safePage, safePageSize, province));
    }
}
