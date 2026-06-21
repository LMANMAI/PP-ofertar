package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.LoginRequest;
import ar.edu.ofertAR.dto.request.RegisterRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.dto.response.UserProfileResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        userRepository.save(user);

        String token = jwtService.generateToken(user);

        return buildAuthResponse(user, token);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        String token = jwtService.generateToken(user);

        return buildAuthResponse(user, token);
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        UserProfileResponse profile = UserProfileResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profilePicture(user.getProfilePicture())
                .address(user.getAddress())
                .phone(user.getPhone())
                .createdAt(user.getCreatedAt())
                .build();

        return AuthResponse.builder()
                .token(token)
                .user(profile)
                .build();
    }
}
