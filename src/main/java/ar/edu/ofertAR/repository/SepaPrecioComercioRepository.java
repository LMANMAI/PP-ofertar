package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SepaPrecioComercio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SepaPrecioComercioRepository extends JpaRepository<SepaPrecioComercio, Long> {

    /** Comercios donde está el producto, del más barato al más caro. */
    List<SepaPrecioComercio> findByEanOrderByPrecioMinimoAsc(String ean);
}
