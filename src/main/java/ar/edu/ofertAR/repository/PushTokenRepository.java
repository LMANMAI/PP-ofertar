package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    List<PushToken> findByUserId(Long userId);

    Optional<PushToken> findByToken(String token);

    /** Transacción propia: se llama al procesar la respuesta de Expo, que corre
     * en el executor de push (o en el request de /push/test) fuera de toda
     * transacción, y un delete derivado necesita una de escritura. */
    @Transactional
    void deleteByToken(String token);
}
