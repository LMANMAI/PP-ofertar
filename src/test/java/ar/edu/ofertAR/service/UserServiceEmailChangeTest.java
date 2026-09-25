package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.UpdateProfileRequest;
import ar.edu.ofertAR.dto.response.AuthResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService: cambio de correo")
class UserServiceEmailChangeTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;

    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, passwordEncoder, jwtService);
        user = User.builder().name("Ana").email("ana@old.com").password("hash").build();
        user.setId(1L);
    }

    private static UpdateProfileRequest request(String email, String currentPassword) {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setEmail(email);
        r.setCurrentPassword(currentPassword);
        return r;
    }

    @Test
    @DisplayName("correo nuevo con la contraseña correcta: se guarda y el token sale con el correo nuevo")
    void newEmailWithCorrectPassword_isSavedAndTokenIssuedAfter() {
        when(passwordEncoder.matches("secreta", "hash")).thenReturn(true);
        when(userRepository.existsByEmail("ana@new.com")).thenReturn(false);
        when(jwtService.generateToken(any(User.class))).thenAnswer(inv -> "token-for-" + ((User) inv.getArgument(0)).getEmail());

        AuthResponse response = service.updateProfile(user, request("  ana@new.com ", "secreta"));

        assertEquals("ana@new.com", user.getEmail());
        assertEquals("ana@new.com", response.getUser().getEmail());
        assertEquals("token-for-ana@new.com", response.getToken());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("correo nuevo con contraseña incorrecta: se rechaza y no se guarda nada")
    void newEmailWithWrongPassword_isRejected() {
        when(passwordEncoder.matches("mala", "hash")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(user, request("ana@new.com", "mala")));

        assertEquals("ana@old.com", user.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("correo nuevo sin contraseña: se rechaza")
    void newEmailWithoutPassword_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(user, request("ana@new.com", null)));

        assertEquals("ana@old.com", user.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("correo ya usado por otra cuenta: se rechaza")
    void emailAlreadyRegistered_isRejected() {
        when(passwordEncoder.matches("secreta", "hash")).thenReturn(true);
        when(userRepository.existsByEmail("taken@x.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(user, request("taken@x.com", "secreta")));

        assertEquals("ana@old.com", user.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("mismo correo (aunque cambie el caso): no pide contraseña ni consulta si está en uso")
    void sameEmailIgnoringCase_isANoOp() {
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        service.updateProfile(user, request("ANA@old.com", null));

        assertEquals("ana@old.com", user.getEmail());
        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    @DisplayName("sin correo en el pedido: el correo queda igual")
    void noEmailInRequest_leavesEmailAlone() {
        when(jwtService.generateToken(any(User.class))).thenReturn("token");
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setName("Ana Maria");

        service.updateProfile(user, r);

        assertEquals("ana@old.com", user.getEmail());
        assertEquals("Ana Maria", user.getName());
        verifyNoInteractions(passwordEncoder);
    }
}
