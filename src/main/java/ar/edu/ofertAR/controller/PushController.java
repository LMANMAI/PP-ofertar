package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.PushTestRequest;
import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.dto.response.PushStatusResponse;
import ar.edu.ofertAR.dto.response.PushTestResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.PushDiagnosticsService;
import ar.edu.ofertAR.service.PushNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushController {

    private final PushNotificationService pushNotificationService;
    private final PushDiagnosticsService pushDiagnosticsService;

    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RegisterPushTokenRequest request
    ) {
        pushNotificationService.registerToken(user, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Le manda al usuario logueado (y sólo a él) una notificación de prueba a
     * todos sus dispositivos, y devuelve qué contestó Expo por cada uno. Una
     * cada 10 segundos por usuario (429 si no). Sin dispositivos, 200 con
     * {@code devices} vacío.
     */
    @PostMapping("/test")
    public ResponseEntity<PushTestResponse> test(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) PushTestRequest request
    ) {
        return ResponseEntity.ok(pushDiagnosticsService.sendTest(user, request == null ? null : request.getType()));
    }

    /** Dispositivos registrados (sin el token completo) y preferencias de notificación. */
    @GetMapping("/status")
    public ResponseEntity<PushStatusResponse> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pushDiagnosticsService.status(user));
    }
}
