package ar.edu.ofertAR.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Traduce el {@code comercioId} de SEPA al nombre comercial reconocible.
 *
 * <p>El dataset identifica a cada cadena por un id (INC S.A. = Carrefour,
 * Cencosud S.A. = Jumbo/Vea/Disco) y el snapshot solo conserva la primera
 * bandera del {@code comercio.csv}, que no siempre es la que el usuario
 * reconoce (Carrefour llega como "Maxi"). Este mapa curado repara esa
 * discrepancia en la respuesta, sin tocar los datos guardados.
 */
@Component
public class SepaComercioNombres {

    /** comercioId -> nombre comercial de la cadena. */
    private static final Map<String, String> CADENAS = Map.ofEntries(
            Map.entry("2", "La Anónima"),
            Map.entry("3", "Deheza"),
            Map.entry("4", "Estación Lima"),
            Map.entry("5", "California"),
            Map.entry("6", "Comodín"),
            Map.entry("9", "Cencosud"),
            Map.entry("10", "Carrefour"),
            Map.entry("11", "Changomas"),
            Map.entry("12", "COTO"),
            Map.entry("13", "Cooperativa Obrera"),
            Map.entry("15", "DIA"),
            Map.entry("20", "La Agrícola Regional"),
            Map.entry("21", "Toledo"),
            Map.entry("23", "Axion Energy"),
            Map.entry("24", "Farmacity"),
            Map.entry("47", "Unicoop")
    );

    /**
     * Nombre a mostrar para un comercio.
     *
     * <p>Cuando el id no está curado se devuelve la bandera tal cual. Cuando
     * está curado y la bandera no aporta nada nuevo ("COTO CICSA" para COTO,
     * "Supermercados DIA" para DIA) se muestra solo la cadena; si la bandera
     * es otra cosa ("Maxi" para Carrefour) se muestra "Cadena · Bandera".
     */
    public String nombre(String comercioId, String bandera) {
        String cadena = comercioId == null ? null : CADENAS.get(comercioId.trim());
        if (cadena == null || cadena.isBlank()) {
            return bandera;
        }
        String b = bandera == null ? "" : bandera.trim();
        if (b.isBlank()) {
            return cadena;
        }
        if (redundante(cadena, b)) {
            return cadena;
        }
        return cadena + " · " + b;
    }

    private boolean redundante(String cadena, String bandera) {
        String cn = normalize(cadena);
        String bn = normalize(bandera);
        return bn.equals(cn) || bn.contains(cn) || cn.contains(bn);
    }

    /** minúsculas + sin acentos, para comparaciones tolerantes. */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}