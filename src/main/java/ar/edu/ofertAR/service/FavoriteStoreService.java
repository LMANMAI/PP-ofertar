package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.UpdateFavoriteStoresRequest;
import ar.edu.ofertAR.dto.response.FavoriteStoresResponse;
import ar.edu.ofertAR.model.FavoriteStoreChain;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FavoriteStoreChainRepository;
import ar.edu.ofertAR.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FavoriteStoreService {

    private final FavoriteStoreChainRepository favoriteRepository;
    private final UserRepository userRepository;

    /** Users created before the radius column existed got MySQL's implicit
     * default of 0 when the column was added, which would search a zero-km
     * area and find nothing. Treat anything out of range as "unset". */
    static final int DEFAULT_RADIUS_KM = 5;
    private static final int MIN_RADIUS_KM = 1;
    private static final int MAX_RADIUS_KM = 20;

    public static int sanitizeRadius(int radiusKm) {
        return (radiusKm < MIN_RADIUS_KM || radiusKm > MAX_RADIUS_KM) ? DEFAULT_RADIUS_KM : radiusKm;
    }

    public FavoriteStoresResponse getFavorites(User user) {
        return FavoriteStoresResponse.builder()
                .chainSlugs(favoriteRepository.findByUserId(user.getId()).stream()
                        .map(FavoriteStoreChain::getChainSlug)
                        .toList())
                .radiusKm(sanitizeRadius(user.getStoreSearchRadiusKm()))
                .build();
    }

    @Transactional
    public FavoriteStoresResponse updateFavorites(User user, UpdateFavoriteStoresRequest request) {
        if (request.getChainSlugs() != null) {
            // Replace wholesale: the client always sends the full selection,
            // and diffing adds nothing for a list this small.
            favoriteRepository.deleteByUserId(user.getId());
            // Force the DELETEs out before the INSERTs. Hibernate orders
            // inserts ahead of deletes inside a flush, so re-saving a chain
            // the user already had would hit the UNIQUE(user_id, chain_slug)
            // constraint and fail the whole request.
            favoriteRepository.flush();

            List<FavoriteStoreChain> toSave = request.getChainSlugs().stream()
                    .filter(slug -> slug != null && !slug.isBlank())
                    .distinct()
                    .map(slug -> FavoriteStoreChain.builder()
                            .user(user)
                            .chainSlug(slug.trim())
                            .build())
                    .toList();
            favoriteRepository.saveAll(toSave);
        }

        if (request.getRadiusKm() != null) {
            user.setStoreSearchRadiusKm(request.getRadiusKm());
            userRepository.save(user);
        }

        return FavoriteStoresResponse.builder()
                .chainSlugs(request.getChainSlugs() != null
                        ? request.getChainSlugs()
                        : favoriteRepository.findByUserId(user.getId()).stream()
                                .map(FavoriteStoreChain::getChainSlug)
                                .toList())
                .radiusKm(user.getStoreSearchRadiusKm())
                .build();
    }

    /** Chain slugs to restrict offers to, or empty when the user hasn't
     * picked any (in which case every chain is fair game). */
    public List<String> getFavoriteChainSlugs(User user) {
        return favoriteRepository.findByUserId(user.getId()).stream()
                .map(FavoriteStoreChain::getChainSlug)
                .toList();
    }
}
