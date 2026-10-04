package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SepaProducto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SepaProductoRepository extends JpaRepository<SepaProducto, Long> {

    Optional<SepaProducto> findByEan(String ean);

    Page<SepaProducto> findByDescripcionContainingIgnoreCaseOrMarcaContainingIgnoreCase(
            String descripcion, String marca, Pageable pageable);

    /**
     * Búsqueda por palabras con el índice FULLTEXT (descripcion, marca), en modo booleano
     * ("+leche* +desl*"). El orden va en la query: el Pageable no debe traer Sort.
     */
    @Query(value = "SELECT * FROM sepa_producto WHERE MATCH(descripcion, marca) AGAINST (:q IN BOOLEAN MODE) "
            + "ORDER BY cantidad_ofertas DESC",
            countQuery = "SELECT COUNT(*) FROM sepa_producto WHERE MATCH(descripcion, marca) AGAINST (:q IN BOOLEAN MODE)",
            nativeQuery = true)
    Page<SepaProducto> buscarTexto(@Param("q") String q, Pageable pageable);
}
