package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.UserPaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPaymentMethodRepository extends JpaRepository<UserPaymentMethod, Long> {

    List<UserPaymentMethod> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    /** Sólo si es del usuario: un id ajeno da vacío, igual que uno inexistente. */
    Optional<UserPaymentMethod> findByIdAndUserId(Long id, Long userId);
}
