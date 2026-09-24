package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SucursalPrecioResponse;
import ar.edu.ofertAR.model.SepaPrecioGrupo;
import ar.edu.ofertAR.model.SepaSucursal;
import ar.edu.ofertAR.repository.SepaPrecioGrupoRepository;
import ar.edu.ofertAR.repository.SepaSucursalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Responde "¿dónde, cerca mío, está más barato este producto?": de cada cadena, la
 * sucursal más barata dentro de un radio, con su dirección.
 *
 * <p>Parte de las sucursales del radio (que son pocas: decenas) y recorre los
 * precios del producto de menor a mayor, así que el primer grupo que incluye una
 * sucursal cercana de una cadena fija el precio de esa cadena.
 */
@Service
@RequiredArgsConstructor
public class SepaSucursalPreciosService {

    private static final double KM_POR_GRADO_LAT = 111.32;
    private static final double RADIO_TIERRA_KM = 6371.0;

    private final SepaSucursalRepository sucursalRepository;
    private final SepaPrecioGrupoRepository grupoRepository;

    /**
     * @param eans el EAN tal cual vino y normalizado: SEPA lo publica con formatos
     *             mezclados, así que se prueba con cada uno hasta encontrar precios
     */
    public List<SucursalPrecioResponse> masBaratasCerca(Collection<String> eans, double lat, double lng,
                                                        double radioKm) {
        Map<Long, Cercana> cercanas = sucursalesCercanas(lat, lng, radioKm);
        if (cercanas.isEmpty()) {
            return List.of();
        }
        List<String> comercios = cercanas.values().stream()
                .map(c -> c.sucursal().getComercioId())
                .distinct()
                .toList();

        for (String ean : eans) {
            List<SepaPrecioGrupo> grupos = grupoRepository.findByEanAndComercioIdInOrderByPrecioAsc(ean, comercios);
            if (!grupos.isEmpty()) {
                return elegir(cercanas, grupos);
            }
        }
        return List.of();
    }

    record Cercana(SepaSucursal sucursal, double distanciaKm) {
    }

    Map<Long, Cercana> sucursalesCercanas(double lat, double lng, double radioKm) {
        double dLat = radioKm / KM_POR_GRADO_LAT;
        // Un grado de longitud mide menos cuanto más lejos del ecuador.
        double dLng = radioKm / (KM_POR_GRADO_LAT * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        Map<Long, Cercana> out = new HashMap<>();
        for (SepaSucursal s : sucursalRepository.findInBox(lat - dLat, lat + dLat, lng - dLng, lng + dLng)) {
            double d = distanciaKm(lat, lng, s.getLatitud(), s.getLongitud());
            if (d <= radioKm) {
                out.put(s.getId(), new Cercana(s, d));
            }
        }
        return out;
    }

    /**
     * De cada cadena (comercio y bandera), la sucursal más barata entre las cercanas.
     *
     * @param gruposPorPrecio los grupos del producto, del más barato al más caro
     */
    static List<SucursalPrecioResponse> elegir(Map<Long, Cercana> cercanas, List<SepaPrecioGrupo> gruposPorPrecio) {
        Map<String, SucursalPrecioResponse> porCadena = new LinkedHashMap<>();

        for (SepaPrecioGrupo grupo : gruposPorPrecio) {
            // Dentro de un grupo el precio es el mismo: gana la sucursal más cerca.
            Map<String, Cercana> mejorDelGrupo = new HashMap<>();
            for (String idTexto : grupo.getSucursales().split(",")) {
                Cercana c = cercanas.get(parseId(idTexto));
                if (c == null) {
                    continue;
                }
                String cadena = claveCadena(c.sucursal());
                if (porCadena.containsKey(cadena)) {
                    continue; // ya la resolvió un grupo más barato
                }
                mejorDelGrupo.merge(cadena, c,
                        (a, b) -> a.distanciaKm() <= b.distanciaKm() ? a : b);
            }
            mejorDelGrupo.forEach((cadena, c) -> porCadena.put(cadena, respuesta(c, grupo)));
        }

        return porCadena.values().stream()
                .sorted(Comparator.comparing(SucursalPrecioResponse::precio)
                        .thenComparingDouble(SucursalPrecioResponse::distanciaKm))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static SucursalPrecioResponse respuesta(Cercana c, SepaPrecioGrupo grupo) {
        SepaSucursal s = c.sucursal();
        return new SucursalPrecioResponse(
                s.getComercioId(), s.getBanderaId(), s.getBandera(), s.getId(), s.getNombre(), s.getTipo(),
                s.getDireccion(), s.getLocalidad(), s.getProvincia(), s.getLatitud(), s.getLongitud(),
                Math.round(c.distanciaKm() * 10.0) / 10.0, grupo.getPrecio());
    }

    private static String claveCadena(SepaSucursal s) {
        return s.getComercioId() + ":" + s.getBanderaId();
    }

    private static Long parseId(String texto) {
        try {
            return Long.valueOf(texto.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Distancia sobre la esfera (haversine), en km. */
    static double distanciaKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return RADIO_TIERRA_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
