package ar.edu.ofertAR.service;

/**
 * Una sucursal tal como la publica SEPA en {@code sucursales.csv}.
 *
 * <p>La clave de una sucursal dentro de su comercio es
 * {@code (banderaId, sucursalId)}: un mismo comercio agrupa varias banderas
 * (Cencosud es Jumbo, Disco y Vea; Carrefour es Maxi, Market y Express) y los
 * ids de sucursal no están garantizados como únicos entre ellas.
 *
 * @param bandera nombre comercial de la bandera de esta sucursal ("Jumbo"),
 *                no el del comercio; null si comercio.csv no la lista
 */
public record SepaSucursalData(
        String comercioId,
        String banderaId,
        String sucursalId,
        String bandera,
        String nombre,
        String tipo,
        String direccion,
        String localidad,
        String provincia,
        double latitud,
        double longitud
) {
    /** Clave de la sucursal dentro de su comercio. */
    public String clave() {
        return claveDe(banderaId, sucursalId);
    }

    public static String claveDe(String banderaId, String sucursalId) {
        return (banderaId == null ? "" : banderaId.trim()) + ":" + (sucursalId == null ? "" : sucursalId.trim());
    }
}
