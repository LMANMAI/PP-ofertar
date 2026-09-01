package ar.edu.ofertAR.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Imagen de producto resuelta contra proveedores externos, indexada por EAN.
 *
 * <p>Vive en su propia tabla a propósito: {@code sepa_producto} se reemplaza
 * entero en cada sincronización semanal, así que guardar la imagen ahí
 * significaría perder todo el trabajo de enriquecimiento cada lunes.
 *
 * <p>Se guarda solo la URL, nunca los bytes.
 */
@Entity
@Table(name = "producto_imagen", indexes = {
        @Index(name = "idx_producto_imagen_estado", columnList = "estado, actualizado_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductoImagen {

    /** EAN normalizado a 13 dígitos. */
    @Id
    @Column(length = 20)
    private String ean;

    @Column(length = 1000)
    private String url;

    /** Proveedor que la resolvió: openfoodfacts, vtex-coto, manual, ... */
    @Column(length = 50)
    private String fuente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoImagen estado;

    /** Intentos fallidos acumulados. Corta el reintento infinito. */
    @Column(nullable = false)
    private int intentos;

    @Column(name = "actualizado_at", nullable = false)
    private LocalDateTime actualizadoAt;
}
