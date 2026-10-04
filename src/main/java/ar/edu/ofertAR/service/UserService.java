package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.ChangePasswordRequest;
import ar.edu.ofertAR.dto.request.UpdateProfileRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.dto.response.UserProfileResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserProfileResponse getProfile(User user) {
        return toResponse(user);
    }

    public AuthResponse updateProfile(User user, UpdateProfileRequest request) {
        // Checked before anything else is applied, so a rejected email change
        // does not leave the other fields half-updated on the entity.
        applyEmailChange(user, request);

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getProfilePicture() != null) {
            user.setProfilePicture(request.getProfilePicture());
        }
        if (request.getAddress() != null) {
            user.setAddress(request.getAddress());
        }
        if (request.getAlternativeBrandsEnabled() != null) {
            user.setAlternativeBrandsEnabled(request.getAlternativeBrandsEnabled());
        }
        if (request.getOffersPushEnabled() != null) {
            user.setOffersPushEnabled(request.getOffersPushEnabled());
        }

        userRepository.save(user);

        String newToken = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(newToken)
                .user(toResponse(user))
                .build();
    }

    /**
     * The email is the login identity (the JWT subject) and the destination of
     * password reset codes, so changing it needs the current password. Same
     * address, ignoring case, is a no-op and asks for nothing. The token the
     * caller gets back is issued after this, so it already carries the new email.
     */
    private void applyEmailChange(User user, UpdateProfileRequest request) {
        if (request.getEmail() == null) {
            return;
        }
        String newEmail = request.getEmail().trim();
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            return;
        }
        if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
            throw new IllegalArgumentException("Ingresá tu contraseña actual para cambiar el correo");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("La contraseña actual es incorrecta");
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw new IllegalArgumentException("El email ya está registrado");
        }
        user.setEmail(newEmail);
    }

    /** Devuelve un token nuevo: el cambio invalida los anteriores, así que quien
     * cambia la clave no se queda afuera de su propia sesión. */
    public AuthResponse changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("La contraseña actual es incorrecta");
        }

        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente a la actual");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setTokenValidFrom(java.time.Instant.now());
        userRepository.save(user);
        log.info("AUTH contraseña cambiada userId={}", user.getId());

        return AuthResponse.builder()
                .token(jwtService.generateToken(user))
                .user(toResponse(user))
                .build();
    }

    private UserProfileResponse toResponse(User user) {
        return UserProfileResponse.builder()
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
    }
}
