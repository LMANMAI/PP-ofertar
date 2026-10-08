package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShoppingListRepository extends JpaRepository<ShoppingList, Long> {

    /** The user's list. The oldest one, so the answer is the same on every
     * call even if two first requests raced and created two. */
    Optional<ShoppingList> findFirstByUserIdOrderByIdAsc(Long userId);
}
