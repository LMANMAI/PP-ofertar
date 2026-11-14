package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.HabitualProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HabitualProductRepository extends JpaRepository<HabitualProduct, Long> {

    List<HabitualProduct> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
