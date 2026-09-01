package ar.edu.ofertAR.service.imagen;

/**
 * Lo que un proveedor externo sabe de un EAN.
 *
 * <p>Cualquiera de los campos puede venir null: un producto puede estar
 * catalogado sin foto, o tener foto sin marca cargada.
 */
public record ProductoExterno(String nombre, String marca, String imagenUrl) {

    public boolean tieneImagen() {
        return imagenUrl != null && !imagenUrl.isBlank();
    }

    public boolean tieneDatos() {
        return tieneImagen() || (nombre != null && !nombre.isBlank());
    }
}
