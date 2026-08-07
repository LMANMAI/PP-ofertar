package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.UpdateFavoriteStoresRequest;
import ar.edu.ofertAR.dto.response.FavoriteStoresResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.FavoriteStoreService;
import ar.edu.ofertAR.service.offer.StoreLocatorClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

    private final FavoriteStoreService favoriteStoreService;
    private final StoreLocatorClient storeLocatorClient;

    /** Chains the user can choose from. */
    @GetMapping("/chains")
    public ResponseEntity<List<Map<String, Object>>> getChains() {
        return ResponseEntity.ok(storeLocatorClient.listChains());
    }

    /**
     * Branches near a coordinate. Defaults to the user's saved radius, and
     * returns every chain (not just favourites) so the picker can show what's
     * available to choose from.
     */
    @GetMapping("/nearby")
    public ResponseEntity<List<Map<String, Object>>> getNearby(
            @AuthenticationPrincipal User user,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Integer radiusKm,
            @RequestParam(required = false) Boolean onlyFavorites
    ) {
        int radius = radiusKm != null ? radiusKm : user.getStoreSearchRadiusKm();
        List<String> chains = Boolean.TRUE.equals(onlyFavorites)
                ? favoriteStoreService.getFavoriteChainSlugs(user)
                : List.of();
        return ResponseEntity.ok(storeLocatorClient.findNearbyStores(lat, lng, radius, chains));
    }

    @GetMapping("/favorites")
    public ResponseEntity<FavoriteStoresResponse> getFavorites(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(favoriteStoreService.getFavorites(user));
    }

    @PutMapping("/favorites")
    public ResponseEntity<FavoriteStoresResponse> updateFavorites(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateFavoriteStoresRequest request
    ) {
        return ResponseEntity.ok(favoriteStoreService.updateFavorites(user, request));
    }
}
