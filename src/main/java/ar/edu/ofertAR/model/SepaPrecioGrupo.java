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

/**
 * Las sucursales de un comercio que cobran un mismo precio por un producto.
 *
 * <p>Guardar una fila por (producto, sucursal) son decenas de millones de filas.
 * Pero una cadena casi siempre cobra lo mismo en muchas sucursales: en el dataset
 * real, Coto pasa de 1,05 millones de filas a 37 mil grupos de (producto, precio),
 * y DIA de 4,1 millones a 13 mil. Se guarda cada precio distinto una sola vez con
 * la lista de sucursales que lo aplican, y la pregunta "¿cuál es el precio en las
 * sucursales cercanas?" se responde sin perder ninguna.
 *
 * <p>Se reemplaza completa en cada sincronización, junto con sepa_producto.
 */
@Entity
@Table(name = "sepa_precio_grupo", indexes = {
        @Index(name = "idx_sepa_precio_grupo_ean", columnList = "ean")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SepaPrecioGrupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ean;

    @Column(name = "comercio_id", length = 20)
    private String comercioId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal precio;

    @Column(name = "cantidad_sucursales", nullable = false)
    private int cantidadSucursales;

    /** Ids de {@link SepaSucursal} separados por coma. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String sucursales;
}
