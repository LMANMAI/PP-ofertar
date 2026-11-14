package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Un medio de pago que el usuario declaró en "Mis tarjetas" (OFT-51), para
 * cruzarlo con las promos bancarias.
 *
 * <p>A propósito no hay campo para el número, el vencimiento ni el CVV: sólo el
 * emisor, la red y el tipo. Es lo que dice la cláusula de los T&C (OFT-53) y lo
 * único que hace falta para saber si una promo le aplica.
 */
@Entity
@Table(
        name = "user_payment_methods",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_payment_methods_user_method",
                columnNames = {"user_id", "issuer_slug", "network", "card_type"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    /** Slug de payment_issuers (la tabla del scraper): 'banco-galicia', 'mercado-pago'. */
    @Column(name = "issuer_slug", nullable = false, length = 60)
    private String issuerSlug;

    /** visa | mastercard | amex | cabal | naranja; null = no lo dijo (p. ej. una billetera). */
    @Column(length = 20)
    private String network;

    /** credit | debit | prepaid | wallet; null = no lo dijo. */
    @Column(name = "card_type", length = 20)
    private String cardType;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
