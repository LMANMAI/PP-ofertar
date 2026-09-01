package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A supermarket chain the user marked as one they actually shop at. Offers are
 * filtered to these, so a user who only shops at Jumbo and Día never sees a
 * Coto or Carrefour promo they can't use.
 *
 * Stored as a chain slug rather than a FK because the chain catalogue lives in
 * the external scraper service, not in this database.
 */
@Entity
@Table(
        name = "favorite_store_chains",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "chain_slug"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteStoreChain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "chain_slug", nullable = false, length = 50)
    private String chainSlug;

    @Column(name = "chain_name", length = 100)
    private String chainName;
}
