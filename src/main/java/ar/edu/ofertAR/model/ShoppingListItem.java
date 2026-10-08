package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One product on a shopping list, identified by its barcode.
 *
 * The name and brand are a copy of what the scan showed when it was added:
 * {@code sepa_producto} is replaced wholesale every week and a product read
 * from an external provider is not in it at all, so the list cannot depend on
 * a join to still know what each line is. The image is not stored — it is
 * looked up by EAN when the list is read.
 */
@Entity
@Table(
        name = "shopping_list_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_shopping_list_items_list_ean", columnNames = {"list_id", "ean"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShoppingListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "list_id", nullable = false)
    @ToString.Exclude
    private ShoppingList list;

    @Column(nullable = false, length = 20)
    private String ean;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(length = 255)
    private String brand;

    @Column(nullable = false)
    @Builder.Default
    private int quantity = 1;

    /** Already in the cart. Kept on the list so it can be unchecked. */
    @Column(nullable = false)
    @Builder.Default
    private boolean checked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
