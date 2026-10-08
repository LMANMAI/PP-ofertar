package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.FollowProductRequest;
import ar.edu.ofertAR.dto.response.FollowedProductsResponse;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import ar.edu.ofertAR.service.offer.OfferMatchClient;
import ar.edu.ofertAR.service.offer.OfferMatchClient.CampaignOffer;
import ar.edu.ofertAR.service.offer.OfferMatchClient.OfferMatch;
import ar.edu.ofertAR.service.offer.OfferMatchClient.ProductQuery;
import ar.edu.ofertAR.service.offer.OfferMatchClient.PromoMechanic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowedProductServiceTest {

    private static final String EAN = "7790895000997";

    @Mock private FollowedProductRepository repository;
    @Mock private OfferMatchClient offerMatchClient;
    @Mock private FavoriteStoreService favoriteStoreService;
    @Mock private ProductoImagenService productoImagenService;

    private FollowedProductService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new FollowedProductService(repository, offerMatchClient, favoriteStoreService, productoImagenService);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(favoriteStoreService.getFavoriteChainSlugs(any())).thenReturn(List.of());
        lenient().when(productoImagenService.imagenesPorEan(any())).thenReturn(Map.of());
    }

    private FollowedProduct followed(String ean, String description) {
        return FollowedProduct.builder().user(user).ean(ean).description(description).build();
    }

    /** A catalog match: where the product is sold and for how much. */
    static OfferMatch catalog(String price, String listPrice, List<String> labels, Integer requiredQuantity) {
        return new OfferMatch("COCA COLA", "Carrefour", "Gaseosa Coca Cola 2.25 l.", null,
                new BigDecimal(price), listPrice == null ? null : new BigDecimal(listPrice), null,
                labels.isEmpty() ? null : labels.get(0), labels, requiredQuantity, null,
                List.of(), List.of(), List.of());
    }

    static OfferMatch withMechanic() {
        return new OfferMatch("COCA COLA", null, null, null, null, null, null, null, List.of(), null, null,
                List.of(new PromoMechanic("Coto", "Gaseosa Coca Cola 2.25 l.", null,
                        new BigDecimal("3500"), null, List.of("3x2"), 3, null)),
                List.of(), List.of());
    }

    static OfferMatch withCampaign() {
        return new OfferMatch("COCA COLA", null, null, null, null, null, null, null, List.of(), null, null,
                List.of(), List.of(),
                List.of(new CampaignOffer("abc", "Jumbo", null, null, null, null, List.of(30), "percentage_off", false)));
    }

    @Nested
    @DisplayName("qué cuenta como estar en promo")
    class InPromo {

        @Test
        @DisplayName("un precio por debajo del de lista, sí")
        void priceBelowList() {
            assertTrue(FollowedProductService.isInPromo(catalog("2800", "3500", List.of(), null)));
        }

        @Test
        @DisplayName("una condición de cantidad sobre el precio de catálogo, sí")
        void quantityCondition() {
            assertTrue(FollowedProductService.isInPromo(catalog("3500", "3500", List.of("80% en la 2da unidad"), 2)));
        }

        @Test
        @DisplayName("un 3x2 sin descuento por unidad, sí")
        void mechanicWithoutUnitDiscount() {
            OfferMatch match = withMechanic();
            assertTrue(FollowedProductService.isInPromo(match));
            assertEquals("Coto", FollowedProductService.promoRetailer(match));
        }

        @Test
        @DisplayName("una campaña de la marca, sí")
        void campaign() {
            OfferMatch match = withCampaign();
            assertTrue(FollowedProductService.isInPromo(match));
            assertEquals("Jumbo", FollowedProductService.promoRetailer(match));
        }

        @Test
        @DisplayName("al precio de siempre y solo con el descuento de la tarjeta de la cadena, no")
        void onlyCardLabels() {
            OfferMatch match = catalog("3500", "3500", List.of("Tarjeta Carrefour 15%"), 1);
            assertFalse(FollowedProductService.isInPromo(match));
            assertNull(FollowedProductService.promoRetailer(match));
        }

        @Test
        @DisplayName("sin nada encontrado, no")
        void nothing() {
            assertFalse(FollowedProductService.isInPromo(OfferMatch.none()));
        }
    }

    @Nested
    @DisplayName("la lista")
    class Listing {

        @Test
        @DisplayName("sin productos seguidos no se consulta el servicio de ofertas")
        void empty_doesNotCallOffers() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of());

            FollowedProductsResponse response = service.list(user);

            assertTrue(response.isPromosChecked());
            assertTrue(response.getItems().isEmpty());
            verifyNoInteractions(offerMatchClient);
        }

        @Test
        @DisplayName("pregunta por nombre y código, en las cadenas favoritas y sin marcas alternativas")
        void asksForTheseProductsInFavouriteChains() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L))
                    .thenReturn(List.of(followed(EAN, "Gaseosa Coca Cola 2.25 L"), followed("7791234567898", "Yerba")));
            when(favoriteStoreService.getFavoriteChainSlugs(user)).thenReturn(List.of("coto", "jumbo"));
            when(offerMatchClient.tryMatchProducts(any(), anyBoolean(), any()))
                    .thenReturn(Optional.of(List.of(withMechanic(), OfferMatch.none())));

            FollowedProductsResponse response = service.list(user);

            verify(offerMatchClient).tryMatchProducts(
                    eq(List.of(new ProductQuery("Gaseosa Coca Cola 2.25 L", EAN), new ProductQuery("Yerba", "7791234567898"))),
                    eq(false),
                    eq(List.of("coto", "jumbo")));
            assertTrue(response.isPromosChecked());
            assertTrue(response.getItems().get(0).isInPromo());
            assertEquals("3x2", response.getItems().get(0).getPromoMechanics().get(0).getPromoLabels().get(0));
            assertFalse(response.getItems().get(1).isInPromo());
        }

        @Test
        @DisplayName("los códigos solos se leen sin consultar el servicio de ofertas")
        void eans_doNotCallOffers() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L))
                    .thenReturn(List.of(followed(EAN, "Gaseosa"), followed("12345670", "Yerba")));

            assertEquals(List.of(EAN, "12345670"), service.listEans(user));
            verifyNoInteractions(offerMatchClient);
        }

        @Test
        @DisplayName("si el servicio de ofertas no responde, la lista llega igual y dice que no se pudo consultar")
        void offersDown_listStillAnswers() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of(followed(EAN, "Gaseosa")));
            when(offerMatchClient.tryMatchProducts(any(), anyBoolean(), any())).thenReturn(Optional.empty());

            FollowedProductsResponse response = service.list(user);

            assertFalse(response.isPromosChecked());
            assertEquals(1, response.getItems().size());
            assertFalse(response.getItems().get(0).isInPromo());
            assertNull(response.getItems().get(0).getBestOffer());
        }
    }

    @Nested
    @DisplayName("seguir y dejar de seguir")
    class FollowUnfollow {

        private final FollowProductRequest request = new FollowProductRequest(EAN, " Gaseosa Coca Cola 2.25 L ", "Coca Cola");

        @Test
        @DisplayName("seguir guarda el producto, todavía sin promo conocida")
        void follow_saves() {
            when(repository.findByUserIdAndEan(7L, EAN)).thenReturn(Optional.empty());

            service.follow(user, request);

            ArgumentCaptor<FollowedProduct> saved = ArgumentCaptor.forClass(FollowedProduct.class);
            verify(repository).save(saved.capture());
            assertEquals("Gaseosa Coca Cola 2.25 L", saved.getValue().getDescription());
            assertEquals("Coca Cola", saved.getValue().getBrand());
            assertFalse(saved.getValue().isInPromo());
            assertNull(saved.getValue().getLastNotifiedAt());
        }

        @Test
        @DisplayName("seguir uno que ya se sigue no hace nada")
        void follow_twice_isNoOp() {
            when(repository.findByUserIdAndEan(7L, EAN)).thenReturn(Optional.of(followed(EAN, "Gaseosa")));

            service.follow(user, request);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("dos toques a la vez: el que choca con la clave única no es un error")
        void follow_race_isSwallowed() {
            when(repository.findByUserIdAndEan(7L, EAN)).thenReturn(Optional.empty());
            when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

            assertDoesNotThrow(() -> service.follow(user, request));
        }

        @Test
        @DisplayName("al llegar al tope, el siguiente se rechaza con un mensaje para el usuario")
        void follow_atLimit_isRejected() {
            when(repository.findByUserIdAndEan(7L, EAN)).thenReturn(Optional.empty());
            when(repository.countByUserId(7L)).thenReturn((long) FollowedProductService.MAX_FOLLOWED);

            assertThrows(IllegalArgumentException.class, () -> service.follow(user, request));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("dejar de seguir uno que no se seguía no es un error")
        void unfollow_unknown_isNoOp() {
            when(repository.findByUserIdAndEan(7L, EAN)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.unfollow(user, EAN));
            verify(repository, never()).delete(any());
        }
    }
}
