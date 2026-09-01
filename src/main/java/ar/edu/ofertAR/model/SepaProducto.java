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
 * Snapshot semanal de precios SEPA agregado por producto (EAN).
 * Se reemplaza completo en cada sincronización.
 */
@Entity
@Table(name = "sepa_producto", indexes = {
        @Index(name = "idx_sepa_producto_ean", columnList = "ean", unique = true),
        @Index(name = "idx_sepa_producto_descripcion", columnList = "descripcion")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SepaProducto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ean;

    @Column(length = 500)
    private String descripcion;

    @Column(length = 255)
    private String marca;

    @Column(name = "precio_minimo", precision = 14, scale = 2)
    private BigDecimal precioMinimo;

    @Column(name = "precio_promedio", precision = 14, scale = 2)
    private BigDecimal precioPromedio;

    @Column(name = "precio_maximo", precision = 14, scale = 2)
    private BigDecimal precioMaximo;

    /** cantidad de registros (sucursal x comercio) sobre los que se agregó */
    @Column(name = "cantidad_ofertas", nullable = false)
    private int cantidadOfertas;

    @Column(name = "fecha_dataset", nullable = false)
    private LocalDate fechaDataset;
}
