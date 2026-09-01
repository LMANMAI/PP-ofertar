package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Ticket> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<Ticket> findByIdAndUserId(Long id, Long userId);

    Optional<Ticket> findByUserIdAndTicketIdAndStatus(Long userId, String ticketId, TicketStatus status);
}
