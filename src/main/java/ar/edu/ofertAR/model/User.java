package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "profile_picture")
    private String profilePicture;

    @Column(length = 300)
    private String address;

    @Column(length = 20)
    private String phone;

<<<<<<< HEAD
=======
    /** Opt-in to being shown offers on the same kind of product from a
     * different brand (e.g. Elite toilet paper when the user buys Higienol). */
    @Column(name = "alternative_brands_enabled", nullable = false)
    @Builder.Default
    private boolean alternativeBrandsEnabled = true;

    /** Search radius (km) used when listing nearby stores on the map. */
    @Column(name = "store_search_radius_km", nullable = false)
    @Builder.Default
    private int storeSearchRadiusKm = 5;

    /** This account's own invite code, generated once at signup. Left
     * nullable at the DB level (unlike most fields here) purely so
     * ddl-auto's ALTER TABLE doesn't choke backfilling a UNIQUE column
     * across every pre-existing row in the live users table; every account
     * created from here on always gets one. */
    @Column(name = "referral_code", unique = true, length = 12)
    private String referralCode;

    /** Denormalized balance — always updated in the same transaction as the
     * PointsTransaction row that changed it, so it never drifts from the
     * ledger it summarizes. */
    @Column(nullable = false)
    @Builder.Default
    private int points = 0;

>>>>>>> 248bfcb (Merge pull request #9 from LMANMAI/feature/referral-points)
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── UserDetails ──

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
