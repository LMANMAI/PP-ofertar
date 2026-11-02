package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resultado de correr a mano un job de notificaciones para un usuario
 * ({@code POST /admin/push/jobs/{job}}). {@code sent} dice si el job decidió
 * mandarle algo (el envío a Expo sale en segundo plano, como en la corrida
 * programada); {@code reason} explica qué hizo o por qué no mandó nada.
 */
public record PushJobRunResponse(
        @Schema(allowableValues = {"followed", "offers", "reactivation"})
        String job,
        long userId,
        boolean sent,
        String reason,
        int deviceCount
) {}
