package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.TicketItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketItemRepository extends JpaRepository<TicketItem, Long> {

    List<TicketItem> findByTicketId(Long ticketId);
}
