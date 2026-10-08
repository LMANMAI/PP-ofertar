package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Daily check of the products people follow: a push when one of them goes on
 * promotion.
 *
 * Per user, unlike {@link OfferAlertService}, which fetches once per chain.
 * There the question is "what is new at this chain", the same for everyone
 * who follows it. Here it is "is this product on promotion at the chains this
 * user shops at", and both halves change from one user to the next — so it is
 * one call to the offers service per user with followed products, carrying all
 * of them at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowedProductAlertService {

    /**
     * Minimum days between two pushes about the same product. A promotion that
     * the scraper loses for a day and finds again would otherwise be announced
     * as new each time it comes back.
     */
    static final int RENOTIFY_COOLDOWN_DAYS = 7;

    private final FollowedProductRepository followedProductRepository;
    private final UserRepository userRepository;
    private final FollowedProductService followedProductService;
    private final PushNotificationService pushNotificationService;

    @Scheduled(cron = "${followed.alert-cron:0 20 5 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void runFollowedProductAlertJob() {
        List<Long> userIds = followedProductRepository.findDistinctUserIds();
        log.info("Job de productos seguidos: {} usuarios siguen algún producto", userIds.size());

        for (Long userId : userIds) {
            try {
                checkUser(userId);
            } catch (Exception e) {
                log.error("Fallo evaluando los productos seguidos del usuario {}: {}", userId, e.getMessage(), e);
            }
        }
    }

    private void checkUser(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        // Same switch as the offers digest: this is an offer notification.
        // Skipped before asking the offers service, and nothing is recorded,
        // so a promotion already running is announced the day they turn the
        // notifications back on.
        if (user == null || !user.isOffersPushEnabled()) {
            return;
        }

        List<FollowedProduct> followed = followedProductRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        if (followed.isEmpty()) {
            return;
        }

        Optional<List<OfferMatch>> answer = followedProductService.matchPromos(user, followed);
        if (answer.isEmpty()) {
            // Not "nothing is on promotion": we do not know. Leaving the state
            // untouched is what keeps tomorrow's run from announcing as new a
            // promotion that was already running today.
            log.warn("Sin respuesta del servicio de ofertas para los productos seguidos del usuario {}", userId);
            return;
        }
        List<OfferMatch> matches = answer.get();

        LocalDateTime now = LocalDateTime.now();
        List<FollowedProduct> changed = new ArrayList<>();
        List<PushNotificationService.FollowedPromo> toAnnounce = new ArrayList<>();

        // A shorter answer than the question leaves the tail unknown, and it
        // is treated like the outage above: untouched.
        for (int i = 0; i < followed.size() && i < matches.size(); i++) {
            FollowedProduct product = followed.get(i);
            OfferMatch match = matches.get(i);
            boolean inPromo = FollowedProductService.isInPromo(match);
            if (inPromo == product.isInPromo()) {
                continue;
            }

            product.setInPromo(inPromo);
            changed.add(product);
            if (inPromo && cooledDown(product, now)) {
                product.setLastNotifiedAt(now);
                toAnnounce.add(new PushNotificationService.FollowedPromo(
                        product.getDescription(), FollowedProductService.promoRetailer(match)));
            }
        }

        if (!changed.isEmpty()) {
            followedProductRepository.saveAll(changed);
        }
        if (!toAnnounce.isEmpty()) {
            pushNotificationService.notifyFollowedProductsInPromo(user, toAnnounce);
        }
    }

    private static boolean cooledDown(FollowedProduct product, LocalDateTime now) {
        return product.getLastNotifiedAt() == null
                || product.getLastNotifiedAt().isBefore(now.minusDays(RENOTIFY_COOLDOWN_DAYS));
    }
}
