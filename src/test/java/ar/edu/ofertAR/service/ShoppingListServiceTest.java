package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.AddShoppingListItemRequest;
import ar.edu.ofertAR.dto.request.UpdateShoppingListItemRequest;
import ar.edu.ofertAR.dto.response.ShoppingListResponse;
import ar.edu.ofertAR.model.ShoppingList;
import ar.edu.ofertAR.model.ShoppingListItem;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.ShoppingListItemRepository;
import ar.edu.ofertAR.repository.ShoppingListRepository;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShoppingListServiceTest {

    private static final String EAN = "7790895000997";

    @Mock private ShoppingListRepository listRepository;
    @Mock private ShoppingListItemRepository itemRepository;
    @Mock private ProductoImagenService productoImagenService;

    private ShoppingListService service;
    private User user;
    private ShoppingList list;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(listRepository, itemRepository, productoImagenService);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        list = ShoppingList.builder().id(3L).user(user).name("Mi lista").build();
        lenient().when(productoImagenService.imagenesPorEan(any())).thenReturn(Map.of());
    }

    private void userHasList() {
        when(listRepository.findFirstByUserIdOrderByIdAsc(7L)).thenReturn(Optional.of(list));
    }

    private ShoppingListItem item(Long id, String ean, boolean checked) {
        return ShoppingListItem.builder().id(id).list(list).ean(ean).description("Gaseosa").checked(checked).build();
    }

    @Test
    @DisplayName("leer la lista de quien nunca la usó no crea nada")
    void get_withoutList_isEmptyAndWritesNothing() {
        when(listRepository.findFirstByUserIdOrderByIdAsc(7L)).thenReturn(Optional.empty());

        ShoppingListResponse response = service.get(user);

        assertEquals("Mi lista", response.getName());
        assertTrue(response.getItems().isEmpty());
        verify(listRepository, never()).save(any());
    }

    @Test
    @DisplayName("el primer producto crea la lista y entra con cantidad 1")
    void addItem_firstProduct_createsListAndItem() {
        when(listRepository.findFirstByUserIdOrderByIdAsc(7L)).thenReturn(Optional.empty());
        when(listRepository.save(any())).thenReturn(list);
        when(itemRepository.findByListIdAndEan(3L, EAN)).thenReturn(Optional.empty());

        service.addItem(user, new AddShoppingListItemRequest(EAN, "  Gaseosa Coca Cola 2.25 L ", " ", null));

        ArgumentCaptor<ShoppingListItem> saved = ArgumentCaptor.forClass(ShoppingListItem.class);
        verify(itemRepository).save(saved.capture());
        assertEquals(EAN, saved.getValue().getEan());
        assertEquals("Gaseosa Coca Cola 2.25 L", saved.getValue().getDescription());
        assertNull(saved.getValue().getBrand(), "una marca en blanco se guarda como ausente");
        assertEquals(1, saved.getValue().getQuantity());
        assertFalse(saved.getValue().isChecked());
        assertSame(list, saved.getValue().getList());
    }

    @Test
    @DisplayName("agregar uno que ya está no lo duplica: vuelve sin tildar")
    void addItem_alreadyThere_unchecksInsteadOfDuplicating() {
        userHasList();
        ShoppingListItem existing = item(10L, EAN, true);
        existing.setQuantity(4);
        when(itemRepository.findByListIdAndEan(3L, EAN)).thenReturn(Optional.of(existing));

        service.addItem(user, new AddShoppingListItemRequest(EAN, "Gaseosa", null, null));

        verify(itemRepository, times(1)).save(existing);
        assertFalse(existing.isChecked());
        assertEquals(4, existing.getQuantity(), "sin cantidad en el pedido, la que tenía no se toca");
        verify(itemRepository, never()).countByListId(anyLong());
    }

    @Test
    @DisplayName("con la lista llena, el producto nuevo se rechaza con un mensaje para el usuario")
    void addItem_fullList_isRejected() {
        userHasList();
        when(itemRepository.findByListIdAndEan(3L, EAN)).thenReturn(Optional.empty());
        when(itemRepository.countByListId(3L)).thenReturn((long) ShoppingListService.MAX_ITEMS);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addItem(user, new AddShoppingListItemRequest(EAN, "Gaseosa", null, null)));

        assertTrue(error.getMessage().contains("100"));
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizar cambia solo lo que viene en el pedido")
    void updateItem_appliesOnlyWhatWasSent() {
        userHasList();
        ShoppingListItem existing = item(10L, EAN, false);
        existing.setQuantity(2);
        when(itemRepository.findByIdAndListId(10L, 3L)).thenReturn(Optional.of(existing));

        service.updateItem(user, 10L, new UpdateShoppingListItemRequest(null, true));

        assertTrue(existing.isChecked());
        assertEquals(2, existing.getQuantity());
        verify(itemRepository).save(existing);
    }

    @Test
    @DisplayName("el ítem de otra lista es un 404, igual que uno que no existe")
    void updateItem_fromAnotherList_isNotFound() {
        userHasList();
        when(itemRepository.findByIdAndListId(99L, 3L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateItem(user, 99L, new UpdateShoppingListItemRequest(5, null)));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("sacar un producto sin tener lista es un 404, no un error del servidor")
    void removeItem_withoutList_isNotFound() {
        when(listRepository.findFirstByUserIdOrderByIdAsc(7L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.removeItem(user, 10L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    @DisplayName("la respuesta trae la imagen resuelta por EAN y el orden en que se agregaron")
    void response_carriesImagesByNormalizedEan() {
        userHasList();
        // Un EAN-8: la imagen se guarda bajo el EAN completado a 13 dígitos.
        ShoppingListItem corto = item(11L, "12345670", false);
        ShoppingListItem largo = item(12L, EAN, true);
        when(itemRepository.findByListIdOrderByCreatedAtAscIdAsc(3L)).thenReturn(List.of(corto, largo));
        when(productoImagenService.imagenesPorEan(List.of("12345670", EAN)))
                .thenReturn(Map.of("0000012345670", "https://img/corto.jpg"));

        ShoppingListResponse response = service.get(user);

        assertEquals(List.of(11L, 12L), response.getItems().stream().map(i -> i.getId()).toList());
        assertEquals("https://img/corto.jpg", response.getItems().get(0).getImageUrl());
        assertNull(response.getItems().get(1).getImageUrl());
        assertTrue(response.getItems().get(1).isChecked());
    }

    @Test
    @DisplayName("vaciar los tildados borra solo esos")
    void clearChecked_deletesOnlyChecked() {
        userHasList();

        service.clearChecked(user);

        verify(itemRepository).deleteByListIdAndCheckedTrue(3L);
        verify(itemRepository, never()).delete(any());
    }
}
