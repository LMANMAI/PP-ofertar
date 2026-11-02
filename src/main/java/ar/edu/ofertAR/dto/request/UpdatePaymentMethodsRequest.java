package ar.edu.ofertAR.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PUT /me/payment-methods: el conjunto completo de medios y beneficios del
 * usuario; reemplaza al anterior. A propósito no hay lugar para el número de la
 * tarjeta, el vencimiento ni el CVV, y un campo de más en el JSON se ignora: no
 * se guarda.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePaymentMethodsRequest {

    @NotNull(message = "Falta la lista de medios de pago")
    @Size(max = 20, message = "Podés cargar hasta 20 medios de pago")
    private List<@Valid @NotNull(message = "Hay un medio de pago vacío") PaymentMethodInput> methods;

    @NotNull(message = "Falta la lista de beneficios")
    @Size(max = 20, message = "Hay demasiados beneficios")
    private List<@NotBlank(message = "Hay un beneficio vacío") String> benefits;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentMethodInput {

        /** Slug de GET /payment-methods/catalog → issuers. */
        @NotBlank(message = "Falta el emisor")
        @Size(max = 60, message = "El emisor es demasiado largo")
        private String issuerSlug;

        /** Slug de catalog → networks, u omitido. */
        @Size(max = 20, message = "La red es demasiado larga")
        private String network;

        /** Slug de catalog → cardTypes, u omitido. */
        @Size(max = 20, message = "El tipo es demasiado largo")
        private String cardType;
    }
}
