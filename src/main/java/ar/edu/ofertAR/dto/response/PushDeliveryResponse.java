package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Qué contestó Expo para un dispositivo en {@code POST /push/test}. "ok" quiere
 * decir que Expo aceptó el aviso para entregarlo, no que ya llegó al teléfono.
 */
public record PushDeliveryResponse(
        String platform,
        @Schema(description = "Últimos caracteres del token, para reconocer el dispositivo sin exponer el token.")
        String tokenSuffix,
        @Schema(allowableValues = {"ok", "error"})
        String status,
        @Nullable String message,
        @Nullable
        @Schema(description = "Código de error de Expo, p. ej. DeviceNotRegistered (el token se borra).")
        String error
) {}
