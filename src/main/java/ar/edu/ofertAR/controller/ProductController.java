package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/recurring")
    public ResponseEntity<List<RecurringProductResponse>> getRecurringProducts(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(productService.getRecurringProducts(user));
    }
}
