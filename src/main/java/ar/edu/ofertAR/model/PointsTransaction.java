package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ledger row — the source of truth for a user's points history.
 * {@link User#getPoints()} is a denormalized running balance kept in sync
 * with this table, never the other way around.
 */
@Entity
@Table(name = "points_transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointsReason reason;

    /** Already-composed text, shown as-is in the history — not built from
     * the reason on the client. */
    @Column(nullable = false, length = 300)
    private String description;

    /** Positive credits the balance, negative debits it (a redeem). */
    @Column(nullable = false)
    private int points;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
