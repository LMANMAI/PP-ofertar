package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.PushJobRunResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.service.FollowedProductAlertService;
import ar.edu.ofertAR.service.OfferAlertService;
import ar.edu.ofertAR.service.PushJobOutcome;
import ar.edu.ofertAR.service.PushNotificationService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Corre ahora, para un solo usuario, la lógica de un job programado de
 * notificaciones. Sirve para probar un aviso real sin esperar al cron. Sólo
 * ADMIN (ver SecurityConfig: {@code /admin/**}).
 *
 * <ul>
 *   <li>{@code followed}: productos seguidos que entraron en promo ({@code followed.alert-cron}).</li>
 *   <li>{@code offers}: oferta nueva en sus cadenas favoritas ({@code offers.alert-cron}).</li>
 *   <li>{@code reactivation}: recordatorio a quien dejó de escanear ({@code reactivation.cron}).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/admin/push")
@RequiredArgsConstructor
public class AdminPushController {

    private final UserRepository userRepository;
    private final FollowedProductAlertService followedProductAlertService;
    private final OfferAlertService offerAlertService;
    private final PushNotificationService pushNotificationService;

    @PostMapping("/jobs/{job}")
    public ResponseEntity<PushJobRunResponse> runJob(
            @Parameter(schema = @Schema(allowableValues = {"followed", "offers", "reactivation"}))
            @PathVariable String job,
            @RequestParam Long userId
    ) {
        if (!job.equals("followed") && !job.equals("offers") && !job.equals("reactivation")) {
            throw new IllegalArgumentException("Job inválido: '" + job + "'. Valores posibles: followed, offers, reactivation.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe el usuario " + userId + "."));

        log.info("ADMIN corre el job de push '{}' para el usuario {}", job, userId);
        PushJobOutcome outcome = switch (job) {
            case "followed" -> followedProductAlertService.runForUser(userId);
            case "offers" -> offerAlertService.runForUser(user);
            default -> pushNotificationService.runReactivationForUser(userId);
        };

        int deviceCount = pushNotificationService.devicesOf(userId).size();
        String reason = outcome.reason();
        if (outcome.sent() && deviceCount == 0) {
            reason += " Pero el usuario no tiene dispositivos registrados: el aviso no llega a ningún lado.";
        }
        return ResponseEntity.ok(new PushJobRunResponse(job, userId, outcome.sent(), reason, deviceCount));
    }
}
