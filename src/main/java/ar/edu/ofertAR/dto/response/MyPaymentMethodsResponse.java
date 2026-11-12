package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * GET y PUT /me/payment-methods: los medios de pago y beneficios que declaró el
 * usuario. Nunca hay números de tarjeta: sólo emisor, red y tipo.
 *
 * @param methods  en el orden en que se cargaron
 * @param benefits slugs del catálogo, en orden alfabético
 */
public record MyPaymentMethodsResponse(List<PaymentMethodView> methods, List<String> benefits) {

    /**
     * @param network  null si no se indicó (p. ej. una billetera)
     * @param cardType null si no se indicó
     */
    public record PaymentMethodView(
            long id,
            PaymentIssuerView issuer,
            @Nullable String network,
            @Nullable String cardType
    ) {
    }
}
