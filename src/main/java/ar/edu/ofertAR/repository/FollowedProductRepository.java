package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.FollowedProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FollowedProductRepository extends JpaRepository<FollowedProduct, Long> {

    List<FollowedProduct> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    @Query("SELECT DISTINCT f.user.id FROM FollowedProduct f")
    List<Long> findDistinctUserIds();
}
