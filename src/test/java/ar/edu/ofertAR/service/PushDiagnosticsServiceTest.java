package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.PushStatusResponse;
import ar.edu.ofertAR.dto.response.PushTestResponse;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushDiagnosticsServiceTest {

    @Mock private PushNotificationService pushNotificationService;
    @Mock private PushTestRateLimiter rateLimiter;

    private PushDiagnosticsService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PushDiagnosticsService(pushNotificationService, rateLimiter);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(rateLimiter.tryAcquire(7L)).thenReturn(OptionalLong.empty());
    }

    private static PushToken token(String value, String platform, LocalDateTime lastSeen) {
        return PushToken.builder().token(value).platform(platform).lastSeenAt(lastSeen).build();
    }

    @Test
    @DisplayName("usuario sin dispositivos: devices vacío y un mensaje que invita a activarlas")
    void sinDispositivos() {
        when(pushNotificationService.sendToUserNow(eq(user), anyString(), anyString(), any())).thenReturn(List.of());

        PushTestResponse r = service.sendTest(user, null);

        assertFalse(r.sent());
        assertTrue(r.devices().isEmpty());
        assertEquals("test", r.type());
        assertEquals(PushDiagnosticsService.SIN_DISPOSITIVOS, r.message());
    }

    @Test
    @DisplayName("rate limit: 429 con Retry-After y no llama a Expo")
    void rateLimit() {
        when(rateLimiter.tryAcquire(7L)).thenReturn(OptionalLong.of(6));

        PushDiagnosticsService.TooManyRequests e =
                assertThrows(PushDiagnosticsService.TooManyRequests.class, () -> service.sendTest(user, "offers"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertEquals("6", e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    @DisplayName("tipo inválido: IllegalArgumentException (400) sin consumir el rate limit")
    void tipoInvalido() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sendTest(user, "promo"));

        assertTrue(e.getMessage().contains("followedProducts"), e.getMessage());
        verifyNoInteractions(rateLimiter, pushNotificationService);
    }

    @ParameterizedTest
    @EnumSource(PushTestType.class)
    @DisplayName("cada tipo manda el mismo data que el aviso real")
    void mismoDataQueElAvisoReal(PushTestType type) {
        when(pushNotificationService.sendToUserNow(eq(user), anyString(), anyString(), any())).thenReturn(List.of());

        service.sendTest(user, type.wire());

        Map<String, String> esperado = switch (type) {
            case TEST -> Map.of();
            case FOLLOWED_PRODUCTS -> Map.of("screen", "followedProducts");
            case OFFERS -> Map.of("screen", "offers");
            case REACTIVATION -> Map.of("screen", "scanMethod");
        };
        verify(pushNotificationService).sendToUserNow(eq(user), anyString(), anyString(), eq(esperado));
    }

    @Test
    @DisplayName("resultado por dispositivo, sin el token completo")
    void resultadoPorDispositivo() {
        PushToken ok = token("ExponentPushToken[aaaaaaaaaaaaOK1234]", "android", null);
        PushToken muerto = token("ExponentPushToken[bbbbbbbbbbbbDEAD99]", "ios", null);
        when(pushNotificationService.sendToUserNow(eq(user), anyString(), anyString(), any())).thenReturn(List.of(
                new PushNotificationService.DeliveryResult(ok, true, null, null),
                new PushNotificationService.DeliveryResult(muerto, false, "not registered", "DeviceNotRegistered")));

        PushTestResponse r = service.sendTest(user, "followedProducts");

        assertTrue(r.sent());
        assertEquals("followedProducts", r.type());
        assertEquals(2, r.devices().size());
        assertEquals("ok", r.devices().get(0).status());
        assertEquals("K1234]", r.devices().get(0).tokenSuffix());
        assertEquals("error", r.devices().get(1).status());
        assertEquals("DeviceNotRegistered", r.devices().get(1).error());
        assertTrue(r.message().contains("falló en 1 de tus 2"), r.message());
    }

    @Test
    @DisplayName("status: dispositivos del más reciente al más viejo, token recortado y preferencias")
    void status() {
        user.setOffersPushEnabled(false);
        LocalDateTime ayer = LocalDateTime.now().minusDays(1);
        when(pushNotificationService.devicesOf(7L)).thenReturn(List.of(
                token("ExponentPushToken[viejo-000000]", "ios", ayer.minusDays(10)),
                token("ExponentPushToken[nuevo-111111]", "android", ayer)));

        PushStatusResponse r = service.status(user);

        assertEquals(2, r.deviceCount());
        assertEquals("android", r.devices().get(0).platform());
        assertEquals("11111]", r.devices().get(0).tokenSuffix());
        assertFalse(r.offersPushEnabled());
        assertNull(r.lastReactivationNudgeAt());
    }

    @Test
    @DisplayName("un token muy corto no se muestra")
    void tokenCorto() {
        assertEquals("", PushDiagnosticsService.tokenSuffix("abc123"));
        assertEquals("", PushDiagnosticsService.tokenSuffix(null));
        assertEquals("bc1234", PushDiagnosticsService.tokenSuffix("abc1234"));
    }
}
