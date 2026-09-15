package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.ChangePasswordRequest;
import ar.edu.ofertAR.dto.request.DeleteAccountRequest;
import ar.edu.ofertAR.dto.request.UpdateProfileRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.dto.response.UserProfileResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FavoriteStoreChainRepository;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final FavoriteStoreChainRepository favoriteStoreChainRepository;
    private final TicketService ticketService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserProfileResponse getProfile(User user) {
        return toResponse(user);
    }

    public AuthResponse updateProfile(User user, UpdateProfileRequest request) {
        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getProfilePicture() != null) {
            user.setProfilePicture(request.getProfilePicture());
        }
        if (request.getAddress() != null) {
            user.setAddress(request.getAddress());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getAlternativeBrandsEnabled() != null) {
            user.setAlternativeBrandsEnabled(request.getAlternativeBrandsEnabled());
        }

        userRepository.save(user);

        String newToken = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(newToken)
                .user(toResponse(user))
                .build();
    }

    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("La contraseña actual es incorrecta");
        }

        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente a la actual");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Transactional
    public void deleteAccount(User user, DeleteAccountRequest request) {
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("La contraseña es incorrecta");
        }

        ticketService.deleteAllTicketsForUser(user);
        favoriteStoreChainRepository.deleteByUserId(user.getId());
        userRepository.delete(user);
    }

    private UserProfileResponse toResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profilePicture(user.getProfilePicture())
                .address(user.getAddress())
                .phone(user.getPhone())
                .alternativeBrandsEnabled(user.isAlternativeBrandsEnabled())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
