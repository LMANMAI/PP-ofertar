package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.PasswordResetToken;
import ar.edu.ofertAR.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findFirstByUserOrderByCreatedAtDesc(User user);

    void deleteByUser(User user);
}
