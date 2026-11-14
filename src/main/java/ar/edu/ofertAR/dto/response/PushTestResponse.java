package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resultado de {@code POST /push/test}. {@code sent} es true si Expo aceptó el
 * aviso para al menos un dispositivo. Sin dispositivos registrados,
 * {@code devices} viene vacío y {@code message} lo explica.
 */
public record PushTestResponse(
        @Schema(allowableValues = {"test", "followedProducts", "offers", "reactivation"})
        String type,
        boolean sent,
        String message,
        List<PushDeliveryResponse> devices
) {}
