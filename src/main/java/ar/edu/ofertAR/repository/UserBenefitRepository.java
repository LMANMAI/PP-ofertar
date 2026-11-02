package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.UserBenefit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserBenefitRepository extends JpaRepository<UserBenefit, UserBenefit.Key> {

    List<UserBenefit> findByUserIdOrderByBenefitAsc(Long userId);
}
