package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.config.SecurityConfig;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.PushDiagnosticsService;
import ar.edu.ofertAR.service.PushNotificationService;
import ar.edu.ofertAR.service.PushTestRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * POST /push/test y GET /push/status con la seguridad real y el servicio de
 * diagnóstico real (rate limit incluido); sólo el envío a Expo está mockeado.
 * Cada test usa otro usuario, porque el rate limit vive en el contexto que
 * comparten.
 */
@WebMvcTest(PushController.class)
@Import({SecurityConfig.class, PushDiagnosticsService.class, PushTestRateLimiter.class})
class PushControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean PushNotificationService pushNotificationService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    private static User usuario(long id) {
        return User.builder().id(id).name("Test").email(id + "@test.com").password("x").build();
    }

    private static PushToken token(String value) {
        return PushToken.builder().token(value).platform("android").build();
    }

    @Test
    @DisplayName("sin sesión es 401")
    void anonimo() throws Exception {
        mvc.perform(post("/push/test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/push/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin cuerpo manda la prueba genérica y devuelve el ticket de cada dispositivo")
    void sinCuerpo() throws Exception {
        User u = usuario(1L);
        when(pushNotificationService.sendToUserNow(eq(u), anyString(), anyString(), eq(Map.of())))
                .thenReturn(List.of(new PushNotificationService.DeliveryResult(
                        token("ExponentPushToken[xyzABC123456]"), true, null, null)));

        mvc.perform(post("/push/test").with(user(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("test"))
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.devices[0].status").value("ok"))
                .andExpect(jsonPath("$.devices[0].tokenSuffix").value("23456]"))
                .andExpect(jsonPath("$.devices[0].platform").value("android"));
    }

    @Test
    @DisplayName("la segunda prueba dentro de los 10 s es 429 con Retry-After, y no llega a Expo")
    void rateLimit() throws Exception {
        User u = usuario(2L);
        when(pushNotificationService.sendToUserNow(any(), anyString(), anyString(), any())).thenReturn(List.of());

        mvc.perform(post("/push/test").with(user(u))).andExpect(status().isOk());
        mvc.perform(post("/push/test").with(user(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"offers\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value("Esperá unos segundos antes de probar de nuevo."));

        verify(pushNotificationService, times(1)).sendToUserNow(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("usuario sin tokens: 200 con devices vacío, no 500")
    void sinTokens() throws Exception {
        User u = usuario(3L);
        when(pushNotificationService.sendToUserNow(eq(u), anyString(), anyString(), any())).thenReturn(List.of());

        mvc.perform(post("/push/test").with(user(u))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"reactivation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("reactivation"))
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.devices").isEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("type inválido es 400 con los valores posibles")
    void tipoInvalido() throws Exception {
        mvc.perform(post("/push/test").with(user(usuario(4L)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"promo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("followedProducts")));

        verifyNoInteractions(pushNotificationService);
    }

    @Test
    @DisplayName("status no expone el token completo")
    void statusSinToken() throws Exception {
        User u = usuario(5L);
        when(pushNotificationService.devicesOf(5L)).thenReturn(List.of(token("ExponentPushToken[secreto-QWERTY]")));

        mvc.perform(get("/push/status").with(user(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(1))
                .andExpect(jsonPath("$.devices[0].tokenSuffix").value("WERTY]"))
                .andExpect(jsonPath("$.devices[0].token").doesNotExist())
                .andExpect(jsonPath("$.offersPushEnabled").value(true))
                .andExpect(content().string(not(containsString("secreto"))));
    }
}
