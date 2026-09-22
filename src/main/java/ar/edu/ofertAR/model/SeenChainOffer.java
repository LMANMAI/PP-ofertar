package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Marks one offer (by the scraper's own id) as already surfaced for a chain,
 * so the daily alert job can tell a genuinely new offer from one it already
 * notified people about. Nothing here is user-specific — it's per chain,
 * since the fetch-and-diff itself runs once per chain, not once per user.
 */
@Entity
@Table(
        name = "seen_chain_offers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chain_slug", "offer_id"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeenChainOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_slug", nullable = false, length = 50)
    private String chainSlug;

    @Column(name = "offer_id", nullable = false, length = 200)
    private String offerId;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime firstSeenAt = LocalDateTime.now();
}
