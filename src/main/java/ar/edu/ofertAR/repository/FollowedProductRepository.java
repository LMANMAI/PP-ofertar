package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.FollowedProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FollowedProductRepository extends JpaRepository<FollowedProduct, Long> {

    List<FollowedProduct> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<FollowedProduct> findByUserIdAndEan(Long userId, String ean);

    long countByUserId(Long userId);

    @Query("SELECT DISTINCT f.user.id FROM FollowedProduct f")
    List<Long> findDistinctUserIds();
}
