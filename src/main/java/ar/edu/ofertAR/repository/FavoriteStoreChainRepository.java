package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.FavoriteStoreChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FavoriteStoreChainRepository extends JpaRepository<FavoriteStoreChain, Long> {

    List<FavoriteStoreChain> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    List<FavoriteStoreChain> findByChainSlug(String chainSlug);

    @Query("SELECT DISTINCT f.chainSlug FROM FavoriteStoreChain f")
    List<String> findDistinctChainSlugs();
}
