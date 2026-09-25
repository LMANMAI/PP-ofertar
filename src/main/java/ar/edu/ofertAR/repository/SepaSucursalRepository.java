package ar.edu.ofertAR.repository;

import ar.edu.ofertAR.model.SepaSucursal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SepaSucursalRepository extends JpaRepository<SepaSucursal, Long> {

    /** Sucursales dentro de un rectángulo; el círculo exacto lo recorta quien llama. */
    @Query("select s from SepaSucursal s where s.latitud between :minLat and :maxLat "
            + "and s.longitud between :minLng and :maxLng")
    List<SepaSucursal> findInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                 @Param("minLng") double minLng, @Param("maxLng") double maxLng);
}
