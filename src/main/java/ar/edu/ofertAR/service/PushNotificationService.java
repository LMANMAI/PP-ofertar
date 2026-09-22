package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PushTokenRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The one place that talks to Expo's push API. Callers (a ticket finishing
 * OCR, a referral getting credited) fire {@link #sendToUser} and move on —
 * the actual HTTP call happens off {@link #pushNotificationExecutor} so a
 * slow or down push service never holds up the request that triggered it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    private final PushTokenRepository pushTokenRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final RestClient restClient;
    private final ExecutorService pushNotificationExecutor;
    private final TransactionTemplate transactionTemplate;

    /** Nobody's scanned in this many days. */
    private static final int REACTIVATION_DORMANT_DAYS = 14;
    /** Never nudge the same user twice inside this window, even if the job
     * runs daily and they're still dormant on every run. */
    private static final int REACTIVATION_COOLDOWN_DAYS = 30;

    @Transactional
    public void registerToken(User user, RegisterPushTokenRequest request) {
        // The same physical device can end up registering an Expo token that
        // was last attached to a different account (someone logged out and a
        // different person logged in on that phone) — reassign it rather
        // than bounce off the unique constraint on `token`.
        PushToken existing = pushTokenRepository.findByToken(request.getToken()).orElse(null);
        if (existing != null) {
            existing.setUser(user);
            existing.setPlatform(request.getPlatform());
            existing.setLastSeenAt(LocalDateTime.now());
            pushTokenRepository.save(existing);
            return;
        }

        pushTokenRepository.save(PushToken.builder()
                .user(user)
                .token(request.getToken())
                .platform(request.getPlatform())
                .build());
    }

    /** Fire-and-forget: queues the send and returns immediately. */
    public void sendToUser(User user, String title, String body, Map<String, String> data) {
        pushNotificationExecutor.submit(() -> sendToUserBlocking(user, title, body, data));
    }

    public void notifyTicketProcessed(Ticket ticket) {
        String store = ticket.getStoreName() != null && !ticket.getStoreName().isBlank()
                ? ticket.getStoreName() : "tu compra";
        sendToUser(ticket.getUser(), "Ya está tu ticket",
                "Terminamos de leer el ticket de " + store + ".",
                Map.of("screen", "ticketDetail", "ticketId", String.valueOf(ticket.getId())));
    }

    /** Covers every path that leaves a ticket FAILED — OCR/commit blowing up,
     * a duplicate ticketId, or a page with nothing readable on it — with the
     * same generic message, since none of those distinctions are actionable
     * for the person who just scanned it. */
    public void notifyTicketFailed(Ticket ticket) {
        sendToUser(ticket.getUser(), "No pudimos leer tu ticket",
                "Volvé a intentar escanearlo desde la app.",
                Map.of("screen", "ticketHistory"));
    }

    /** Only called when points were actually credited — PointsService skips
     * this entirely once a referrer hits their monthly cap. */
    public void notifyReferralActivated(User referrer) {
        sendToUser(referrer, "Tu amigo activó su cuenta",
                "Ganaste puntos por invitarlo. Mirá tu historial.",
                Map.of("screen", "pointsHistory"));
    }

    public void notifyReferralRetained(User referrer) {
        sendToUser(referrer, "Tu amigo se quedó en OfertAR",
                "Ganaste puntos extra porque sigue usando la app.",
                Map.of("screen", "pointsHistory"));
    }

    // ── Reactivacion — job diario ────────────────────────────────────────

    @Scheduled(cron = "${reactivation.cron:0 30 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void runReactivationJob() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(REACTIVATION_DORMANT_DAYS);
        List<Long> dormantUserIds = ticketRepository.findUserIdsWithLastTicketBefore(cutoff);
        log.info("Job de reactivacion: {} usuarios sin escanear desde antes de {}", dormantUserIds.size(), cutoff);

        for (Long userId : dormantUserIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> processReactivationCandidate(userId));
            } catch (Exception e) {
                log.error("Fallo evaluando reactivacion del usuario {}: {}", userId, e.getMessage(), e);
            }
        }
    }

    /** Gated by offersPushEnabled — this is engagement, not a transactional
     * response to something the user did — and by its own cooldown so a
     * still-dormant user isn't nudged again on every run of the job. */
    private void processReactivationCandidate(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isOffersPushEnabled()) {
            return;
        }

        LocalDateTime cooldownCutoff = LocalDateTime.now().minusDays(REACTIVATION_COOLDOWN_DAYS);
        if (user.getLastReactivationNudgeAt() != null && user.getLastReactivationNudgeAt().isAfter(cooldownCutoff)) {
            return;
        }

        sendToUser(user, "Te extrañamos",
                "Escaneá tu próximo ticket y seguí ahorrando con OfertAR.",
                Map.of("screen", "scanMethod"));
        user.setLastReactivationNudgeAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private void sendToUserBlocking(User user, String title, String body, Map<String, String> data) {
        List<PushToken> tokens = pushTokenRepository.findByUserId(user.getId());
        if (tokens.isEmpty()) {
            return;
        }

        List<String> tokenValues = tokens.stream().map(PushToken::getToken).toList();
        Map<String, Object> message = Map.of(
                "to", tokenValues,
                "title", title,
                "body", body,
                "data", data == null ? Map.of() : data,
                "sound", "default"
        );

        try {
            @SuppressWarnings("unchecked")
            var response = (Map<String, Object>) restClient.post()
                    .uri(EXPO_PUSH_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(message))
                    .retrieve()
                    .body(Map.class);

            handleTickets(tokenValues, response);
        } catch (Exception e) {
            log.error("Fallo al enviar push a usuario {}: {}", user.getId(), e.getMessage(), e);
        }
    }

    /**
     * Expo returns one "ticket" per recipient, in the same order as the `to`
     * array we sent — a DeviceNotRegistered error means the app was
     * uninstalled or the token expired, so that row is dead weight from now
     * on and gets cleaned up instead of failing again on every future send.
     */
    @SuppressWarnings("unchecked")
    private void handleTickets(List<String> tokenValues, Map<String, Object> response) {
        if (response == null) {
            return;
        }
        Object dataObj = response.get("data");
        if (!(dataObj instanceof List<?> tickets)) {
            return;
        }

        for (int i = 0; i < tickets.size() && i < tokenValues.size(); i++) {
            if (!(tickets.get(i) instanceof Map<?, ?> ticket)) {
                continue;
            }
            if (!"error".equals(ticket.get("status"))) {
                continue;
            }

            Object detailsObj = ticket.get("details");
            String errorCode = detailsObj instanceof Map<?, ?> details
                    ? String.valueOf(((Map<String, Object>) details).get("error"))
                    : null;

            if ("DeviceNotRegistered".equals(errorCode)) {
                pushTokenRepository.deleteByToken(tokenValues.get(i));
                log.info("Token de push eliminado (DeviceNotRegistered): {}", tokenValues.get(i));
            } else {
                log.warn("Expo devolvio error al enviar push: {}", ticket.get("message"));
            }
        }
    }
}
