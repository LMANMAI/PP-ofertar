package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.AddShoppingListItemRequest;
import ar.edu.ofertAR.dto.request.UpdateShoppingListItemRequest;
import ar.edu.ofertAR.dto.response.ShoppingListItemResponse;
import ar.edu.ofertAR.dto.response.ShoppingListResponse;
import ar.edu.ofertAR.model.ShoppingList;
import ar.edu.ofertAR.model.ShoppingListItem;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.ShoppingListItemRepository;
import ar.edu.ofertAR.repository.ShoppingListRepository;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The user's shopping list: products added from a barcode scan.
 *
 * One list per user for now. It is created with the first product rather than
 * with the account, so reading the list of someone who never used it writes
 * nothing.
 */
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    static final String DEFAULT_NAME = "Mi lista";
    /** A list is something to walk a supermarket with, not a catalogue. */
    static final int MAX_ITEMS = 100;

    private final ShoppingListRepository listRepository;
    private final ShoppingListItemRepository itemRepository;
    private final ProductoImagenService productoImagenService;

    @Transactional(readOnly = true)
    public ShoppingListResponse get(User user) {
        return listRepository.findFirstByUserIdOrderByIdAsc(user.getId())
                .map(this::toResponse)
                .orElseGet(() -> ShoppingListResponse.builder().name(DEFAULT_NAME).build());
    }

    /**
     * Adds a product. Adding one that is already there is not an error and
     * does not duplicate it: it comes back unchecked, since scanning it again
     * means the user wants it on the next trip.
     */
    @Transactional
    public ShoppingListResponse addItem(User user, AddShoppingListItemRequest request) {
        ShoppingList list = listRepository.findFirstByUserIdOrderByIdAsc(user.getId())
                .orElseGet(() -> listRepository.save(ShoppingList.builder().user(user).name(DEFAULT_NAME).build()));

        String ean = request.getEan().trim();
        Optional<ShoppingListItem> existing = itemRepository.findByListIdAndEan(list.getId(), ean);
        if (existing.isPresent()) {
            ShoppingListItem item = existing.get();
            item.setChecked(false);
            if (request.getQuantity() != null) item.setQuantity(request.getQuantity());
            itemRepository.save(item);
            return toResponse(list);
        }

        if (itemRepository.countByListId(list.getId()) >= MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "Tu lista ya tiene " + MAX_ITEMS + " productos. Sacá alguno para agregar otro.");
        }
        itemRepository.save(ShoppingListItem.builder()
                .list(list)
                .ean(ean)
                .description(request.getDescription().trim())
                .brand(blankToNull(request.getBrand()))
                .quantity(request.getQuantity() != null ? request.getQuantity() : 1)
                .build());
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse updateItem(User user, Long itemId, UpdateShoppingListItemRequest request) {
        ShoppingList list = requireList(user);
        ShoppingListItem item = requireItem(list, itemId);
        if (request.getQuantity() != null) item.setQuantity(request.getQuantity());
        if (request.getChecked() != null) item.setChecked(request.getChecked());
        itemRepository.save(item);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse removeItem(User user, Long itemId) {
        ShoppingList list = requireList(user);
        itemRepository.delete(requireItem(list, itemId));
        // Out before the list is read back: the response is built in this
        // same transaction and must not include the row just removed.
        itemRepository.flush();
        return toResponse(list);
    }

    /** Removes what is already in the cart, to start the next trip clean. */
    @Transactional
    public ShoppingListResponse clearChecked(User user) {
        Optional<ShoppingList> list = listRepository.findFirstByUserIdOrderByIdAsc(user.getId());
        if (list.isEmpty()) return get(user);
        itemRepository.deleteByListIdAndCheckedTrue(list.get().getId());
        itemRepository.flush();
        return toResponse(list.get());
    }

    private ShoppingList requireList(User user) {
        return listRepository.findFirstByUserIdOrderByIdAsc(user.getId())
                .orElseThrow(ShoppingListService::itemNotFound);
    }

    /** Looked up through the user's own list, so another user's item id is a
     * 404 like any id that does not exist. */
    private ShoppingListItem requireItem(ShoppingList list, Long itemId) {
        return itemRepository.findByIdAndListId(itemId, list.getId())
                .orElseThrow(ShoppingListService::itemNotFound);
    }

    private static ResponseStatusException itemNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese producto ya no está en tu lista");
    }

    private ShoppingListResponse toResponse(ShoppingList list) {
        List<ShoppingListItem> items = itemRepository.findByListIdOrderByCreatedAtAscIdAsc(list.getId());
        Map<String, String> images = productoImagenService.imagenesPorEan(
                items.stream().map(ShoppingListItem::getEan).toList());
        return ShoppingListResponse.builder()
                .name(list.getName())
                .items(items.stream()
                        .map(item -> ShoppingListItemResponse.builder()
                                .id(item.getId())
                                .ean(item.getEan())
                                .description(item.getDescription())
                                .brand(item.getBrand())
                                .imageUrl(images.get(ProductoImagenService.normalizarEan(item.getEan())))
                                .quantity(item.getQuantity())
                                .checked(item.isChecked())
                                .addedAt(item.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
