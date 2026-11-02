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
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("payload y resultado por dispositivo")
    class PayloadAndResults {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ArgumentCaptor<Object> stubExpo(Map<String, Object> expoResponse) {
            ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
            when(restClient.post()).thenReturn(restClientUriSpec);
            when(restClientUriSpec.uri(EXPO_PUSH_URL)).thenReturn(restClientBodySpec);
            when(restClientBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(restClientBodySpec);
            when(restClientBodySpec.body(body.capture())).thenReturn(restClientBodySpec);
            when(restClientBodySpec.retrieve()).thenReturn(restClientResponseSpec);
            when(restClientResponseSpec.body(Map.class)).thenReturn(expoResponse);
            return body;
        }

        @Test
        @DisplayName("el mensaje que sale para Expo va con prioridad high y el data intacto")
        @SuppressWarnings("unchecked")
        void sentMessage_hasHighPriority() {
            User u = user(1L);
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(
                    PushToken.builder().user(u).token("T1").platform("android").build()));
            ArgumentCaptor<Object> body = stubExpo(Map.of("data", List.of(Map.of("status", "ok"))));

            service.sendToUser(u, "Titulo", "Cuerpo", Map.of("screen", "offers"));

            List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getValue();
            assertEquals(1, messages.size());
            assertEquals("high", messages.get(0).get("priority"));
            assertEquals(List.of("T1"), messages.get(0).get("to"));
            assertEquals(Map.of("screen", "offers"), messages.get(0).get("data"));
            assertFalse(messages.get(0).containsKey("channelId"), "la app no crea canales propios");
        }

        @Test
        @DisplayName("sendToUserNow devuelve el ticket de cada dispositivo y sigue limpiando los tokens muertos")
        void sendNow_returnsTicketsAndCleansDeadTokens() {
            User u = user(1L);
            PushToken good = PushToken.builder().user(u).token("GOOD").platform("android").build();
            PushToken dead = PushToken.builder().user(u).token("DEAD").platform("ios").build();
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(good, dead));
            stubExpo(Map.of("data", List.of(
                    Map.of("status", "ok", "id", "r1"),
                    Map.of("status", "error", "message", "not registered",
                            "details", Map.of("error", "DeviceNotRegistered")))));

            List<PushNotificationService.DeliveryResult> results = service.sendToUserNow(u, "T", "B", Map.of());

            assertEquals(2, results.size());
            assertTrue(results.get(0).ok());
            assertSame(good, results.get(0).device());
            assertFalse(results.get(1).ok());
            assertEquals("DeviceNotRegistered", results.get(1).error());
            assertEquals("not registered", results.get(1).message());
            verify(pushTokenRepository).deleteByToken("DEAD");
            verify(pushTokenRepository, never()).deleteByToken("GOOD");
        }

        @Test
        @DisplayName("sendToUserNow sin dispositivos: lista vacía, sin llamar a Expo")
        void sendNow_noTokens() {
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of());

            assertTrue(service.sendToUserNow(user(1L), "T", "B", Map.of()).isEmpty());
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("sendToUserNow con Expo caído: error por dispositivo, no excepción")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void sendNow_expoDown_isErrorPerDevice() {
            User u = user(1L);
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of(
                    PushToken.builder().user(u).token("T1").platform("android").build()));
            stubExpoResponse(Map.of());
            when(restClientResponseSpec.body(Map.class)).thenThrow(new IllegalStateException("timeout"));

            List<PushNotificationService.DeliveryResult> results = service.sendToUserNow(u, "T", "B", Map.of());

            assertEquals(1, results.size());
            assertFalse(results.get(0).ok());
            verify(pushTokenRepository, never()).deleteByToken(any());
        }
    }

    @Nested
    @DisplayName("reactivacion para un solo usuario")
    class ReactivationForUser {

        @SuppressWarnings("unchecked")
        private void makeExecuteRunItsCallback() {
            when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                    invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class)
                            .doInTransaction(null));
        }

        @Test
        @DisplayName("nunca escaneó: no se le manda y se explica")
        void neverScanned_isSkipped() {
            when(ticketRepository.findLastTicketCreatedAtByUserId(1L)).thenReturn(null);

            PushJobOutcome outcome = service.runReactivationForUser(1L);

            assertFalse(outcome.sent());
            assertTrue(outcome.reason().contains("nunca escaneó"), outcome.reason());
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("escaneó hace poco: no está inactivo")
        void recentScan_isSkipped() {
            when(ticketRepository.findLastTicketCreatedAtByUserId(1L)).thenReturn(LocalDateTime.now().minusDays(2));

            PushJobOutcome outcome = service.runReactivationForUser(1L);

            assertFalse(outcome.sent());
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("inactivo y fuera de cooldown: misma evaluación que el job, se manda y queda marcado")
        void dormant_getsNudged() {
            makeExecuteRunItsCallback();
            User u = user(1L);
            when(ticketRepository.findLastTicketCreatedAtByUserId(1L)).thenReturn(LocalDateTime.now().minusDays(20));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(pushTokenRepository.findByUserId(1L)).thenReturn(List.of());

            PushJobOutcome outcome = service.runReactivationForUser(1L);

            assertTrue(outcome.sent());
            assertNotNull(u.getLastReactivationNudgeAt());
            verify(userRepository).save(u);
        }

        @Test
        @DisplayName("inactivo pero dentro del cooldown: lo dice y no lo vuelve a marcar")
        void dormant_inCooldown_isSkipped() {
            makeExecuteRunItsCallback();
            User u = user(1L);
            u.setLastReactivationNudgeAt(LocalDateTime.now().minusDays(3));
            when(ticketRepository.findLastTicketCreatedAtByUserId(1L)).thenReturn(LocalDateTime.now().minusDays(20));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));

            PushJobOutcome outcome = service.runReactivationForUser(1L);

            assertFalse(outcome.sent());
            assertTrue(outcome.reason().contains("menos de 30 días"), outcome.reason());
            verify(userRepository, never()).save(any());
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

    @Nested
    @DisplayName("productos seguidos en promo")
    class FollowedProductsInPromo {

        private PushNotificationService spied;

        @BeforeEach
        void spyOnSend() {
            spied = spy(service);
            lenient().doNothing().when(spied).sendToUser(any(), any(), any(), any());
        }

        private String[] sent() {
            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(spied).sendToUser(any(), title.capture(), body.capture(), eq(Map.of("screen", "followedProducts")));
            return new String[] {title.getValue(), body.getValue()};
        }

        @Test
        @DisplayName("uno solo: se nombra el producto y la cadena")
        void one_namesProductAndChain() {
            spied.notifyFollowedProductsInPromo(user(1L),
                    List.of(new PushNotificationService.FollowedPromo("Gaseosa Coca Cola 2.25 L", "Coto")));

            assertArrayEquals(new String[] {
                    "Un producto que seguís está en promo",
                    "Gaseosa Coca Cola 2.25 L tiene una promo en Coto."}, sent());
        }

        @Test
        @DisplayName("sin cadena conocida no se inventa una")
        void one_withoutChain() {
            spied.notifyFollowedProductsInPromo(user(1L),
                    List.of(new PushNotificationService.FollowedPromo("Yerba", null)));

            assertEquals("Yerba tiene una promo.", sent()[1]);
        }

        @Test
        @DisplayName("varios: se cuentan en vez de listarlos todos")
        void several_areCounted() {
            spied.notifyFollowedProductsInPromo(user(1L), List.of(
                    new PushNotificationService.FollowedPromo("Gaseosa", "Coto"),
                    new PushNotificationService.FollowedPromo("Yerba", "Jumbo"),
                    new PushNotificationService.FollowedPromo("Arroz", null)));

            assertArrayEquals(new String[] {
                    "3 productos que seguís están en promo",
                    "Gaseosa y 2 más."}, sent());
        }

        @Test
        @DisplayName("sin nada que avisar no se manda nada")
        void none_sendsNothing() {
            spied.notifyFollowedProductsInPromo(user(1L), List.of());

            verify(spied, never()).sendToUser(any(), any(), any(), any());
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
