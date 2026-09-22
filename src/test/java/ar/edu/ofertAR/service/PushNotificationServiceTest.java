package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PushTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private PushTokenRepository pushTokenRepository;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private RestClient restClient;

    /** Runs submitted tasks synchronously, so tests don't need to wait on a
     * background thread to see the effect of sendToUser. */
    private final ExecutorService sameThreadExecutor = new SameThreadExecutorService();

    private PushNotificationService service;

    @BeforeEach
    void setUp() {
        service = new PushNotificationService(pushTokenRepository, restClient, sameThreadExecutor);
    }

    private static User user(Long id) {
        User u = User.builder().name("Test").email(id + "@test.com").password("x").build();
        u.setId(id);
        return u;
    }

    @Nested
    @DisplayName("registro de token")
    class RegisterToken {

        @Test
        @DisplayName("token nuevo: crea la fila")
        void newToken_createsRow() {
            User u = user(1L);
            when(pushTokenRepository.findByToken("TOKEN1")).thenReturn(Optional.empty());

            service.registerToken(u, new RegisterPushTokenRequest("TOKEN1", "android"));

            ArgumentCaptor<PushToken> captor = ArgumentCaptor.forClass(PushToken.class);
            verify(pushTokenRepository).save(captor.capture());
            assertEquals(u, captor.getValue().getUser());
            assertEquals("TOKEN1", captor.getValue().getToken());
            assertEquals("android", captor.getValue().getPlatform());
        }

        @Test
        @DisplayName("token ya registrado por otro usuario: se reasigna, no se duplica")
        void existingToken_reassignsToNewUser() {
            User oldOwner = user(1L);
            User newOwner = user(2L);
            PushToken existing = PushToken.builder().user(oldOwner).token("TOKEN1").platform("android").build();
            when(pushTokenRepository.findByToken("TOKEN1")).thenReturn(Optional.of(existing));

            service.registerToken(newOwner, new RegisterPushTokenRequest("TOKEN1", "ios"));

            verify(pushTokenRepository).save(existing);
            assertEquals(newOwner, existing.getUser());
            assertEquals("ios", existing.getPlatform());
        }
    }

    @Nested
    @DisplayName("envio de push")
    class SendToUser {

        @Test
        @DisplayName("usuario sin tokens registrados: no llama a Expo")
        void noTokens_doesNotCallExpo() {
            User u = user(1L);
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of());

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of());

            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("Expo responde DeviceNotRegistered: el token se borra")
        void deviceNotRegistered_deletesToken() {
            User u = user(1L);
            PushToken token = PushToken.builder().user(u).token("DEAD_TOKEN").platform("android").build();
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(token));

            Map<String, Object> expoResponse = Map.of(
                    "data", List.of(Map.of(
                            "status", "error",
                            "message", "not registered",
                            "details", Map.of("error", "DeviceNotRegistered")
                    ))
            );
            when(restClient.post().uri(any(String.class)).contentType(any()).body(any())
                    .retrieve().body(Map.class)).thenReturn(expoResponse);

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of());

            verify(pushTokenRepository).deleteByToken("DEAD_TOKEN");
        }

        @Test
        @DisplayName("Expo responde ok: no borra ningun token")
        void success_keepsToken() {
            User u = user(1L);
            PushToken token = PushToken.builder().user(u).token("GOOD_TOKEN").platform("android").build();
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(token));

            Map<String, Object> expoResponse = Map.of(
                    "data", List.of(Map.of("status", "ok", "id", "receipt-1"))
            );
            when(restClient.post().uri(any(String.class)).contentType(any()).body(any())
                    .retrieve().body(Map.class)).thenReturn(expoResponse);

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of());

            verify(pushTokenRepository, never()).deleteByToken(any());
        }
    }

    /** Minimal same-thread ExecutorService so sendToUser's async submit runs
     * inline during the test instead of racing a real background thread. */
    private static class SameThreadExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown = false;

        @Override public void shutdown() { shutdown = true; }
        @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }
}
