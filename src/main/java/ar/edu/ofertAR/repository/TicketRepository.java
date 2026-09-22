package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Ticket> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<Ticket> findByIdAndUserId(Long id, Long userId);

    Optional<Ticket> findByUserIdAndTicketIdAndStatus(Long userId, String ticketId, TicketStatus status);

    long countByUserIdAndStatus(Long userId, TicketStatus status);

    boolean existsByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);

    /** Users whose most recent ticket (any status — this is about scanning
     * activity, not successful reads) predates the cutoff. Users with no
     * tickets at all never match: they haven't gone dormant, they just
     * haven't started, which is a different problem than this job solves. */
    @Query("SELECT t.user.id FROM Ticket t GROUP BY t.user.id HAVING MAX(t.createdAt) < :cutoff")
    List<Long> findUserIdsWithLastTicketBefore(@Param("cutoff") LocalDateTime cutoff);
}
