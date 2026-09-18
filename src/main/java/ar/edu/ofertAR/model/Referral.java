package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One record per referral code used at signup — the referrer's invite
 * turning into a new account. {@link #referred} is unique: a user can be
 * on the receiving end of a referral only once, on their own registration.
 */
@Entity
@Table(name = "referrals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referrer_id", nullable = false)
    private User referrer;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_id", nullable = false, unique = true)
    private User referred;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReferralStatus status = ReferralStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Set once the referred user's first ticket finishes processing. */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** Set once the referred user is confirmed still active ~30 days later. */
    @Column(name = "retained_at")
    private LocalDateTime retainedAt;
}
