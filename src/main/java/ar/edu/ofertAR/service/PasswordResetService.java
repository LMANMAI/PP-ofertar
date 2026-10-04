package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.PasswordResetToken;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PasswordResetTokenRepository;
import ar.edu.ofertAR.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * "Forgot password" by emailed 6-digit code. Three rules keep a short code
 * safe: it expires ({@link #TTL_MINUTES}), it stops accepting guesses after
 * {@link #MAX_ATTEMPTS} wrong ones, and only its hash is stored.
 *
 * Every failure is the same message, and {@link #requestReset} never says
 * whether the email has an account, so none of this can be used to find out
 * who is registered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final int TTL_MINUTES = 15;
    static final int MAX_ATTEMPTS = 5;
    static final int RESEND_COOLDOWN_SECONDS = 60;
    static final String INVALID_CODE = "El código es incorrecto o venció. Pedí uno nuevo si hace falta.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailer mailer;

    /** Always returns normally, whether or not the email has an account. */
    @Transactional
    public void requestReset(String email) {
        Optional<User> found = userRepository.findByEmail(email.trim());
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();

        Optional<PasswordResetToken> last = tokenRepository.findFirstByUserOrderByCreatedAtDesc(user);
        if (last.isPresent()
                && last.get().getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(RESEND_COOLDOWN_SECONDS))) {
            return;
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        tokenRepository.deleteByUser(user);
        tokenRepository.flush();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES))
                .build());
        mailer.sendCode(user, code, TTL_MINUTES);
    }

    /** Checks the code without using it up, so the app can reject a wrong one
     * before asking for the new password. Wrong guesses still count. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void verifyCode(String email, String code) {
        validate(email, code);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void resetPassword(String email, String code, String newPassword) {
        PasswordResetToken token = validate(email, code);
        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenValidFrom(java.time.Instant.now());
        userRepository.save(user);
        tokenRepository.deleteByUser(user);
        log.info("AUTH contraseña restablecida por código userId={}", user.getId());
    }

    // noRollbackFor on the callers: the attempt counter has to be saved even
    // though the wrong-code path throws, or the limit would never trip.
    private PasswordResetToken validate(String email, String code) {
        User user = userRepository.findByEmail(email.trim())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
        PasswordResetToken token = tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));

        if (token.getExpiresAt().isBefore(LocalDateTime.now()) || token.getAttempts() >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException(INVALID_CODE);
        }
        if (!passwordEncoder.matches(code, token.getCodeHash())) {
            token.setAttempts(token.getAttempts() + 1);
            tokenRepository.save(token);
            throw new IllegalArgumentException(INVALID_CODE);
        }
        return token;
    }
}
