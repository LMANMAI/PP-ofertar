package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.AddShoppingListItemRequest;
import ar.edu.ofertAR.dto.request.UpdateShoppingListItemRequest;
import ar.edu.ofertAR.dto.response.ShoppingListResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.ShoppingListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The user's shopping list. Every change answers with the list as it is left. */
@RestController
@RequestMapping("/shopping-list")
@RequiredArgsConstructor
public class ShoppingListController {

    private final ShoppingListService shoppingListService;

    @GetMapping
    public ResponseEntity<ShoppingListResponse> get(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shoppingListService.get(user));
    }

    /** Adds a scanned product; one that is already on the list comes back unchecked. */
    @PostMapping("/items")
    public ResponseEntity<ShoppingListResponse> addItem(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddShoppingListItemRequest request
    ) {
        return ResponseEntity.ok(shoppingListService.addItem(user, request));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<ShoppingListResponse> updateItem(
            @AuthenticationPrincipal User user,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateShoppingListItemRequest request
    ) {
        return ResponseEntity.ok(shoppingListService.updateItem(user, itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<ShoppingListResponse> removeItem(
            @AuthenticationPrincipal User user,
            @PathVariable Long itemId
    ) {
        return ResponseEntity.ok(shoppingListService.removeItem(user, itemId));
    }

    /** Removes the products already checked off. */
    @DeleteMapping("/checked")
    public ResponseEntity<ShoppingListResponse> clearChecked(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(shoppingListService.clearChecked(user));
    }
}
