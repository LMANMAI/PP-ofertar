package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByUserIdOrderByCreatedAtDesc(Long userId);
<<<<<<< HEAD
=======

    List<Ticket> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<Ticket> findByIdAndUserId(Long id, Long userId);

    Optional<Ticket> findByUserIdAndTicketIdAndStatus(Long userId, String ticketId, TicketStatus status);

    long countByUserIdAndStatus(Long userId, TicketStatus status);

    boolean existsByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
>>>>>>> 248bfcb (Merge pull request #9 from LMANMAI/feature/referral-points)
}
