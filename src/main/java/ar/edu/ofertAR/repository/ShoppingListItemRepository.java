package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.ShoppingListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, Long> {

    List<ShoppingListItem> findByListIdOrderByCreatedAtAscIdAsc(Long listId);

    Optional<ShoppingListItem> findByListIdAndEan(Long listId, String ean);

    Optional<ShoppingListItem> findByIdAndListId(Long id, Long listId);

    long countByListId(Long listId);

    void deleteByListIdAndCheckedTrue(Long listId);
}
