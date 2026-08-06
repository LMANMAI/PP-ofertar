package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.RecurringProductResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * @param ticketId optional; when given, {@code inReferenceTicket} is computed
     *                 against that ticket instead of the user's most recent one.
     *                 Used right after a scan to detect habitual products the
     *                 user may have forgotten to buy.
     */
    @GetMapping("/recurring")
    public ResponseEntity<List<RecurringProductResponse>> getRecurringProducts(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Long ticketId
    ) {
        return ResponseEntity.ok(productService.getRecurringProducts(user, ticketId));
    }
}
