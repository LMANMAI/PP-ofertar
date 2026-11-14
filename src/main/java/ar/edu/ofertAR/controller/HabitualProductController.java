package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.AddHabitualProductRequest;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.HabitualProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sumar y sacar productos de la compra habitual desde el escáner. La compra
 * habitual completa se lee de {@code GET /products/recurring}.
 */
@RestController
@RequestMapping("/products/habitual")
@RequiredArgsConstructor
public class HabitualProductController {

    private final HabitualProductService habitualProductService;

    /** Solo los códigos: lo que necesita el escáner para saber si el producto ya está. */
    @GetMapping("/eans")
    public ResponseEntity<List<String>> listHabitualProductEans(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(habitualProductService.listEans(user));
    }

    @PostMapping
    public ResponseEntity<Void> addHabitualProduct(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddHabitualProductRequest request
    ) {
        habitualProductService.add(user, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{ean}")
    public ResponseEntity<Void> removeHabitualProduct(@AuthenticationPrincipal User user, @PathVariable String ean) {
        habitualProductService.remove(user, ean);
        return ResponseEntity.noContent().build();
    }
}
