package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.FavoriteStoreChain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriteStoreChainRepository extends JpaRepository<FavoriteStoreChain, Long> {

    List<FavoriteStoreChain> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
