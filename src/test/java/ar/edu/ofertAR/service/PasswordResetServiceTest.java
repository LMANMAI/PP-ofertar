package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.PasswordResetToken;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PasswordResetTokenRepository;
import ar.edu.ofertAR.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordResetMailer mailer;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository, encoder, mailer);
        user = User.builder().id(1L).name("Ana").email("ana@correo.com").password(encoder.encode("vieja")).build();
    }

    private PasswordResetToken tokenFor(String code) {
        return PasswordResetToken.builder()
                .user(user)
                .codeHash(encoder.encode(code))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    @Test
    @DisplayName("un email sin cuenta no envía nada y no falla")
    void unknownEmailDoesNothing() {
        when(userRepository.findByEmail("nadie@correo.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.requestReset("nadie@correo.com"));

        verifyNoInteractions(mailer);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("guarda solo el hash y manda el código de 6 dígitos por correo")
    void requestStoresHashAndMailsCode() {
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());

        service.requestReset("ana@correo.com");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendCode(eq(user), code.capture(), anyInt());
        assertTrue(code.getValue().matches("\\d{6}"));

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        assertNotEquals(code.getValue(), saved.getValue().getCodeHash());
        assertTrue(encoder.matches(code.getValue(), saved.getValue().getCodeHash()));
    }

    @Test
    @DisplayName("un segundo pedido dentro del minuto no genera otro código")
    void requestWithinCooldownIsIgnored() {
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(tokenFor("123456")));

        service.requestReset("ana@correo.com");

        verifyNoInteractions(mailer);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("con el código correcto cambia la contraseña y borra el código")
    void correctCodeResetsPassword() {
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(tokenFor("123456")));

        service.resetPassword("ana@correo.com", "123456", "NuevaClave1!");

        assertTrue(encoder.matches("NuevaClave1!", user.getPassword()));
        verify(tokenRepository).deleteByUser(user);
    }

    @Test
    @DisplayName("un código incorrecto suma un intento y no cambia nada")
    void wrongCodeCountsAttempt() {
        PasswordResetToken token = tokenFor("123456");
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(token));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("ana@correo.com", "000000", "NuevaClave1!"));

        assertEquals(PasswordResetService.INVALID_CODE, e.getMessage());
        assertEquals(1, token.getAttempts());
        verify(tokenRepository).save(token);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("agotados los intentos, ni el código correcto sirve")
    void tooManyAttemptsRejectsEvenCorrectCode() {
        PasswordResetToken token = tokenFor("123456");
        token.setAttempts(PasswordResetService.MAX_ATTEMPTS);
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(token));

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("ana@correo.com", "123456", "NuevaClave1!"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("un código vencido se rechaza con el mismo mensaje")
    void expiredCodeIsRejected() {
        PasswordResetToken token = tokenFor("123456");
        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByEmail("ana@correo.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(token));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.verifyCode("ana@correo.com", "123456"));

        assertEquals(PasswordResetService.INVALID_CODE, e.getMessage());
    }

    @Test
    @DisplayName("verificar un email sin cuenta da el mismo error que un código malo")
    void verifyUnknownEmailLooksLikeWrongCode() {
        when(userRepository.findByEmail("nadie@correo.com")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.verifyCode("nadie@correo.com", "123456"));

        assertEquals(PasswordResetService.INVALID_CODE, e.getMessage());
    }
}
