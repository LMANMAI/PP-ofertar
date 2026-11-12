package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Un emisor de payment_issuers: banco, billetera, emisora de tarjeta o club. */
public record PaymentIssuerView(
        String slug,
        String name,
        @Schema(allowableValues = {"bank", "wallet", "card_issuer", "club"})
        String kind
) {
}
