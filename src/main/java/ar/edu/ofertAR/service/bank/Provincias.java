package ar.edu.ofertAR.service.bank;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Normaliza provincias para comparar la zona del usuario con bank_promotions.regions.
 *
 * <p>La forma canónica va en mayúscula, sin tildes y con guion bajo: "CORDOBA",
 * "RIO_NEGRO", "CABA". Se aceptan el nombre con o sin tildes ("Córdoba", "Rio Negro"),
 * el código ISO 3166-2 ("AR-X" o "X") y algunos alias ("Capital Federal"). Las
 * regiones de la base pasan por el mismo normalizador, así "RIO NEGRO" y
 * "RIO_NEGRO" son la misma provincia.
 */
public final class Provincias {

    private Provincias() {
    }

    /** Canónica → nombre para mostrar. */
    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();
    /** Alias normalizado → canónica. */
    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        agregar("BUENOS_AIRES", "Buenos Aires", "B", "PROVINCIA_DE_BUENOS_AIRES", "PBA");
        agregar("CABA", "la Ciudad de Buenos Aires", "C", "CIUDAD_AUTONOMA_DE_BUENOS_AIRES",
                "CIUDAD_DE_BUENOS_AIRES", "CAPITAL_FEDERAL");
        agregar("CATAMARCA", "Catamarca", "K");
        agregar("CHACO", "Chaco", "H");
        agregar("CHUBUT", "Chubut", "U");
        agregar("CORDOBA", "Córdoba", "X");
        agregar("CORRIENTES", "Corrientes", "W");
        agregar("ENTRE_RIOS", "Entre Ríos", "E");
        agregar("FORMOSA", "Formosa", "P");
        agregar("JUJUY", "Jujuy", "Y");
        agregar("LA_PAMPA", "La Pampa", "L");
        agregar("LA_RIOJA", "La Rioja", "F");
        agregar("MENDOZA", "Mendoza", "M");
        agregar("MISIONES", "Misiones", "N");
        agregar("NEUQUEN", "Neuquén", "Q");
        agregar("RIO_NEGRO", "Río Negro", "R");
        agregar("SALTA", "Salta", "A");
        agregar("SAN_JUAN", "San Juan", "J");
        agregar("SAN_LUIS", "San Luis", "D");
        agregar("SANTA_CRUZ", "Santa Cruz", "Z");
        agregar("SANTA_FE", "Santa Fe", "S");
        agregar("SANTIAGO_DEL_ESTERO", "Santiago del Estero", "G");
        agregar("TIERRA_DEL_FUEGO", "Tierra del Fuego", "V",
                "TIERRA_DEL_FUEGO_ANTARTIDA_E_ISLAS_DEL_ATLANTICO_SUR");
        agregar("TUCUMAN", "Tucumán", "T");
    }

    private static void agregar(String canonica, String nombre, String iso, String... alias) {
        NOMBRES.put(canonica, nombre);
        ALIAS.put(canonica, canonica);
        ALIAS.put(iso, canonica);
        ALIAS.put("AR_" + iso, canonica);
        for (String a : alias) {
            ALIAS.put(a, canonica);
        }
    }

    /** La provincia canónica, o vacío si no se reconoce. */
    public static Optional<String> canonica(String valor) {
        if (valor == null || valor.isBlank()) return Optional.empty();
        String clave = clave(valor);
        String encontrada = ALIAS.get(clave);
        if (encontrada == null && clave.startsWith("PROVINCIA_DE_")) {
            encontrada = ALIAS.get(clave.substring("PROVINCIA_DE_".length()));
        }
        return Optional.ofNullable(encontrada);
    }

    /**
     * La zona del usuario tal como la manda la app: una provincia o varias separadas por coma.
     * "AMBA" y "GBA" son CABA + provincia de Buenos Aires. Vacío si no manda nada.
     *
     * @throws IllegalArgumentException si alguna no se reconoce (el controller la vuelve 400)
     */
    public static Set<String> zona(String valor) {
        Set<String> zona = new LinkedHashSet<>();
        if (valor == null || valor.isBlank()) return zona;
        for (String parte : valor.split(",")) {
            if (parte.isBlank()) continue;
            String clave = clave(parte);
            if (clave.equals("AMBA") || clave.equals("GBA")) {
                zona.add("CABA");
                zona.add("BUENOS_AIRES");
                continue;
            }
            zona.add(canonica(parte).orElseThrow(() ->
                    new IllegalArgumentException("La provincia '" + parte.trim() + "' no es válida")));
        }
        return zona;
    }

    /** Para las regiones de la base: la canónica si se reconoce, o la clave normalizada tal cual. */
    public static String normalizarRegion(String valor) {
        return canonica(valor).orElseGet(() -> clave(valor));
    }

    public static String nombre(String canonica) {
        String nombre = NOMBRES.get(canonica);
        if (nombre != null) return nombre;
        String texto = canonica.replace('_', ' ').toLowerCase(Locale.ROOT);
        return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    /** "Sólo en Córdoba", "Sólo en Río Negro y Neuquén". */
    public static String soloEn(List<String> canonicas) {
        List<String> nombres = canonicas.stream().map(Provincias::nombre).toList();
        if (nombres.size() == 1) return "Sólo en " + nombres.get(0);
        return "Sólo en " + String.join(", ", nombres.subList(0, nombres.size() - 1))
                + " y " + nombres.get(nombres.size() - 1);
    }

    private static String clave(String valor) {
        String sinTildes = Normalizer.normalize(valor.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
