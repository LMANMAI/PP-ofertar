package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.PointsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsTransactionRepository extends JpaRepository<PointsTransaction, Long> {

    List<PointsTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);
}
