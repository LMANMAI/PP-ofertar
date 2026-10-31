package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A product the user asked to be told about when it goes on promotion, without
 * having bought it (recurring products come from tickets; this one comes from
 * a barcode scan).
 *
 * El nombre y la marca son una copia tomada al seguirlo, así la lista y el
 * aviso no dependen de que el producto siga apareciendo en la búsqueda.
 */
@Entity
@Table(
        name = "followed_products",
        uniqueConstraints = @UniqueConstraint(name = "uk_followed_products_user_ean", columnNames = {"user_id", "ean"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowedProduct {

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

    /** Whether the last run of the alert job found it on promotion. The push
     * goes out when this turns true, not on every run it stays true. */
    @Column(name = "in_promo", nullable = false)
    @Builder.Default
    private boolean inPromo = false;

    /** Last push about this product, for the cooldown between two of them. */
    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
