package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RedeemRequest;
import ar.edu.ofertAR.dto.response.PointsBalanceResponse;
import ar.edu.ofertAR.dto.response.PointsHistoryEntryResponse;
import ar.edu.ofertAR.model.PointsReason;
import ar.edu.ofertAR.model.PointsTransaction;
import ar.edu.ofertAR.model.Referral;
import ar.edu.ofertAR.model.ReferralStatus;
import ar.edu.ofertAR.model.Reward;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PointsTransactionRepository;
import ar.edu.ofertAR.repository.ReferralRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    private final UserRepository userRepository;
    private final ReferralRepository referralRepository;
    private final PointsTransactionRepository pointsTransactionRepository;
    private final TicketRepository ticketRepository;
    private final TransactionTemplate transactionTemplate;
    private final PushNotificationService pushNotificationService;

    private static final int POINTS_REFERRED_SIGNUP = 20;
    private static final int POINTS_REFERRER_ACTIVATION = 50;
    private static final int POINTS_REFERRER_RETENTION = 30;
    private static final int REFERRAL_MONTHLY_CAP = 15;

    /** Minimum days after activation before a referral is even considered
     * for the retention bonus. */
    private static final int RETENTION_DAYS = 30;
    /** How much later than the referral's own creation the referred user's
     * evidence-of-activity ticket has to be. */
    private static final int RETENTION_EVIDENCE_DAYS = 25;
    /** Past this many days without qualifying, stop re-evaluating — it can
     * still complete manually later, it just won't be picked up by the job. */
    private static final int RETENTION_GIVE_UP_DAYS = 60;

    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Codigo de referido ──────────────────────────────────────────────

    public String generateUniqueReferralCode() {
        String code;
        do {
            code = randomCode();
        } while (userRepository.existsByReferralCode(code));
        return code;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ── Alta con codigo de invitacion ───────────────────────────────────

    /**
     * Called right after a new user is persisted. A missing, unknown, or
     * self-referencing code is never an error — it just means no referral
     * gets recorded, silently.
     */
    @Transactional
    public void applyReferralSignup(User newUser, String referralCodeUsed) {
        if (referralCodeUsed == null || referralCodeUsed.isBlank()) {
            return;
        }

        User referrer = userRepository.findByReferralCode(referralCodeUsed.trim()).orElse(null);
        if (referrer == null || referrer.getId().equals(newUser.getId())) {
            log.info("Codigo de invitacion invalido o propio ignorado en el alta de {}", newUser.getId());
            return;
        }

        referralRepository.save(Referral.builder()
                .referrer(referrer)
                .referred(newUser)
                .status(ReferralStatus.PENDING)
                .build());

        credit(newUser, PointsReason.REFERRAL_SIGNUP, POINTS_REFERRED_SIGNUP,
                "Te registraste con un código de invitación");
    }

    // ── Activacion al primer ticket ─────────────────────────────────────

    /** Called once a ticket has just been saved with status PROCESSED. */
    @Transactional
    public void onTicketProcessed(Ticket ticket) {
        User user = ticket.getUser();
        long processedTickets = ticketRepository.countByUserIdAndStatus(user.getId(), TicketStatus.PROCESSED);
        if (processedTickets != 1) {
            // Not this user's first ticket to make it through OCR.
            return;
        }

        referralRepository.findByReferredIdAndStatus(user.getId(), ReferralStatus.PENDING)
                .ifPresent(this::activate);
    }

    private void activate(Referral referral) {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1);
        long activationsThisMonth = referralRepository
                .countByReferrerIdAndActivatedAtGreaterThanEqualAndActivatedAtLessThan(
                        referral.getReferrer().getId(), monthStart, monthEnd);

        if (activationsThisMonth < REFERRAL_MONTHLY_CAP) {
            credit(referral.getReferrer(), PointsReason.REFERRAL_ACTIVATED, POINTS_REFERRER_ACTIVATION,
                    "Tu amigo activó su cuenta");
            pushNotificationService.notifyReferralActivated(referral.getReferrer());
        } else {
            log.info("Referral {} activado sin puntos: referrer {} ya alcanzo el tope mensual ({})",
                    referral.getId(), referral.getReferrer().getId(), REFERRAL_MONTHLY_CAP);
        }

        referral.setStatus(ReferralStatus.ACTIVATED);
        referral.setActivatedAt(LocalDateTime.now());
        referralRepository.save(referral);
    }

    // ── Retencion — job diario ───────────────────────────────────────────

    @Scheduled(cron = "${referral.retention-cron:0 0 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void runRetentionJob() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(RETENTION_GIVE_UP_DAYS);
        LocalDateTime windowEnd = now.minusDays(RETENTION_DAYS);

        List<Referral> candidates = referralRepository.findByStatusAndActivatedAtBetween(
                ReferralStatus.ACTIVATED, windowStart, windowEnd);
        log.info("Job de retencion de referidos: {} candidatos entre {} y {}",
                candidates.size(), windowStart, windowEnd);

        for (Referral candidate : candidates) {
            Long referralId = candidate.getId();
            try {
                transactionTemplate.executeWithoutResult(status -> processRetentionCandidate(referralId, now));
            } catch (Exception e) {
                log.error("Fallo evaluando retencion del referral {}: {}", referralId, e.getMessage(), e);
            }
        }
    }

    /** Re-fetches inside its own transaction — same defensive reasoning as
     * TicketProcessingService re-fetching the ticket: state may have moved
     * between the query above and this run. */
    private void processRetentionCandidate(Long referralId, LocalDateTime now) {
        Referral referral = referralRepository.findById(referralId).orElse(null);
        if (referral == null || referral.getStatus() != ReferralStatus.ACTIVATED) {
            return;
        }

        LocalDateTime evidenceCutoff = referral.getCreatedAt().plusDays(RETENTION_EVIDENCE_DAYS);
        boolean stillActive = ticketRepository.existsByUserIdAndCreatedAtAfter(
                referral.getReferred().getId(), evidenceCutoff);
        if (!stillActive) {
            // Leaves it ACTIVATED; the job's own 60-day window above is
            // what eventually stops retrying it.
            return;
        }

        credit(referral.getReferrer(), PointsReason.REFERRAL_RETAINED, POINTS_REFERRER_RETENTION,
                "Tu amigo se quedó en OfertAR");
        pushNotificationService.notifyReferralRetained(referral.getReferrer());
        referral.setStatus(ReferralStatus.RETAINED);
        referral.setRetainedAt(now);
        referralRepository.save(referral);
    }

    // ── Consulta y canje ─────────────────────────────────────────────────

    public PointsBalanceResponse getBalance(User user) {
        return toBalanceResponse(user);
    }

    public List<PointsHistoryEntryResponse> getHistory(User user) {
        return pointsTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    @Transactional
    public PointsBalanceResponse redeem(User user, RedeemRequest request) {
        Reward reward = Reward.fromId(request.getRewardId())
                .orElseThrow(() -> new IllegalArgumentException("La recompensa no existe"));

        if (user.getPoints() < reward.getCost()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No tenés puntos suficientes para este canje");
        }

        credit(user, PointsReason.REDEEM, -reward.getCost(), "Canje: " + reward.getTitle());

        return toBalanceResponse(user);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** The one place that touches both a balance and its ledger row,
     * always together — the invariant User.points depends on. */
    private void credit(User user, PointsReason reason, int points, String description) {
        user.setPoints(user.getPoints() + points);
        userRepository.save(user);
        pointsTransactionRepository.save(PointsTransaction.builder()
                .user(user)
                .reason(reason)
                .description(description)
                .points(points)
                .build());
    }

    private PointsBalanceResponse toBalanceResponse(User user) {
        return PointsBalanceResponse.builder()
                .balance(user.getPoints())
                .referralCode(user.getReferralCode())
                .build();
    }

    private PointsHistoryEntryResponse toHistoryEntry(PointsTransaction tx) {
        return PointsHistoryEntryResponse.builder()
                .id(tx.getId())
                .reason(tx.getReason())
                .description(tx.getDescription())
                .points(tx.getPoints())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
