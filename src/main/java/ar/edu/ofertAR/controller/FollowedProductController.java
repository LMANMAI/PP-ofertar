package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.FollowProductRequest;
import ar.edu.ofertAR.dto.response.FollowedProductsResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.FollowedProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Products followed from a barcode scan, without having bought them. The daily
 * job in FollowedProductAlertService sends the push when one goes on promotion.
 */
@RestController
@RequestMapping("/products/followed")
@RequiredArgsConstructor
public class FollowedProductController {

    private final FollowedProductService followedProductService;

    /** The followed products, each with the promotions it has right now. */
    @GetMapping
    public ResponseEntity<FollowedProductsResponse> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(followedProductService.list(user));
    }

    /**
     * Only the barcodes, without asking the offers service for anything: what
     * the scanner needs to draw "Siguiendo" on a product it just read.
     */
    @GetMapping("/eans")
    public ResponseEntity<List<String>> listEans(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(followedProductService.listEans(user));
    }

    @PostMapping
    public ResponseEntity<Void> follow(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody FollowProductRequest request
    ) {
        followedProductService.follow(user, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{ean}")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal User user, @PathVariable String ean) {
        followedProductService.unfollow(user, ean);
        return ResponseEntity.noContent().build();
    }
}
