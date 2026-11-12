package ar.edu.ofertAR.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;
import java.util.List;

/** Estado de las notificaciones del usuario: {@code GET /push/status}. */
public record PushStatusResponse(
        int deviceCount,
        List<PushDeviceResponse> devices,
        @Schema(description = "Preferencia de alertas de ofertas: habilita los avisos de ofertas, productos seguidos y reactivación. "
                + "Los de tickets y referidos salen siempre.")
        boolean offersPushEnabled,
        @Nullable
        @Schema(description = "Último recordatorio del job de reactivación; null si nunca se le mandó.")
        LocalDateTime lastReactivationNudgeAt
) {}
