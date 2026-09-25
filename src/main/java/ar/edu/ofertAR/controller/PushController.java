package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.model.User;
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

    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RegisterPushTokenRequest request
    ) {
        pushNotificationService.registerToken(user, request);
        return ResponseEntity.noContent().build();
    }
}
