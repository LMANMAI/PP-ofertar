package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.Referral;
import ar.edu.ofertAR.model.ReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, Long> {

    Optional<Referral> findByReferredIdAndStatus(Long referredId, ReferralStatus status);

    /** Activations already recorded this month for a referrer's antiabuse
     * cap — a null activatedAt (still PENDING) never matches a range, so
     * this doubles as an implicit status filter. */
    long countByReferrerIdAndActivatedAtGreaterThanEqualAndActivatedAtLessThan(
            Long referrerId, LocalDateTime from, LocalDateTime to);

    List<Referral> findByStatusAndActivatedAtBetween(
            ReferralStatus status, LocalDateTime from, LocalDateTime to);
}
