package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.OfferFeedResponse;
import ar.edu.ofertAR.model.FavoriteStoreChain;
import ar.edu.ofertAR.model.SeenChainOffer;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FavoriteStoreChainRepository;
import ar.edu.ofertAR.repository.SeenChainOfferRepository;
import ar.edu.ofertAR.service.offer.OfferFeedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfferAlertServiceTest {

    @Mock private FavoriteStoreChainRepository favoriteStoreChainRepository;
    @Mock private SeenChainOfferRepository seenChainOfferRepository;
    @Mock private OfferFeedClient offerFeedClient;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private TransactionTemplate transactionTemplate;

    private OfferAlertService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new OfferAlertService(
                favoriteStoreChainRepository, seenChainOfferRepository, offerFeedClient,
                pushNotificationService, transactionTemplate);
        // executeWithoutResult has to actually run what's passed to it for
        // the "new offers get persisted to SeenChainOffer" behavior to show up.
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static User user(Long id, boolean offersPushEnabled) {
        User u = User.builder().name("Test").email(id + "@test.com").password("x").build();
        u.setId(id);
        u.setOffersPushEnabled(offersPushEnabled);
        return u;
    }

    private static OfferFeedResponse.Offer offer(String id, String product, int discountPct) {
        return OfferFeedResponse.Offer.builder()
                .id(id)
                .kind("catalog")
                .retailerSlug("coto")
                .retailerName("Coto")
                .headline("-" + discountPct + "%")
                .productName(product)
                .discountPct(BigDecimal.valueOf(discountPct))
                .build();
    }

    private static OfferFeedResponse feedOf(OfferFeedResponse.Offer... offers) {
        return OfferFeedResponse.builder().items(List.of(offers)).build();
    }

    private static FavoriteStoreChain favorite(User user, String chainSlug) {
        return FavoriteStoreChain.builder().user(user).chainSlug(chainSlug).chainName(chainSlug).build();
    }

    @Nested
    @DisplayName("deteccion de ofertas nuevas")
    class NewOfferDetection {

        @Test
        @DisplayName("oferta nunca vista: se marca como vista y se notifica al que la sigue")
        void neverSeenOffer_marksSeenAndNotifies() {
            User follower = user(1L, true);
            when(favoriteStoreChainRepository.findDistinctChainSlugs()).thenReturn(List.of("coto"));
            when(favoriteStoreChainRepository.findByChainSlug("coto")).thenReturn(List.of(favorite(follower, "coto")));
            when(offerFeedClient.listOffers(List.of("coto"), 1, 50, null, List.of()))
                    .thenReturn(feedOf(offer("catalog:coto:1", "Yerba", 20)));
            when(seenChainOfferRepository.findByChainSlugAndOfferIdIn(eq("coto"), any())).thenReturn(List.of());

            service.runOfferAlertJob();

            ArgumentCaptor<SeenChainOffer> seenCaptor = ArgumentCaptor.forClass(SeenChainOffer.class);
            verify(seenChainOfferRepository).save(seenCaptor.capture());
            assertEquals("coto", seenCaptor.getValue().getChainSlug());
            assertEquals("catalog:coto:1", seenCaptor.getValue().getOfferId());

            ArgumentCaptor<OfferFeedResponse.Offer> notifyCaptor = ArgumentCaptor.forClass(OfferFeedResponse.Offer.class);
            verify(pushNotificationService).notifyNewOffer(eq(follower), notifyCaptor.capture());
            assertEquals("catalog:coto:1", notifyCaptor.getValue().getId());
        }

        @Test
        @DisplayName("oferta ya vista antes: no se vuelve a notificar")
        void alreadySeenOffer_isSkipped() {
            when(favoriteStoreChainRepository.findDistinctChainSlugs()).thenReturn(List.of("coto"));
            when(offerFeedClient.listOffers(List.of("coto"), 1, 50, null, List.of()))
                    .thenReturn(feedOf(offer("catalog:coto:1", "Yerba", 20)));
            when(seenChainOfferRepository.findByChainSlugAndOfferIdIn(eq("coto"), any()))
                    .thenReturn(List.of(SeenChainOffer.builder().chainSlug("coto").offerId("catalog:coto:1").build()));

            service.runOfferAlertJob();

            verify(seenChainOfferRepository, never()).save(any());
            verifyNoInteractions(pushNotificationService);
        }

        @Test
        @DisplayName("usuario con alertas de ofertas apagadas: no lo notifica")
        void offersPushDisabled_isSkipped() {
            User follower = user(1L, false);
            when(favoriteStoreChainRepository.findDistinctChainSlugs()).thenReturn(List.of("coto"));
            when(favoriteStoreChainRepository.findByChainSlug("coto")).thenReturn(List.of(favorite(follower, "coto")));
            when(offerFeedClient.listOffers(List.of("coto"), 1, 50, null, List.of()))
                    .thenReturn(feedOf(offer("catalog:coto:1", "Yerba", 20)));
            when(seenChainOfferRepository.findByChainSlugAndOfferIdIn(eq("coto"), any())).thenReturn(List.of());

            service.runOfferAlertJob();

            verifyNoInteractions(pushNotificationService);
        }
    }

    @Nested
    @DisplayName("tope de un push por usuario por corrida")
    class PerUserCap {

        @Test
        @DisplayName("usuario sigue dos cadenas con ofertas nuevas: recibe un solo push, con la de mayor descuento")
        void userFollowsTwoChainsWithNewOffers_getsOnlyBestOne() {
            User follower = user(1L, true);
            when(favoriteStoreChainRepository.findDistinctChainSlugs()).thenReturn(List.of("coto", "jumbo"));
            when(favoriteStoreChainRepository.findByChainSlug("coto")).thenReturn(List.of(favorite(follower, "coto")));
            when(favoriteStoreChainRepository.findByChainSlug("jumbo")).thenReturn(List.of(favorite(follower, "jumbo")));

            when(offerFeedClient.listOffers(List.of("coto"), 1, 50, null, List.of()))
                    .thenReturn(feedOf(offer("catalog:coto:1", "Yerba", 15)));
            when(offerFeedClient.listOffers(List.of("jumbo"), 1, 50, null, List.of()))
                    .thenReturn(feedOf(offer("catalog:jumbo:1", "Fideos", 40)));
            when(seenChainOfferRepository.findByChainSlugAndOfferIdIn(anyString(), any())).thenReturn(List.of());

            service.runOfferAlertJob();

            ArgumentCaptor<OfferFeedResponse.Offer> notifyCaptor = ArgumentCaptor.forClass(OfferFeedResponse.Offer.class);
            verify(pushNotificationService, times(1)).notifyNewOffer(eq(follower), notifyCaptor.capture());
            assertEquals("catalog:jumbo:1", notifyCaptor.getValue().getId(), "40% le gana a 15%");
        }
    }
}
