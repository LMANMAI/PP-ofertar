package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Un producto que el usuario sumó a su compra habitual desde el escáner. La
 * compra habitual son estos más los que se detectan solos en los tickets: no es
 * una lista aparte, y por eso {@code GET /products/recurring} devuelve todo junto.
 *
 * El nombre y la marca son una copia tomada al sumarlo, así el producto no
 * depende de que siga en el relevamiento de SEPA.
 */
@Entity
@Table(
        name = "habitual_products",
        uniqueConstraints = @UniqueConstraint(name = "uk_habitual_products_user_ean", columnNames = {"user_id", "ean"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HabitualProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false, length = 20)
    private String ean;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(length = 255)
    private String brand;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
