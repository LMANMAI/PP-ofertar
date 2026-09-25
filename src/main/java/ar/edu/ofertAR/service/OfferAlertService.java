package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import ar.edu.ofertAR.model.FavoriteStoreChain;
import ar.edu.ofertAR.model.SeenChainOffer;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FavoriteStoreChainRepository;
import ar.edu.ofertAR.repository.SeenChainOfferRepository;
import ar.edu.ofertAR.service.offer.OfferFeedClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Daily digest of new offers at the chains people follow.
 *
 * Scoped per chain, not per user: dozens of users can share the same handful
 * of favorite chains, and there is no persisted offer catalog to query
 * locally (see OfferFeedClient) — every check is a live call to the scraper,
 * so fetching once per chain and fanning out is the only way this doesn't
 * turn into one scraper call per user for the same data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferAlertService {

    /** How deep into each chain's feed to look for something new. Not the
     * whole catalog (a chain can carry hundreds of rows) — this is a
     * best-effort "what's new" digest, not a completeness guarantee. */
    private static final int OFFERS_PER_CHAIN_CHECKED = 50;

    private final FavoriteStoreChainRepository favoriteStoreChainRepository;
    private final SeenChainOfferRepository seenChainOfferRepository;
    private final OfferFeedClient offerFeedClient;
    private final PushNotificationService pushNotificationService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${offers.alert-cron:0 0 5 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void runOfferAlertJob() {
        List<String> chainSlugs = favoriteStoreChainRepository.findDistinctChainSlugs();
        log.info("Job de alertas de ofertas: {} cadenas con favoritos", chainSlugs.size());

        // chainSlug -> the single best new offer found there today. The
        // very first run has nothing in SeenChainOffer yet, so everything
        // currently on offer counts as "new" — a one-time welcome digest
        // rather than a false claim, and still capped to one push per user
        // by the aggregation below.
        Map<String, OfferFeedResponse.Offer> bestNewOfferByChain = new HashMap<>();
        for (String chainSlug : chainSlugs) {
            try {
                findBestNewOffer(chainSlug).ifPresent(offer -> bestNewOfferByChain.put(chainSlug, offer));
            } catch (Exception e) {
                log.error("Fallo evaluando ofertas nuevas de {}: {}", chainSlug, e.getMessage(), e);
            }
        }

        if (!bestNewOfferByChain.isEmpty()) {
            notifyUsers(bestNewOfferByChain);
        }
    }

    private Optional<OfferFeedResponse.Offer> findBestNewOffer(String chainSlug) {
        List<OfferFeedResponse.Offer> current = fetchChainOffers(chainSlug);
        if (current.isEmpty()) {
            return Optional.empty();
        }

        Set<String> currentIds = current.stream().map(OfferFeedResponse.Offer::getId).collect(Collectors.toSet());
        Set<String> alreadySeen = seenChainOfferRepository.findByChainSlugAndOfferIdIn(chainSlug, currentIds)
                .stream().map(SeenChainOffer::getOfferId).collect(Collectors.toSet());
        List<OfferFeedResponse.Offer> newOffers = current.stream()
                .filter(o -> !alreadySeen.contains(o.getId()))
                .toList();

        if (newOffers.isEmpty()) {
            return Optional.empty();
        }

        transactionTemplate.executeWithoutResult(status -> {
            for (OfferFeedResponse.Offer offer : newOffers) {
                seenChainOfferRepository.save(SeenChainOffer.builder()
                        .chainSlug(chainSlug)
                        .offerId(offer.getId())
                        .build());
            }
        });

        return newOffers.stream().max(Comparator.comparing(OfferAlertService::discountOrZero));
    }

    private List<OfferFeedResponse.Offer> fetchChainOffers(String chainSlug) {
        try {
            return offerFeedClient
                    .listOffers(List.of(chainSlug), 1, OFFERS_PER_CHAIN_CHECKED, null, List.of())
                    .getItems();
        } catch (Exception e) {
            log.warn("No se pudo traer el feed de {} para el job de alertas: {}", chainSlug, e.getMessage());
            return List.of();
        }
    }

    /** Caps at one push per user per run: a user following several chains
     * that all turned up something new only hears about the single best one,
     * not once per chain. */
    private void notifyUsers(Map<String, OfferFeedResponse.Offer> bestNewOfferByChain) {
        Map<Long, User> usersById = new HashMap<>();
        Map<Long, OfferFeedResponse.Offer> bestPerUser = new HashMap<>();

        for (Map.Entry<String, OfferFeedResponse.Offer> entry : bestNewOfferByChain.entrySet()) {
            for (FavoriteStoreChain favorite : favoriteStoreChainRepository.findByChainSlug(entry.getKey())) {
                User user = favorite.getUser();
                if (!user.isOffersPushEnabled()) {
                    continue;
                }
                usersById.put(user.getId(), user);
                bestPerUser.merge(user.getId(), entry.getValue(),
                        (current, candidate) -> discountOrZero(candidate).compareTo(discountOrZero(current)) > 0
                                ? candidate : current);
            }
        }

        for (Map.Entry<Long, OfferFeedResponse.Offer> entry : bestPerUser.entrySet()) {
            pushNotificationService.notifyNewOffer(usersById.get(entry.getKey()), entry.getValue());
        }
    }

    private static BigDecimal discountOrZero(OfferFeedResponse.Offer offer) {
        return offer.getDiscountPct() != null ? offer.getDiscountPct() : BigDecimal.ZERO;
    }
}
