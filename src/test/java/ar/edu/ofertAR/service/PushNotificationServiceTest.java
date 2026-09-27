package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.RegisterPushTokenRequest;
import ar.edu.ofertAR.model.PushToken;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.PushTokenRepository;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private PushTokenRepository pushTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private TicketRepository ticketRepository;
    /** Plano, y la cadena fluida armada a mano en {@link #stubExpoResponse}.
     *  Con RETURNS_DEEP_STUBS los tres tests de envio pasaban a mentir: ver el
     *  comentario del helper. */
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec restClientUriSpec;
    @Mock private RestClient.RequestBodySpec restClientBodySpec;
    @Mock private RestClient.ResponseSpec restClientResponseSpec;
    @Mock private TransactionTemplate transactionTemplate;

    /** Runs submitted tasks synchronously, so tests don't need to wait on a
     * background thread to see the effect of sendToUser. */
    private final ExecutorService sameThreadExecutor = new SameThreadExecutorService();

    private PushNotificationService service;

    @BeforeEach
    void setUp() {
        service = new PushNotificationService(
                pushTokenRepository, userRepository, ticketRepository, restClient, sameThreadExecutor, transactionTemplate);
    }

    /** runReactivationJob wraps each candidate in transactionTemplate.executeWithoutResult;
     * this makes the mock actually run what's passed to it instead of doing nothing. */
    @SuppressWarnings("unchecked")
    private void makeTransactionTemplateRunItsAction() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /** El mismo endpoint que PushNotificationService.EXPO_PUSH_URL, que es
     *  privado. Se stubea el string exacto y no anyString() para clavarlo: si
     *  alguien apunta el servicio a otro host, la cadena deja de matchear y
     *  estos tests se caen en vez de seguir en verde contra un destino que no
     *  es Expo. */
    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    /**
     * Arma la cadena fluida del RestClient paso a paso y hace que Expo
     * conteste {@code expoResponse}.
     *
     * <p>Antes era una sola expresion con deep stubs:
     * {@code restClient.post().uri(any(String.class)).contentType(any()).body(any()).retrieve().body(Map.class)}.
     * Compila, stubea sin chistar y no matchea nunca. Los deep stubs indexan
     * cada mock hijo por la invocacion que lo produjo, y un matcher evalua a
     * null al momento de stubear: el stub quedaba registrado contra
     * {@code contentType(null)} mientras el servicio llama
     * {@code contentType(APPLICATION_JSON)}, que devuelve otro hijo sin nada
     * encima. Medido, cada paso devolvia otra instancia:
     *
     * <pre>
     *   afterUri  = RequestBodySpec@1167916234
     *   afterCt   = RequestBodySpec@238169801
     *   afterBody = RequestBodySpec@1006398046
     *   result    = null
     * </pre>
     *
     * <p>Con la respuesta en null, handleTickets corta en su primer if y nunca
     * borra el token muerto: dos tests rojos sobre codigo sano.
     */
    private void stubExpoResponse(Map<String, Object> expoResponse) {
        when(restClient.post()).thenReturn(restClientUriSpec);
        when(restClientUriSpec.uri(EXPO_PUSH_URL)).thenReturn(restClientBodySpec);
        when(restClientBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(restClientBodySpec);
        when(restClientBodySpec.body(any(Object.class))).thenReturn(restClientBodySpec);
        when(restClientBodySpec.retrieve()).thenReturn(restClientResponseSpec);
        when(restClientResponseSpec.body(Map.class)).thenReturn(expoResponse);
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
            stubExpoResponse(Map.of(
                    "data", List.of(Map.of(
                            "status", "error",
                            "message", "not registered",
                            "details", Map.of("error", "DeviceNotRegistered")
                    ))
            ));

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of());

            verify(pushTokenRepository).deleteByToken("DEAD_TOKEN");
        }

        @Test
        @DisplayName("Expo responde ok: no borra ningun token")
        void success_keepsToken() {
            User u = user(1L);
            PushToken token = PushToken.builder().user(u).token("GOOD_TOKEN").platform("android").build();
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(token));
            stubExpoResponse(Map.of(
                    "data", List.of(Map.of("status", "ok", "id", "receipt-1"))
            ));

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of());

            verify(pushTokenRepository, never()).deleteByToken(any());
        }

        @Test
        @DisplayName("lo que se postea a Expo lleva los tokens, el titulo y el cuerpo")
        void postsExpectedPayload() {
            // El que hace ruido si la cadena del RestClient se vuelve a desarmar.
            // Los otros dos miran el efecto -que un token se borre o no-, y ese
            // efecto es ambiguo: con la cadena rota la respuesta es null,
            // handleTickets corta en el primer if y "no borro nada" se lee igual
            // que "Expo contesto ok". Aca se afirma que la llamada ocurrio, con
            // que cuerpo, y de paso queda documentado el payload de Expo.
            User u = user(1L);
            PushToken a = PushToken.builder().user(u).token("TOKEN_A").platform("android").build();
            PushToken b = PushToken.builder().user(u).token("TOKEN_B").platform("ios").build();
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(a, b));
            stubExpoResponse(Map.of("data", List.of(
                    Map.of("status", "ok", "id", "r-1"),
                    Map.of("status", "ok", "id", "r-2"))));

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of("screen", "offers"));

            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            verify(restClientBodySpec).body(sent.capture());

            assertInstanceOf(List.class, sent.getValue());
            List<?> messages = (List<?>) sent.getValue();
            assertEquals(1, messages.size(), "Expo recibe un solo mensaje con todos los destinatarios");
            assertInstanceOf(Map.class, messages.get(0));
            Map<?, ?> message = (Map<?, ?>) messages.get(0);

            // El orden importa: handleTickets casa el ticket i con el token i.
            assertEquals(List.of("TOKEN_A", "TOKEN_B"), message.get("to"));
            assertEquals("Titulo", message.get("title"));
            assertEquals("Cuerpo", message.get("body"));
            assertEquals(Map.of("screen", "offers"), message.get("data"));
        }
    }

    @Nested
    @DisplayName("job de reactivacion")
    class ReactivationJob {

        @Test
        @DisplayName("usuario inactivo, nunca nudgeado, con alertas prendidas: recibe el push y queda marcado")
        void dormantUser_neverNudged_getsNudged() {
            makeTransactionTemplateRunItsAction();
            User u = user(1L);
            when(ticketRepository.findUserIdsWithLastTicketBefore(any())).thenReturn(List.of(1L));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of());

            service.runReactivationJob();

            assertNotNull(u.getLastReactivationNudgeAt());
            verify(userRepository).save(u);
        }

        @Test
        @DisplayName("alertas de ofertas apagadas: no nudgea")
        void offersPushDisabled_isSkipped() {
            makeTransactionTemplateRunItsAction();
            User u = user(1L);
            u.setOffersPushEnabled(false);
            when(ticketRepository.findUserIdsWithLastTicketBefore(any())).thenReturn(List.of(1L));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));

            service.runReactivationJob();

            assertNull(u.getLastReactivationNudgeAt());
            verify(userRepository, never()).save(any());
            verifyNoInteractions(pushTokenRepository);
        }

        @Test
        @DisplayName("nudgeado hace menos de 30 dias: no nudgea de nuevo")
        void withinCooldown_isSkipped() {
            makeTransactionTemplateRunItsAction();
            User u = user(1L);
            u.setLastReactivationNudgeAt(LocalDateTime.now().minusDays(5));
            when(ticketRepository.findUserIdsWithLastTicketBefore(any())).thenReturn(List.of(1L));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));

            service.runReactivationJob();

            verify(userRepository, never()).save(any());
            verifyNoInteractions(pushTokenRepository);
        }

        @Test
        @DisplayName("nudgeado hace mas de 30 dias: vuelve a nudgear")
        void pastCooldown_getsNudgedAgain() {
            makeTransactionTemplateRunItsAction();
            User u = user(1L);
            u.setLastReactivationNudgeAt(LocalDateTime.now().minusDays(40));
            when(ticketRepository.findUserIdsWithLastTicketBefore(any())).thenReturn(List.of(1L));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of());

            service.runReactivationJob();

            verify(userRepository).save(u);
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
