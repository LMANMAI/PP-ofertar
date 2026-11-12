package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.HorariosResponse;

import java.util.Collection;
import java.util.Objects;

/**
 * Detecta el "00:00 a 24:00" puesto de relleno por un comercio entero.
 *
 * <p>Un comercio que declara 24 horas en todas sus sucursales y todos los días
 * probablemente no informa su horario: completa la columna obligatoria con lo que
 * no puede ser falso. Mostrar "Abierto" en base a eso sería inventar, así que se
 * toma como no informado.
 *
 * <p>La regla es a propósito estrecha: con una sola sucursal que declare otra cosa
 * el comercio tiene variación real y se le cree. Medido en el dataset de septiembre
 * 2026 (domingo, lunes y martes dan lo mismo):
 * <ul>
 *   <li>Estación Lima (id 4): su única sucursal, 24 h los siete días. Es el único
 *       comercio que cae en la regla.</li>
 *   <li>Axion Energy (23): 52 de 55 en 24 h, 3 de 06:00 a 22:00. Deheza (3): 38 de 41.
 *       Son estaciones de servicio, donde abrir 24 h es verosímil: no aplica.</li>
 *   <li>Changomas (11): tres hipermercados en 24 h (Santa Fe, Neuquén, Mendoza) entre
 *       91 sucursales; DIA (15) y La Anónima (2): solo sus tiendas online. No aplica.</li>
 *   <li>Carrefour (10) no declara 24 h en ninguna sucursal.</li>
 * </ul>
 */
public final class HorariosPlaceholder {

    private HorariosPlaceholder() {
    }

    /**
     * @param horariosDelComercio los de todas las sucursales de UN comercio; las que no
     *                            informan nada (null) no cuentan ni a favor ni en contra
     */
    public static boolean esRelleno24h(Collection<HorariosResponse> horariosDelComercio) {
        if (horariosDelComercio == null) {
            return false;
        }
        long informadas = horariosDelComercio.stream().filter(Objects::nonNull).count();
        return informadas > 0 && horariosDelComercio.stream()
                .filter(Objects::nonNull)
                .allMatch(HorariosResponse::esTodoElDiaTodaLaSemana);
    }
}
