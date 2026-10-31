package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.service.PushNotificationService.FollowedPromo;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static ar.edu.ofertAR.service.FollowedProductServiceTest.catalog;
import static ar.edu.ofertAR.service.FollowedProductServiceTest.catalogOf;
import static ar.edu.ofertAR.service.FollowedProductServiceTest.withLooseMechanicOnly;
import static ar.edu.ofertAR.service.FollowedProductServiceTest.withMechanic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowedProductAlertServiceTest {

    @Mock private FollowedProductRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private FollowedProductService followedProductService;
    @Mock private PushNotificationService pushNotificationService;

    private FollowedProductAlertService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new FollowedProductAlertService(repository, userRepository, followedProductService, pushNotificationService);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(repository.findDistinctUserIds()).thenReturn(List.of(7L));
        lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    }

    private FollowedProduct product(String description, boolean inPromo, LocalDateTime lastNotifiedAt) {
        return FollowedProduct.builder().user(user).ean("7790895000997").description(description)
                .inPromo(inPromo).lastNotifiedAt(lastNotifiedAt).build();
    }

    private void follows(FollowedProduct... products) {
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of(products));
    }

    private void offersAnswer(OfferMatch... matches) {
        when(followedProductService.matchPromos(eq(user), any())).thenReturn(Optional.of(List.of(matches)));
    }

    @SuppressWarnings("unchecked")
    private List<FollowedPromo> announced() {
        ArgumentCaptor<List<FollowedPromo>> captor = ArgumentCaptor.forClass(List.class);
        verify(pushNotificationService).notifyFollowedProductsInPromo(eq(user), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("un producto que entra en promo se avisa una vez, con la cadena, y queda registrado")
    void entersPromo_isAnnouncedAndRecorded() {
        FollowedProduct gaseosa = product("Gaseosa Coca Cola 2.25 L", false, null);
        follows(gaseosa);
        offersAnswer(withMechanic());

        service.runFollowedProductAlertJob();

        assertEquals(List.of(new FollowedPromo("Gaseosa Coca Cola 2.25 L", "Coto")), announced());
        assertTrue(gaseosa.isInPromo());
        assertNotNull(gaseosa.getLastNotifiedAt());
        verify(repository).saveAll(List.of(gaseosa));
    }

    @Test
    @DisplayName("mientras sigue en promo no se vuelve a avisar")
    void staysInPromo_isNotAnnouncedAgain() {
        follows(product("Gaseosa", true, LocalDateTime.now().minusDays(1)));
        offersAnswer(withMechanic());

        service.runFollowedProductAlertJob();

        verifyNoInteractions(pushNotificationService);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("cuando la promo termina se registra, y la siguiente se vuelve a avisar")
    void promoEnds_thenNextOneIsAnnounced() {
        FollowedProduct gaseosa = product("Gaseosa", true, LocalDateTime.now().minusDays(20));
        follows(gaseosa);
        offersAnswer(OfferMatch.none());

        service.runFollowedProductAlertJob();

        assertFalse(gaseosa.isInPromo());
        verify(repository).saveAll(List.of(gaseosa));
        verifyNoInteractions(pushNotificationService);

        offersAnswer(catalog("2800", "3500", List.of(), null));
        service.runFollowedProductAlertJob();

        assertEquals(List.of(new FollowedPromo("Gaseosa", "Carrefour")), announced());
    }

    @Test
    @DisplayName("una promo que vuelve a los pocos días de avisada no se anuncia otra vez")
    void promoBackWithinCooldown_isRecordedButNotAnnounced() {
        FollowedProduct gaseosa = product("Gaseosa", false, LocalDateTime.now().minusDays(2));
        follows(gaseosa);
        offersAnswer(withMechanic());

        service.runFollowedProductAlertJob();

        assertTrue(gaseosa.isInPromo());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    @DisplayName("si el servicio de ofertas no responde no se toca nada: una caída no es el fin de la promo")
    void offersDown_leavesStateUntouched() {
        FollowedProduct gaseosa = product("Gaseosa", true, LocalDateTime.now().minusDays(20));
        follows(gaseosa);
        when(followedProductService.matchPromos(eq(user), any())).thenReturn(Optional.empty());

        service.runFollowedProductAlertJob();

        assertTrue(gaseosa.isInPromo());
        verify(repository, never()).saveAll(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    @DisplayName("con las notificaciones de ofertas apagadas no se consulta ni se registra nada")
    void pushDisabled_isSkippedEntirely() {
        user.setOffersPushEnabled(false);

        service.runFollowedProductAlertJob();

        verifyNoInteractions(followedProductService, pushNotificationService);
        verify(repository, never()).findByUserIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    @DisplayName("varios productos que entran en promo el mismo día van en un solo aviso")
    void severalProducts_oneNotification() {
        follows(product("Gaseosa", false, null), product("Yerba", false, null), product("Arroz Gallo Oro", false, null));
        offersAnswer(withMechanic(), OfferMatch.none(), catalogOf("Arroz largo fino Gallo Oro 1 kg", "brand_text"));

        service.runFollowedProductAlertJob();

        assertEquals(List.of(new FollowedPromo("Gaseosa", "Coto"), new FollowedPromo("Arroz Gallo Oro", "Carrefour")),
                announced());
    }

    @Test
    @DisplayName("una promo que es solo de otro producto parecido no se avisa ni se registra como promo")
    void looseOnlyMatch_isNotAnnounced() {
        FollowedProduct gaseosa = product("Gaseosa Coca Cola 2.25 L", false, null);
        FollowedProduct yerba = product("Yerba Playadito 1 kg", false, null);
        follows(gaseosa, yerba);
        // Lo que la app muestra como "Promo de otro producto" y "Similar en".
        offersAnswer(withLooseMechanicOnly(), catalogOf("Mate cocido Playadito 25 saquitos", "brand_text"));

        service.runFollowedProductAlertJob();

        verifyNoInteractions(pushNotificationService);
        assertFalse(gaseosa.isInPromo());
        assertFalse(yerba.isInPromo());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("una respuesta más corta que la pregunta deja sin tocar lo que no vino")
    void shortAnswer_leavesTheRestUntouched() {
        FollowedProduct yerba = product("Yerba", true, null);
        follows(product("Gaseosa", false, null), yerba);
        offersAnswer(withMechanic());

        service.runFollowedProductAlertJob();

        assertTrue(yerba.isInPromo());
        assertEquals(1, announced().size());
    }

    @Test
    @DisplayName("el fallo con un usuario no corta el job para los demás")
    void oneUserFailing_doesNotStopTheRest() {
        User other = User.builder().id(8L).name("Bea").email("bea@test").password("x").build();
        when(repository.findDistinctUserIds()).thenReturn(List.of(8L, 7L));
        when(userRepository.findById(8L)).thenThrow(new IllegalStateException("db"));
        follows(product("Gaseosa", false, null));
        offersAnswer(withMechanic());

        service.runFollowedProductAlertJob();

        verify(pushNotificationService).notifyFollowedProductsInPromo(eq(user), any());
        verify(pushNotificationService, never()).notifyFollowedProductsInPromo(eq(other), any());
    }
}
