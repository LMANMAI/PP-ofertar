package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.LoginRequest;
import ar.edu.ofertAR.dto.request.RegisterRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.service.AuthService;
<<<<<<< HEAD
=======
import ar.edu.ofertAR.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
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
}
