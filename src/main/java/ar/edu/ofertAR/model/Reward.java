package ar.edu.ofertAR.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fixed, server-side reward catalog for point redemption. OfertAR has no
 * real paid subscription yet, so redeeming only spends points and logs the
 * transaction — it never triggers a charge or grants an actual benefit.
 * The client sends a {@code rewardId}; its cost is always looked up here,
 * never trusted from the request.
 */
@Getter
public enum Reward {
    MINI_DESCUENTO("mini-descuento", 100, "5% en tu próxima suscripción"),
    DESCUENTO_GRANDE("descuento-grande", 350, "20% en tu próxima suscripción"),
    MES_GRATIS("mes-gratis", 1700, "1 mes gratis de suscripción");

    private final String id;
    private final int cost;
    private final String title;

    Reward(String id, int cost, String title) {
        this.id = id;
        this.cost = cost;
        this.title = title;
    }

    public static Optional<Reward> fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }
}
