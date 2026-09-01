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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Precio de un producto en un comercio puntual, dentro del snapshot semanal.
 *
 * <p>Es el desglose que {@link SepaProducto} pierde al agregar por EAN: sin
 * esto, la app puede decir "el mínimo es $850" pero no en qué supermercado,
 * que es justamente lo que el usuario quiere saber al escanear.
 *
 * <p>Se agrega por (ean, comercio), no por sucursal: guardar cada sucursal
 * multiplicaría las filas por cientos sin agregar información útil para el
 * comparador. El rango min/max dentro del comercio se conserva igual.
 *
 * <p>Se reemplaza completo en cada sincronización, junto con sepa_producto.
 */
@Entity
@Table(name = "sepa_precio_comercio", indexes = {
        @Index(name = "idx_sepa_precio_comercio_ean", columnList = "ean")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SepaPrecioComercio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ean;

    @Column(name = "comercio_id", length = 20)
    private String comercioId;

    /** Nombre comercial: COTO, Jumbo, Carrefour... Es lo que ve el usuario. */
    @Column(length = 255)
    private String bandera;

    @Column(name = "razon_social", length = 255)
    private String razonSocial;

    @Column(name = "precio_minimo", precision = 14, scale = 2)
    private BigDecimal precioMinimo;

    @Column(name = "precio_maximo", precision = 14, scale = 2)
    private BigDecimal precioMaximo;

    /** Sucursales de este comercio donde apareció el producto. */
    @Column(name = "cantidad_sucursales", nullable = false)
    private int cantidadSucursales;

    @Column(name = "fecha_dataset", nullable = false)
    private LocalDate fechaDataset;
}
