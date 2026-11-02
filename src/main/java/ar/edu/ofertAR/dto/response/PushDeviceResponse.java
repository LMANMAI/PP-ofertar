package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** Un dispositivo registrado para push, sin el token completo. */
public record PushDeviceResponse(
        String platform,
        @Schema(description = "Últimos caracteres del token, para reconocer el dispositivo sin exponer el token.")
        String tokenSuffix,
        LocalDateTime createdAt,
        LocalDateTime lastSeenAt
) {}
