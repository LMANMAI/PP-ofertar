package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.ProductoImagen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProductoImagenRepository extends JpaRepository<ProductoImagen, String> {

    /**
     * EANs candidatos a enriquecer, priorizando los productos que aparecen en
     * más comercios (proxy de "lo que la gente realmente busca").
     *
     * <p>Devuelve los que nunca se consultaron, y los que fallaron hace rato
     * y todavía no agotaron los reintentos. Los OK no vuelven a salir.
     */
    @Query(value = """
            SELECT p.ean
            FROM sepa_producto p
            LEFT JOIN producto_imagen i ON i.ean = p.ean
            WHERE i.ean IS NULL
               OR (i.estado <> 'OK'
                   AND i.intentos < :maxIntentos
                   AND i.actualizado_at < :reintentarAntesDe)
            ORDER BY p.cantidad_ofertas DESC
            LIMIT :limite
            """, nativeQuery = true)
    List<String> findEansPendientes(@Param("reintentarAntesDe") LocalDateTime reintentarAntesDe,
                                    @Param("maxIntentos") int maxIntentos,
                                    @Param("limite") int limite);

    long countByEstado(ar.edu.ofertAR.model.EstadoImagen estado);
}
