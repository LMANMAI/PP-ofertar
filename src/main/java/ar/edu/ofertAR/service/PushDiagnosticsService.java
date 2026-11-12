package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.PushDeliveryResponse;
import ar.edu.ofertAR.dto.response.PushDeviceResponse;
import ar.edu.ofertAR.dto.response.PushStatusResponse;
import ar.edu.ofertAR.dto.response.PushTestResponse;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * Lo que necesita un usuario para saber si sus notificaciones andan: ver qué
 * dispositivos tiene registrados y mandarse una de prueba.
 */
@Service
@RequiredArgsConstructor
public class PushDiagnosticsService {

    /** Cuántos caracteres del final del token se muestran. */
    static final int SUFIJO_TOKEN = 6;

    static final String SIN_DISPOSITIVOS = "No tenés ningún dispositivo registrado para notificaciones. "
            + "Activá las alertas desde tu perfil en la app instalada y volvé a probar.";

    private final PushNotificationService pushNotificationService;
    private final PushTestRateLimiter rateLimiter;

    /**
     * Manda al propio usuario una notificación de prueba, ya, salteando la
     * deduplicación de los jobs (no lee ni escribe su estado). El tipo se
     * valida antes de contar el intento, así un 400 no consume la ventana.
     */
    public PushTestResponse sendTest(User user, String rawType) {
        PushTestType type = PushTestType.fromWire(rawType);

        OptionalLong espera = rateLimiter.tryAcquire(user.getId());
        if (espera.isPresent()) {
            throw new TooManyRequests(espera.getAsLong());
        }

        List<PushNotificationService.DeliveryResult> results =
                pushNotificationService.sendToUserNow(user, type.title(), type.body(), type.data());
        if (results.isEmpty()) {
            return new PushTestResponse(type.wire(), false, SIN_DISPOSITIVOS, List.of());
        }

        List<PushDeliveryResponse> devices = results.stream()
                .map(r -> new PushDeliveryResponse(
                        r.device().getPlatform(),
                        tokenSuffix(r.device().getToken()),
                        r.ok() ? "ok" : "error",
                        r.message(),
                        r.error()))
                .toList();
        long ok = results.stream().filter(PushNotificationService.DeliveryResult::ok).count();
        String message;
        if (ok == results.size()) {
            message = "Te mandamos una notificación de prueba. Puede tardar unos segundos.";
        } else if (ok > 0) {
            message = "Te mandamos la notificación de prueba, pero falló en " + (results.size() - ok)
                    + " de tus " + results.size() + " dispositivos.";
        } else {
            message = "No pudimos mandar la notificación de prueba a ninguno de tus dispositivos.";
        }
        return new PushTestResponse(type.wire(), ok > 0, message, devices);
    }

    public PushStatusResponse status(User user) {
        List<PushDeviceResponse> devices = pushNotificationService.devicesOf(user.getId()).stream()
                .sorted(Comparator.comparing(PushToken::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(t -> new PushDeviceResponse(t.getPlatform(), tokenSuffix(t.getToken()), t.getCreatedAt(), t.getLastSeenAt()))
                .toList();
        return new PushStatusResponse(devices.size(), devices, user.isOffersPushEnabled(), user.getLastReactivationNudgeAt());
    }

    /** Los últimos {@value #SUFIJO_TOKEN} caracteres; nada si el token es tan
     * corto que eso sería mostrarlo casi entero. */
    static String tokenSuffix(String token) {
        if (token == null || token.length() <= SUFIJO_TOKEN) {
            return "";
        }
        return token.substring(token.length() - SUFIJO_TOKEN);
    }

    /** 429 con Retry-After. */
    public static class TooManyRequests extends ResponseStatusException {
        private final long retryAfterSeconds;

        TooManyRequests(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Esperá unos segundos antes de probar de nuevo.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            return headers;
        }
    }
}
