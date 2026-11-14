package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.dto.response.OfferFeedResponse;
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
import java.util.ArrayList;
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

    /**
     * Prioridad de entrega. En Android, con la prioridad normal (la que usa
     * Expo si no se manda nada) FCM puede retener el mensaje mientras el
     * teléfono está en reposo (Doze) y la notificación llega minutos tarde.
     * En iOS "high" es la prioridad 10 de APNs, la de entrega inmediata.
     *
     * No se manda {@code channelId}: la app no crea canales de Android propios
     * (no llama a {@code setNotificationChannelAsync}), así que el aviso va al
     * canal por defecto que arma expo-notifications.
     */
    static final String EXPO_PRIORITY = "high";

    /** A dónde lleva cada aviso al tocarlo (ver {@code useNotificationTaps.ts}
     * en la app). Son los mismos para el aviso real y para su prueba desde
     * {@code POST /push/test}, así la prueba abre la misma pantalla. */
    public static final Map<String, String> DATA_FOLLOWED_PRODUCTS = Map.of("screen", "followedProducts");
    public static final Map<String, String> DATA_OFFERS = Map.of("screen", "offers");
    public static final Map<String, String> DATA_REACTIVATION = Map.of("screen", "scanMethod");

    private final PushTokenRepository pushTokenRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final RestClient restClient;
    private final ExecutorService pushNotificationExecutor;
    private final TransactionTemplate transactionTemplate;

    /** Nobody's scanned in this many days. */
    static final int REACTIVATION_DORMANT_DAYS = 14;
    /** Never nudge the same user twice inside this window, even if the job
     * runs daily and they're still dormant on every run. */
    static final int REACTIVATION_COOLDOWN_DAYS = 30;

    /**
     * Resultado de Expo para un dispositivo: el "ticket" que devuelve por cada
     * destinatario. {@code ok} quiere decir que Expo lo aceptó para entregarlo,
     * no que ya llegó al teléfono (eso lo dirían los receipts, que no se
     * consultan). {@code error} es el código de Expo (p. ej.
     * {@code DeviceNotRegistered}) cuando lo hay.
     */
    public record DeliveryResult(PushToken device, boolean ok, String message, String error) {}

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

    /** Dispositivos registrados del usuario. */
    public List<PushToken> devicesOf(Long userId) {
        return pushTokenRepository.findByUserId(userId);
    }

    /** Fire-and-forget: queues the send and returns immediately. */
    public void sendToUser(User user, String title, String body, Map<String, String> data) {
        pushNotificationExecutor.submit(() -> sendToUserBlocking(user, title, body, data));
    }

    /**
     * Envía ahora, en el hilo de quien llama, y devuelve qué contestó Expo por
     * cada dispositivo. Lo usa {@code POST /push/test}, que necesita mostrar el
     * resultado. Igual que el envío en segundo plano, borra los tokens que Expo
     * da por muertos. Lista vacía si el usuario no tiene dispositivos.
     */
    public List<DeliveryResult> sendToUserNow(User user, String title, String body, Map<String, String> data) {
        List<PushToken> tokens = pushTokenRepository.findByUserId(user.getId());
        if (tokens.isEmpty()) {
            return List.of();
        }
        try {
            return deliver(tokens, title, body, data);
        } catch (Exception e) {
            log.error("Fallo al enviar push a usuario {}: {}", user.getId(), e.getMessage(), e);
            return tokens.stream()
                    .map(t -> new DeliveryResult(t, false, "No se pudo contactar al servicio de notificaciones.", null))
                    .toList();
        }
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

    /** One offer, already chosen as "the" one worth telling this user about
     * — OfferAlertService owns picking it, this just formats and sends it. */
    public void notifyNewOffer(User user, OfferFeedResponse.Offer offer) {
        String title = "Nueva oferta en " + (offer.getRetailerName() != null ? offer.getRetailerName() : "tu súper");
        String body = offer.getProductName() != null
                ? offer.getProductName() + ": " + offer.getHeadline()
                : offer.getHeadline();
        sendToUser(user, title, body, DATA_OFFERS);
    }

    /** A followed product that went on promotion, as the push names it. */
    public record FollowedPromo(String description, String retailerName) {}

    /**
     * One push for everything that went on promotion today among the products
     * this user follows — FollowedProductAlertService owns deciding which, and
     * that the list is not empty. One product is named along with the chain;
     * several are counted, because a notification that lists five products is
     * read as none.
     */
    public void notifyFollowedProductsInPromo(User user, List<FollowedPromo> promos) {
        if (promos.isEmpty()) {
            return;
        }
        if (promos.size() == 1) {
            FollowedPromo promo = promos.get(0);
            String where = promo.retailerName() != null && !promo.retailerName().isBlank()
                    ? " tiene una promo en " + promo.retailerName() + "."
                    : " tiene una promo.";
            sendToUser(user, "Un producto que seguís está en promo", promo.description() + where, DATA_FOLLOWED_PRODUCTS);
            return;
        }
        String others = promos.size() == 2 ? " y " + promos.get(1).description() : " y " + (promos.size() - 1) + " más";
        sendToUser(user, promos.size() + " productos que seguís están en promo",
                promos.get(0).description() + others + ".", DATA_FOLLOWED_PRODUCTS);
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

    /**
     * El job de reactivación para un solo usuario, ahora (lo dispara un ADMIN
     * desde {@code POST /admin/push/jobs/reactivation}). Primero se hace la
     * misma pregunta que la consulta del job programado — que su último ticket
     * sea anterior al corte de inactividad — y después corre exactamente la
     * misma evaluación, con su cooldown y su marca.
     */
    public PushJobOutcome runReactivationForUser(Long userId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(REACTIVATION_DORMANT_DAYS);
        LocalDateTime lastTicket = ticketRepository.findLastTicketCreatedAtByUserId(userId);
        if (lastTicket == null) {
            return PushJobOutcome.skipped("El usuario nunca escaneó un ticket: el job sólo le escribe a quien dejó de escanear.");
        }
        if (!lastTicket.isBefore(cutoff)) {
            return PushJobOutcome.skipped("Escaneó hace menos de " + REACTIVATION_DORMANT_DAYS
                    + " días (último ticket: " + lastTicket.toLocalDate() + "): no está inactivo.");
        }
        return transactionTemplate.execute(status -> processReactivationCandidate(userId));
    }

    /** Gated by offersPushEnabled — this is engagement, not a transactional
     * response to something the user did — and by its own cooldown so a
     * still-dormant user isn't nudged again on every run of the job. */
    private PushJobOutcome processReactivationCandidate(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return PushJobOutcome.skipped("El usuario no existe.");
        }
        if (!user.isOffersPushEnabled()) {
            return PushJobOutcome.skipped("El usuario tiene apagadas las alertas de ofertas.");
        }

        LocalDateTime cooldownCutoff = LocalDateTime.now().minusDays(REACTIVATION_COOLDOWN_DAYS);
        if (user.getLastReactivationNudgeAt() != null && user.getLastReactivationNudgeAt().isAfter(cooldownCutoff)) {
            return PushJobOutcome.skipped("Ya se le mandó un recordatorio hace menos de " + REACTIVATION_COOLDOWN_DAYS
                    + " días (" + user.getLastReactivationNudgeAt().toLocalDate() + ").");
        }

        sendToUser(user, "Te extrañamos",
                "Escaneá tu próximo ticket y seguí ahorrando con OfertAR.",
                DATA_REACTIVATION);
        user.setLastReactivationNudgeAt(LocalDateTime.now());
        userRepository.save(user);
        return PushJobOutcome.sent("Se mandó el recordatorio para volver a escanear.");
    }

    private void sendToUserBlocking(User user, String title, String body, Map<String, String> data) {
        List<PushToken> tokens = pushTokenRepository.findByUserId(user.getId());
        if (tokens.isEmpty()) {
            return;
        }

        try {
            deliver(tokens, title, body, data);
        } catch (Exception e) {
            log.error("Fallo al enviar push a usuario {}: {}", user.getId(), e.getMessage(), e);
        }
    }

    /** El mensaje tal como se manda a Expo: uno solo, para todos los tokens. */
    static Map<String, Object> expoMessage(List<String> tokenValues, String title, String body, Map<String, String> data) {
        return Map.of(
                "to", tokenValues,
                "title", title,
                "body", body,
                "data", data == null ? Map.of() : data,
                "sound", "default",
                "priority", EXPO_PRIORITY
        );
    }

    /** Una llamada a Expo para todos los dispositivos. Las fallas de red se
     * propagan: cada caller decide si las loguea o las devuelve. */
    private List<DeliveryResult> deliver(List<PushToken> tokens, String title, String body, Map<String, String> data) {
        List<String> tokenValues = tokens.stream().map(PushToken::getToken).toList();

        @SuppressWarnings("unchecked")
        var response = (Map<String, Object>) restClient.post()
                .uri(EXPO_PUSH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(expoMessage(tokenValues, title, body, data)))
                .retrieve()
                .body(Map.class);

        return handleTickets(tokens, response);
    }

    /**
     * Expo returns one "ticket" per recipient, in the same order as the `to`
     * array we sent — a DeviceNotRegistered error means the app was
     * uninstalled or the token expired, so that row is dead weight from now
     * on and gets cleaned up instead of failing again on every future send.
     *
     * Devuelve el resultado de cada dispositivo, en el mismo orden. Un
     * dispositivo sin ticket (respuesta vacía o más corta) cuenta como error.
     */
    @SuppressWarnings("unchecked")
    private List<DeliveryResult> handleTickets(List<PushToken> tokens, Map<String, Object> response) {
        Object dataObj = response == null ? null : response.get("data");
        List<?> tickets = dataObj instanceof List<?> list ? list : List.of();
        if (tickets.isEmpty()) {
            log.warn("Expo respondió sin tickets: {}", response);
        }

        List<DeliveryResult> results = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            PushToken token = tokens.get(i);
            if (i >= tickets.size() || !(tickets.get(i) instanceof Map<?, ?> ticket)) {
                results.add(new DeliveryResult(token, false, "Expo no devolvió resultado para este dispositivo.", null));
                continue;
            }
            if (!"error".equals(ticket.get("status"))) {
                results.add(new DeliveryResult(token, true, null, null));
                continue;
            }

            Object detailsObj = ticket.get("details");
            String errorCode = detailsObj instanceof Map<?, ?> details && details.get("error") != null
                    ? String.valueOf(((Map<String, Object>) details).get("error"))
                    : null;
            String message = ticket.get("message") != null ? String.valueOf(ticket.get("message")) : null;
            results.add(new DeliveryResult(token, false, message, errorCode));

            if ("DeviceNotRegistered".equals(errorCode)) {
                pushTokenRepository.deleteByToken(token.getToken());
                log.info("Token de push eliminado (DeviceNotRegistered): {}", token.getToken());
            } else {
                log.warn("Expo devolvio error al enviar push: {}", message);
            }
        }
        return results;
    }
}
