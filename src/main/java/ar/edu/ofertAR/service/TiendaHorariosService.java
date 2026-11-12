package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import ar.edu.ofertAR.model.SepaSucursal;
import ar.edu.ofertAR.repository.SepaSucursalRepository;
import ar.edu.ofertAR.service.horario.HorariosJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Le pone horario a las tiendas del mapa ({@code /stores/nearby}).
 *
 * <p>El mapa sale del scraper, que no sabe de horarios; SEPA sí, pero son dos
 * listados distintos, sin un id en común. Se cruzan por cercanía: cada tienda toma
 * el horario de la sucursal SEPA <b>de su misma cadena</b> más cercana, solo si está
 * tan cerca que no puede ser otra. Sin un match confiable la tienda va sin horario:
 * mejor "no sabemos" que el horario de la sucursal de al lado.
 *
 * <p><b>Umbrales, medidos</b> contra el scraper (744 tiendas de 16 puntos de AMBA,
 * La Plata, Mar del Plata y Bahía Blanca; Córdoba, Rosario, Mendoza, Neuquén y
 * Tucumán no devuelven tiendas) y el sucursales.csv de SEPA de septiembre 2026:
 * <ul>
 *   <li>Hasta {@value #UMBRAL_M} m, todos los matches revisados son la sucursal
 *       correcta. En COTO se puede comprobar, porque el id del scraper ("coto-82") es el
 *       de SEPA: 43 de 43 con esta regla. Desde ~150 m aparecen los errores en las
 *       cadenas densas: un COTO a 194 m toma el de Av. San Pedrito en vez del de Av.
 *       Nazca, otro a 225 m el de Alsina. En esas cadenas la segunda sucursal más
 *       cercana suele estar a 200-400 m.</li>
 *   <li>Los hipermercados (Jumbo, Changomas, Carrefour Hiper) ocupan una manzana y el
 *       scraper y SEPA marcan puntos distintos del predio: el correcto cae a 100-250 m.
 *       Se aceptan hasta {@value #UMBRAL_AISLADA_M} m solo si no hay otra sucursal de la
 *       cadena a menos de {@value #SEPARACION_M} m, o sea si no hay con cuál confundirla.
 *       Más allá de 250 m, aun aisladas, aparecen errores (un Maxi de Claypole a 294 m
 *       tomaría un Express de Carlos Pellegrini).</li>
 * </ul>
 *
 * <p>Con esta regla quedan con horario: Disco 95%, Carrefour 91%, DIA 84%, Changomas
 * 79%, Jumbo 71%, COTO 62% y Vea 54%. Lo que falta son sobre todo coordenadas mal
 * puestas en el scraper (un COTO de Belgrano cae a 3 km, el de Viamonte a 415 km), que
 * es justo lo que no hay que "arreglar" agrandando el umbral.
 *
 * <p>Además el scraper tiene coordenadas de relleno: cuatro Vea de CABA con
 * direcciones distintas (Manzanares 3953, Av. Santa Fe 1653, Bulnes 1048, Aráoz 265)
 * en el mismo punto exacto que el de Av. Córdoba 6103, otros cuatro sobre el de Av.
 * San Juan 2862, y dos Carrefour (Rivadavia 6074, Independencia 1520) en el Obelisco.
 * Ninguna distancia arregla eso: las tiendas de una cadena que comparten coordenadas
 * con otra de distinta dirección van sin horario, las diez de Vea incluidas (ver
 * {@link #coordenadasDeRelleno}).
 *
 * <p>Makro no está en SEPA y La Anónima no está en el scraper (no hubo con qué medir):
 * no se mapean.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiendaHorariosService {

    static final int UMBRAL_M = 100;
    static final int UMBRAL_AISLADA_M = 250;
    static final int SEPARACION_M = 1500;

    private static final double KM_POR_GRADO_LAT = 111.32;

    /** Qué sucursales de SEPA son de cada cadena del scraper. */
    record CadenaSepa(String comercioId, String banderaId) {
        boolean incluye(SepaSucursal s) {
            return comercioId.equals(s.getComercioId())
                    && (banderaId == null || banderaId.equals(s.getBanderaId()));
        }
    }

    /**
     * chainSlug del scraper -> comercio (y bandera) de SEPA. Ids de comercio.csv, los
     * mismos de {@link SepaComercioNombres}: Cencosud (9) es Vea (1), Disco (2) y Jumbo
     * (3); Carrefour (10) y Changomas (11) se toman con todas sus banderas, porque el
     * scraper no distingue Express de Market ni Súper de Híper.
     */
    static final Map<String, CadenaSepa> CADENAS = Map.of(
            "carrefour", new CadenaSepa("10", null),
            "changomas", new CadenaSepa("11", null),
            "coto", new CadenaSepa("12", null),
            "dia", new CadenaSepa("15", null),
            "vea", new CadenaSepa("9", "1"),
            "disco", new CadenaSepa("9", "2"),
            "jumbo", new CadenaSepa("9", "3"));

    private final SepaSucursalRepository sucursalRepository;

    /**
     * Las mismas tiendas, en el mismo orden, con {@code horarios} en las que tienen un
     * match confiable. Si algo falla devuelve las tiendas tal cual: sin horarios el mapa
     * sigue sirviendo.
     */
    public List<Map<String, Object>> conHorarios(List<Map<String, Object>> tiendas) {
        if (tiendas == null || tiendas.isEmpty()) {
            return tiendas;
        }
        try {
            return enriquecer(tiendas);
        } catch (RuntimeException e) {
            log.warn("No se pudieron agregar horarios a las tiendas: {}", e.getMessage());
            return tiendas;
        }
    }

    /** Una tienda del scraper que se puede ubicar y es de una cadena que está en SEPA. */
    record Tienda(int indice, String cadena, double lat, double lng, String direccion) {
    }

    private List<Map<String, Object>> enriquecer(List<Map<String, Object>> tiendas) {
        List<Tienda> ubicables = new ArrayList<>();
        for (int i = 0; i < tiendas.size(); i++) {
            Tienda t = tienda(i, tiendas.get(i));
            if (t != null) {
                ubicables.add(t);
            }
        }
        if (ubicables.isEmpty()) {
            return tiendas;
        }

        Map<CadenaSepa, List<SepaSucursal>> porCadena = sucursalesPorCadena(ubicables);
        Set<Integer> descartadas = coordenadasDeRelleno(ubicables);
        Map<Long, HorariosResponse> horariosPorSucursal = new HashMap<>();

        List<Map<String, Object>> out = new ArrayList<>(tiendas);
        for (Tienda t : ubicables) {
            if (descartadas.contains(t.indice())) {
                continue;
            }
            SepaSucursal s = elegir(t.lat(), t.lng(), porCadena.getOrDefault(CADENAS.get(t.cadena()), List.of()));
            if (s == null) {
                continue;
            }
            HorariosResponse horarios = horariosPorSucursal.computeIfAbsent(s.getId(),
                    id -> HorariosJson.leer(s.getHorarios()));
            if (horarios != null) {
                Map<String, Object> conHorario = new LinkedHashMap<>(tiendas.get(t.indice()));
                conHorario.put("horarios", horarios);
                out.set(t.indice(), conHorario);
            }
        }
        return out;
    }

    private static Tienda tienda(int indice, Map<String, Object> item) {
        if (item == null || !(item.get("chainSlug") instanceof String slug)
                || !(item.get("lat") instanceof Number lat) || !(item.get("lng") instanceof Number lng)) {
            return null;
        }
        String cadena = slug.trim().toLowerCase(Locale.ROOT);
        if (!CADENAS.containsKey(cadena)) {
            return null;
        }
        Object direccion = item.get("address");
        return new Tienda(indice, cadena, lat.doubleValue(), lng.doubleValue(),
                direccion == null ? "" : direccion.toString());
    }

    /**
     * Una sola consulta: la caja que encierra todas las tiendas, agrandada lo
     * necesario para ver si hay otra sucursal de la cadena a {@value #SEPARACION_M} m.
     */
    private Map<CadenaSepa, List<SepaSucursal>> sucursalesPorCadena(List<Tienda> tiendas) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (Tienda t : tiendas) {
            minLat = Math.min(minLat, t.lat());
            maxLat = Math.max(maxLat, t.lat());
            minLng = Math.min(minLng, t.lng());
            maxLng = Math.max(maxLng, t.lng());
        }
        double margenKm = SEPARACION_M / 1000.0;
        double dLat = margenKm / KM_POR_GRADO_LAT;
        double latExtrema = Math.max(Math.abs(minLat), Math.abs(maxLat));
        double dLng = margenKm / (KM_POR_GRADO_LAT * Math.max(0.1, Math.cos(Math.toRadians(latExtrema))));

        Set<CadenaSepa> cadenas = tiendas.stream().map(t -> CADENAS.get(t.cadena())).collect(Collectors.toSet());
        Set<String> comercios = cadenas.stream().map(CadenaSepa::comercioId).collect(Collectors.toSet());

        Map<CadenaSepa, List<SepaSucursal>> out = new HashMap<>();
        for (SepaSucursal s : sucursalRepository.findInBoxDeComercios(
                minLat - dLat, maxLat + dLat, minLng - dLng, maxLng + dLng, comercios)) {
            for (CadenaSepa c : cadenas) {
                if (c.incluye(s)) {
                    out.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
                }
            }
        }
        return out;
    }

    /**
     * La sucursal de la cadena que le corresponde a una tienda, o null si no hay una
     * que sea sin duda la misma. Ver los umbrales en la clase.
     */
    static SepaSucursal elegir(double lat, double lng, List<SepaSucursal> deLaCadena) {
        SepaSucursal primera = null;
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        for (SepaSucursal s : deLaCadena) {
            double d = SepaSucursalPreciosService.distanciaKm(lat, lng, s.getLatitud(), s.getLongitud()) * 1000;
            if (d < d1) {
                d2 = d1;
                d1 = d;
                primera = s;
            } else if (d < d2) {
                d2 = d;
            }
        }
        if (primera == null) {
            return null;
        }
        if (d1 <= UMBRAL_M) {
            return primera;
        }
        return d1 <= UMBRAL_AISLADA_M && d2 >= SEPARACION_M ? primera : null;
    }

    /**
     * Índices de las tiendas cuyas coordenadas no son las suyas: las de una cadena que
     * caen en el mismo punto exacto que otra con distinta dirección. Las repetidas de
     * verdad (Changomas publica cada híper como "Retirá en" y como "Pickup", Carrefour
     * lista algunas dos veces) comparten la dirección y no se descartan.
     */
    static Set<Integer> coordenadasDeRelleno(List<Tienda> tiendas) {
        Map<String, List<Tienda>> porPunto = new HashMap<>();
        for (Tienda t : tiendas) {
            porPunto.computeIfAbsent(t.cadena() + "|" + t.lat() + "|" + t.lng(), k -> new ArrayList<>()).add(t);
        }
        Set<Integer> out = new HashSet<>();
        for (List<Tienda> mismoPunto : porPunto.values()) {
            if (mismoPunto.size() > 1 && !mismaDireccion(mismoPunto)) {
                mismoPunto.forEach(t -> out.add(t.indice()));
            }
        }
        return out;
    }

    private static final Pattern NUMERO = Pattern.compile("\\b\\d{2,5}\\b");

    /**
     * Si todas nombran el mismo lugar. Las direcciones del scraper vienen escritas de
     * formas distintas ("Av. Sta Fe 2349 2349", "Avenida Santa Fe 2349"), así que se
     * comparan por la altura; sin números ("Cerrito y Rubén Darío S/N"), por el texto.
     */
    private static boolean mismaDireccion(List<Tienda> tiendas) {
        for (int i = 0; i < tiendas.size(); i++) {
            for (int j = i + 1; j < tiendas.size(); j++) {
                if (!mismaDireccion(tiendas.get(i).direccion(), tiendas.get(j).direccion())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean mismaDireccion(String a, String b) {
        Set<String> numerosA = numeros(a);
        Set<String> numerosB = numeros(b);
        if (numerosA.isEmpty() || numerosB.isEmpty()) {
            return normalizar(a).equals(normalizar(b));
        }
        numerosA.retainAll(numerosB);
        return !numerosA.isEmpty();
    }

    private static Set<String> numeros(String texto) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMERO.matcher(texto);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
