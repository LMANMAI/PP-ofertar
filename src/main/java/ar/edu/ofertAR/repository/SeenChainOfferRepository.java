package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SeenChainOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeenChainOfferRepository extends JpaRepository<SeenChainOffer, Long> {

    List<SeenChainOffer> findByChainSlugAndOfferIdIn(String chainSlug, Collection<String> offerIds);
}
