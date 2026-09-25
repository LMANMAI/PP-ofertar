package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SepaPrecioGrupo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SepaPrecioGrupoRepository extends JpaRepository<SepaPrecioGrupo, Long> {

    /** Los precios de un producto en los comercios dados, del más barato al más caro. */
    List<SepaPrecioGrupo> findByEanAndComercioIdInOrderByPrecioAsc(String ean, Collection<String> comercioIds);
}
