package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SepaProducto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SepaProductoRepository extends JpaRepository<SepaProducto, Long> {

    Optional<SepaProducto> findByEan(String ean);

    Page<SepaProducto> findByDescripcionContainingIgnoreCaseOrMarcaContainingIgnoreCase(
            String descripcion, String marca, Pageable pageable);
}
