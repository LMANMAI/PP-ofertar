package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.LoginRequest;
import ar.edu.ofertAR.dto.request.RegisterRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.dto.response.UserProfileResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.security.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final PointsService pointsService;
    private final LoginAttemptService loginAttempts;

    public AuthResponse register(RegisterRequest request, String ip) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .referralCode(pointsService.generateUniqueReferralCode())
                .build();

        userRepository.save(user);
        pointsService.applyReferralSignup(user, request.getReferralCode());

        String token = jwtService.generateToken(user);
        log.info("AUTH registro userId={} ip={}", user.getId(), ip);

        return buildAuthResponse(user, token);
    }

    public AuthResponse login(LoginRequest request, String ip) {
        loginAttempts.verificarPermitido(ip, request.getEmail());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            loginAttempts.registrarFallo(ip, request.getEmail());
            log.warn("AUTH login fallido ip={} email#={}", ip, LoginAttemptService.huella(request.getEmail()));
            throw e;
        }
        loginAttempts.registrarExito(request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        String token = jwtService.generateToken(user);
        log.info("AUTH login ok userId={} ip={}", user.getId(), ip);

        return buildAuthResponse(user, token);
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        UserProfileResponse profile = UserProfileResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profilePicture(user.getProfilePicture())
                .address(user.getAddress())
                .alternativeBrandsEnabled(user.isAlternativeBrandsEnabled())
                .referralCode(user.getReferralCode())
                .points(user.getPoints())
                .offersPushEnabled(user.isOffersPushEnabled())
                .createdAt(user.getCreatedAt())
                .build();

        return AuthResponse.builder()
                .token(token)
                .user(profile)
                .build();
    }
}
