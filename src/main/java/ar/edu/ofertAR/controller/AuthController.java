package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.ForgotPasswordRequest;
import ar.edu.ofertAR.dto.request.LoginRequest;
import ar.edu.ofertAR.dto.request.RegisterRequest;
import ar.edu.ofertAR.dto.request.ResetPasswordRequest;
import ar.edu.ofertAR.dto.request.VerifyResetCodeRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.service.AuthService;
import ar.edu.ofertAR.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authService.login(request, http.getRemoteAddr()));
    }

    /** Same 204 whether or not the email has an account. */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-reset-code")
    public ResponseEntity<Void> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        passwordResetService.verifyCode(request.getEmail(), request.getCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}
