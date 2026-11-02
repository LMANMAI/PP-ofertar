package ar.edu.ofertAR.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Un beneficio que el usuario declaró (jubilado, plan sueldo, Club La Nación...).
 * Las promos que lo piden sólo le matchean si lo declaró (D6 del SPEC de promos
 * bancarias).
 */
@Entity
@Table(name = "user_benefits")
@IdClass(UserBenefit.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBenefit {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Slug del catálogo: jubilado | empleado_publico | plan_sueldo | club_la_nacion | jumbo_mas | mi_carrefour. */
    @Id
    @Column(nullable = false, length = 40)
    private String benefit;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private String benefit;
    }
}
