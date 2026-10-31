package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.FollowProductRequest;
import ar.edu.ofertAR.dto.response.FollowedProductResponse;
import ar.edu.ofertAR.dto.response.FollowedProductsResponse;
import ar.edu.ofertAR.model.FollowedProduct;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.BarcodeLastPurchase;
import ar.edu.ofertAR.repository.FollowedProductRepository;
import ar.edu.ofertAR.repository.TicketRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowedProductServiceTest {

    private static final String EAN = "7790895000997";
    /** El mismo producto, en el formato de 14 dígitos que mezcla SEPA. */
    private static final String EAN_14 = "07790895000997";
    private static final String DESC = "Gaseosa Coca Cola 2.25 L";

    @Mock private FollowedProductRepository repository;
    @Mock private OfferMatchClient offerMatchClient;
    @Mock private FavoriteStoreService favoriteStoreService;
    @Mock private ProductoImagenService productoImagenService;
    @Mock private TicketRepository ticketRepository;

    private FollowedProductService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new FollowedProductService(
                repository, offerMatchClient, favoriteStoreService, productoImagenService, ticketRepository);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(favoriteStoreService.getFavoriteChainSlugs(any())).thenReturn(List.of());
        lenient().when(productoImagenService.imagenesPorEan(any())).thenReturn(Map.of());
        lenient().when(ticketRepository.findLastPurchaseByBarcode(anyLong(), any())).thenReturn(List.of());
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

    /** Una rebaja de catálogo sobre {@code productName}, encontrada con {@code method}
     * ("ean", "brand_text" o null). */
    static OfferMatch catalogOf(String productName, String method) {
        return new OfferMatch("COCA COLA", "Carrefour", productName, null,
                new BigDecimal("2800"), new BigDecimal("3500"), null,
                null, List.of(), null, null,
                List.of(), List.of(), List.of(), method);
    }

    static PromoMechanic mechanic(String retailer, String productName) {
        return new PromoMechanic(retailer, productName, null, new BigDecimal("3500"), null, List.of("3x2"), 3, null);
    }

    static OfferMatch withMechanics(PromoMechanic... mechanics) {
        return new OfferMatch("COCA COLA", null, null, null, null, null, null, null, List.of(), null, null,
                List.of(mechanics), List.of(), List.of());
    }

    static OfferMatch withMechanic() {
        return withMechanics(mechanic("Coto", "Gaseosa Coca Cola 2.25 l."));
    }

    /** Solo un 3x2 de otro producto de la marca: lo que la app muestra como
     * "Promo de otro producto". */
    static OfferMatch withLooseMechanicOnly() {
        return withMechanics(mechanic("Coto", "Agua saborizada Aquarius pomelo 1.5 l."));
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
            assertTrue(FollowedProductService.isInPromo(DESC, catalog("2800", "3500", List.of(), null)));
        }

        @Test
        @DisplayName("una condición de cantidad sobre el precio de catálogo, sí")
        void quantityCondition() {
            assertTrue(FollowedProductService.isInPromo(DESC, catalog("3500", "3500", List.of("80% en la 2da unidad"), 2)));
        }

        @Test
        @DisplayName("un 3x2 sin descuento por unidad, sí")
        void mechanicWithoutUnitDiscount() {
            OfferMatch match = withMechanic();
            assertTrue(FollowedProductService.isInPromo(DESC, match));
            assertEquals("Coto", FollowedProductService.promoRetailer(DESC, match));
        }

        @Test
        @DisplayName("una campaña de la marca, sí")
        void campaign() {
            OfferMatch match = withCampaign();
            assertTrue(FollowedProductService.isInPromo(DESC, match));
            assertEquals("Jumbo", FollowedProductService.promoRetailer(DESC, match));
        }

        @Test
        @DisplayName("al precio de siempre y solo con el descuento de la tarjeta de la cadena, no")
        void onlyCardLabels() {
            OfferMatch match = catalog("3500", "3500", List.of("Tarjeta Carrefour 15%"), 1);
            assertFalse(FollowedProductService.isInPromo(DESC, match));
            assertNull(FollowedProductService.promoRetailer(DESC, match));
        }

        @Test
        @DisplayName("sin nada encontrado, no")
        void nothing() {
            assertFalse(FollowedProductService.isInPromo(DESC, OfferMatch.none()));
        }

        @Test
        @DisplayName("un 3x2 de otro producto de la marca (\"Promo de otro producto\" en la app), no")
        void looseMechanic_isNotAPromo() {
            OfferMatch match = withLooseMechanicOnly();
            assertFalse(FollowedProductService.isInPromo(DESC, match));
            assertNull(FollowedProductService.promoRetailer(DESC, match));
        }

        @Test
        @DisplayName("una rebaja de otro producto encontrada por texto (\"Similar en\" en la app), no")
        void looseCatalogByText_isNotAPromo() {
            OfferMatch match = catalogOf("Agua saborizada Aquarius pomelo 1.5 l.", "brand_text");
            assertFalse(FollowedProductService.isInPromo(DESC, match));
            assertNull(FollowedProductService.promoRetailer(DESC, match));
        }

        @Test
        @DisplayName("una rebaja encontrada por EAN es de este producto aunque el nombre del catálogo difiera")
        void catalogByEan_countsEvenIfNamesDiffer() {
            OfferMatch match = catalogOf("Coca-Cola Sabor Original Botella 2,25 L", "ean");
            assertTrue(FollowedProductService.isInPromo("Bebida cola 2.25", match));
            assertEquals("Carrefour", FollowedProductService.promoRetailer("Bebida cola 2.25", match));
        }

        @Test
        @DisplayName("si hay una mecánica ajena y otra propia, el push nombra la cadena de la propia")
        void retailerOfTheSameProductMechanic() {
            OfferMatch match = withMechanics(
                    mechanic("Coto", "Agua saborizada Aquarius pomelo 1.5 l."),
                    mechanic("Jumbo", "Gaseosa Coca Cola 2.25 l."));
            assertTrue(FollowedProductService.isInPromo(DESC, match));
            assertEquals("Jumbo", FollowedProductService.promoRetailer(DESC, match));
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
                    .thenReturn(List.of(followed(EAN, DESC), followed("7791234567898", "Yerba")));
            when(favoriteStoreService.getFavoriteChainSlugs(user)).thenReturn(List.of("coto", "jumbo"));
            when(offerMatchClient.tryMatchProducts(any(), anyBoolean(), any()))
                    .thenReturn(Optional.of(List.of(withMechanic(), OfferMatch.none())));

            FollowedProductsResponse response = service.list(user);

            verify(offerMatchClient).tryMatchProducts(
                    eq(List.of(new ProductQuery(DESC, EAN), new ProductQuery("Yerba", "7791234567898"))),
                    eq(false),
                    eq(List.of("coto", "jumbo")));
            assertTrue(response.isPromosChecked());
            assertTrue(response.getItems().get(0).isInPromo());
            assertEquals("3x2", response.getItems().get(0).getPromoMechanics().get(0).getPromoLabels().get(0));
            assertFalse(response.getItems().get(1).isInPromo());
        }

        @Test
        @DisplayName("una promo de otro producto viaja en la lista, pero el producto no figura en promo")
        void looseMechanic_listedButNotInPromo() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of(followed(EAN, DESC)));
            when(offerMatchClient.tryMatchProducts(any(), anyBoolean(), any()))
                    .thenReturn(Optional.of(List.of(withLooseMechanicOnly())));

            FollowedProductResponse item = service.list(user).getItems().get(0);

            assertFalse(item.isInPromo());
            assertEquals(1, item.getPromoMechanics().size());
        }

        @Test
        @DisplayName("los códigos solos se leen sin consultar el servicio de ofertas, tal como se guardaron")
        void eans_doNotCallOffers() {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L))
                    .thenReturn(List.of(followed(EAN_14, "Gaseosa"), followed("12345670", "Yerba")));

            assertEquals(List.of(EAN_14, "12345670"), service.listEans(user));
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
    @DisplayName("la última compra")
    class LastPurchased {

        private final LocalDateTime earlier = LocalDateTime.of(2026, 9, 1, 10, 0);
        private final LocalDateTime later = LocalDateTime.of(2026, 10, 20, 18, 30);

        private List<FollowedProductResponse> listWith(List<FollowedProduct> followed, List<BarcodeLastPurchase> purchases) {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(followed);
            when(offerMatchClient.tryMatchProducts(any(), anyBoolean(), any())).thenReturn(Optional.empty());
            when(ticketRepository.findLastPurchaseByBarcode(7L, TicketStatus.PROCESSED)).thenReturn(purchases);
            return service.list(user).getItems();
        }

        @Test
        @DisplayName("comprado: la fecha del ticket más reciente, aunque el código venga en otro formato")
        void bought_latestAcrossFormats() {
            List<FollowedProductResponse> items = listWith(
                    List.of(followed(EAN, DESC)),
                    List.of(new BarcodeLastPurchase(EAN, earlier), new BarcodeLastPurchase(EAN_14, later)));

            assertEquals(later, items.get(0).getLastPurchasedAt());
        }

        @Test
        @DisplayName("un EAN-8 seguido reconoce el ticket que lo trae completado con ceros")
        void ean8_matchesPaddedBarcode() {
            List<FollowedProductResponse> items = listWith(
                    List.of(followed("12345670", "Chicle")),
                    List.of(new BarcodeLastPurchase("0000012345670", earlier)));

            assertEquals(earlier, items.get(0).getLastPurchasedAt());
        }

        @Test
        @DisplayName("nunca comprado: null, aunque haya compras de otros productos")
        void notBought_isNull() {
            List<FollowedProductResponse> items = listWith(
                    List.of(followed(EAN, DESC)),
                    List.of(new BarcodeLastPurchase("7791234567898", later)));

            assertNull(items.get(0).getLastPurchasedAt());
        }

        @Test
        @DisplayName("los ítems sin código de barras no cuentan como compra")
        void barcodeLess_isIgnored() {
            List<FollowedProductResponse> items = listWith(
                    List.of(followed(EAN, DESC)),
                    List.of(new BarcodeLastPurchase(null, later), new BarcodeLastPurchase("  ", later)));

            assertNull(items.get(0).getLastPurchasedAt());
        }

        @Test
        @DisplayName("una sola consulta de compras para toda la lista, y solo de tickets procesados")
        void oneQueryForTheWholeList() {
            listWith(List.of(followed(EAN, DESC), followed("7791234567898", "Yerba")), List.of());

            verify(ticketRepository, times(1)).findLastPurchaseByBarcode(7L, TicketStatus.PROCESSED);
            verifyNoMoreInteractions(ticketRepository);
        }
    }

    @Nested
    @DisplayName("seguir y dejar de seguir")
    class FollowUnfollow {

        private final FollowProductRequest request = new FollowProductRequest(EAN, " Gaseosa Coca Cola 2.25 L ", "Coca Cola");

        private void currentlyFollows(FollowedProduct... products) {
            when(repository.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of(products));
        }

        /** {@code count} productos distintos, ninguno el del pedido. */
        private List<FollowedProduct> distinct(int count) {
            List<FollowedProduct> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(followed(String.format("77900000%05d", i), "Producto " + i));
            }
            return out;
        }

        @Test
        @DisplayName("seguir guarda el producto, todavía sin promo conocida")
        void follow_saves() {
            currentlyFollows();

            service.follow(user, request);

            ArgumentCaptor<FollowedProduct> saved = ArgumentCaptor.forClass(FollowedProduct.class);
            verify(repository).save(saved.capture());
            assertEquals(EAN, saved.getValue().getEan());
            assertEquals("Gaseosa Coca Cola 2.25 L", saved.getValue().getDescription());
            assertEquals("Coca Cola", saved.getValue().getBrand());
            assertFalse(saved.getValue().isInPromo());
            assertNull(saved.getValue().getLastNotifiedAt());
        }

        @Test
        @DisplayName("seguir uno que ya se sigue no hace nada")
        void follow_twice_isNoOp() {
            currentlyFollows(followed(EAN, "Gaseosa"));

            service.follow(user, request);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("seguir el mismo producto con el código en otro formato no hace nada")
        void follow_sameProductOtherFormat_isNoOp() {
            currentlyFollows(followed(EAN, "Gaseosa"));

            service.follow(user, new FollowProductRequest(EAN_14, "Gaseosa", null));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("el código se guarda tal como vino, sin normalizar")
        void follow_storesTheEanAsGiven() {
            currentlyFollows();

            service.follow(user, new FollowProductRequest(EAN_14, "Gaseosa", null));

            ArgumentCaptor<FollowedProduct> saved = ArgumentCaptor.forClass(FollowedProduct.class);
            verify(repository).save(saved.capture());
            assertEquals(EAN_14, saved.getValue().getEan());
        }

        @Test
        @DisplayName("dos toques a la vez: el que choca con la clave única no es un error")
        void follow_race_isSwallowed() {
            currentlyFollows();
            when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

            assertDoesNotThrow(() -> service.follow(user, request));
        }

        @Test
        @DisplayName("al llegar al tope, el siguiente se rechaza con un mensaje para el usuario")
        void follow_atLimit_isRejected() {
            currentlyFollows(distinct(FollowedProductService.MAX_FOLLOWED).toArray(FollowedProduct[]::new));

            assertThrows(IllegalArgumentException.class, () -> service.follow(user, request));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("en el tope, volver a seguir uno ya seguido (en otro formato) sigue siendo un no-op, no un error")
        void follow_atLimit_alreadyFollowed_isNoOp() {
            List<FollowedProduct> full = distinct(FollowedProductService.MAX_FOLLOWED - 1);
            full.add(followed(EAN, "Gaseosa"));
            currentlyFollows(full.toArray(FollowedProduct[]::new));

            assertDoesNotThrow(() -> service.follow(user, new FollowProductRequest(EAN_14, "Gaseosa", null)));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("dos filas del mismo producto en formatos distintos ocupan un solo lugar del tope")
        void follow_duplicatesCountOnceTowardTheLimit() {
            List<FollowedProduct> rows = distinct(FollowedProductService.MAX_FOLLOWED - 2);
            rows.add(followed("7791234567898", "Yerba"));
            rows.add(followed("07791234567898", "Yerba"));
            currentlyFollows(rows.toArray(FollowedProduct[]::new));

            service.follow(user, request);

            verify(repository).save(any());
        }

        @Test
        @DisplayName("dejar de seguir uno que no se seguía no es un error")
        void unfollow_unknown_isNoOp() {
            currentlyFollows(followed("7791234567898", "Yerba"));

            assertDoesNotThrow(() -> service.unfollow(user, EAN));
            verify(repository, never()).deleteAll(any());
            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("dejar de seguir con el código en otro formato borra el producto seguido, y solo ese")
        void unfollow_otherFormat_deletesThatProduct() {
            FollowedProduct gaseosa = followed(EAN, "Gaseosa");
            currentlyFollows(followed("7791234567898", "Yerba"), gaseosa);

            service.unfollow(user, EAN_14);

            verify(repository).deleteAll(List.of(gaseosa));
        }
    }
}
