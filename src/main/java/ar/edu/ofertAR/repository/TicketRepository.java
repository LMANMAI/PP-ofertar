package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** Users whose most recent ticket (any status — this is about scanning
     * activity, not successful reads) predates the cutoff. Users with no
     * tickets at all never match: they haven't gone dormant, they just
     * haven't started, which is a different problem than this job solves. */
    @Query("SELECT t.user.id FROM Ticket t GROUP BY t.user.id HAVING MAX(t.createdAt) < :cutoff")
    List<Long> findUserIdsWithLastTicketBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Por cada código de barras distinto en los ítems de los tickets del
     * usuario con ese estado, la fecha del ticket más reciente que lo trae. Una
     * sola query para toda la lista de productos seguidos (un usuario tiene a lo
     * sumo 50 tickets). Los ítems sin código quedan afuera: sin código no hay
     * con qué saber si son el producto seguido. */
    @Query("SELECT new ar.edu.ofertAR.repository.BarcodeLastPurchase(i.barcode, MAX(t.createdAt)) "
            + "FROM TicketItem i JOIN i.ticket t "
            + "WHERE t.user.id = :userId AND t.status = :status "
            + "AND i.barcode IS NOT NULL AND TRIM(i.barcode) <> '' "
            + "GROUP BY i.barcode")
    List<BarcodeLastPurchase> findLastPurchaseByBarcode(
            @Param("userId") Long userId, @Param("status") TicketStatus status);
>>>>>>> 1718c2a (feat: seguir productos (MVP4) (#27))
}
