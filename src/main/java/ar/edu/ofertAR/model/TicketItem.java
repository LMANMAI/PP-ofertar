package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "ticket_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "subtotal", precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(length = 50)
    private String barcode;

    @Column(length = 50)
    private String category;

    @Column(name = "raw_description", length = 300)
    private String rawDescription;

    @Column(name = "discount_description", length = 200)
    private String discountDescription;

    @Column(name = "discount_amount", precision = 10, scale = 2)
    private BigDecimal discountAmount;
}
