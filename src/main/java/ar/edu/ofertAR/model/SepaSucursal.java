package ar.edu.ofertAR.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una sucursal del dataset SEPA, con dónde está.
 *
 * <p>Es lo que permite pasar de "el mínimo de Coto es $5.600" a "en tu barrio,
 * el más barato está en Av. San Martín 2095". El {@code id} lo asigna la
 * sincronización y es el que citan los grupos de {@link SepaPrecioGrupo}: un
 * entero corto en vez de la clave (bandera, sucursal) de SEPA, porque esa lista
 * se repite por cada grupo de precio.
 *
 * <p>Se reemplaza completa en cada sincronización, junto con sepa_producto.
 */
@Entity
@Table(name = "sepa_sucursal", indexes = {
        @Index(name = "idx_sepa_sucursal_ubicacion", columnList = "latitud, longitud")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SepaSucursal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comercio_id", length = 20)
    private String comercioId;

    @Column(name = "bandera_id", length = 20)
    private String banderaId;

    @Column(name = "sucursal_id", length = 40)
    private String sucursalId;

    /** Nombre comercial de la bandera de esta sucursal: Jumbo, Disco, Vea. */
    @Column(length = 255)
    private String bandera;

    @Column(length = 255)
    private String nombre;

    /** Supermercado, Hipermercado, Autoservicio... */
    @Column(length = 60)
    private String tipo;

    /** Calle y número, listos para mostrar. */
    @Column(length = 255)
    private String direccion;

    @Column(length = 120)
    private String localidad;

    /** Código ISO de SEPA ("AR-C"), no el nombre. */
    @Column(length = 20)
    private String provincia;

    @Column(nullable = false)
    private double latitud;

    @Column(nullable = false)
    private double longitud;
}
