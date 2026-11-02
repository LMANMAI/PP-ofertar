package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.UpdatePaymentMethodsRequest;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse;
import ar.edu.ofertAR.dto.response.PaymentCatalogResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.bank.PaymentMethodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * "Mis tarjetas" (OFT-51). Sólo se guardan emisor, red y tipo: nunca el número
 * de la tarjeta, el vencimiento ni el CVV.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Mis tarjetas", description = "Medios de pago y beneficios declarados, sin números de tarjeta")
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @Operation(summary = "Catálogo: emisores (del scraper), redes, tipos y beneficios")
    @GetMapping("/payment-methods/catalog")
    public ResponseEntity<PaymentCatalogResponse> catalog() {
        return ResponseEntity.ok(paymentMethodService.catalog());
    }

    @Operation(summary = "Los medios de pago y beneficios del usuario")
    @GetMapping("/me/payment-methods")
    public ResponseEntity<MyPaymentMethodsResponse> get(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(paymentMethodService.get(user));
    }

    @Operation(summary = "Reemplaza el conjunto completo de medios y beneficios",
            description = "Valida cada slug contra el catálogo: uno desconocido es 400 y no se guarda nada.")
    @PutMapping("/me/payment-methods")
    public ResponseEntity<MyPaymentMethodsResponse> replace(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdatePaymentMethodsRequest request
    ) {
        return ResponseEntity.ok(paymentMethodService.replace(user, request));
    }

    @Operation(summary = "Quita un medio de pago",
            description = "204; 404 si no existe o es de otro usuario.")
    @DeleteMapping("/me/payment-methods/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        paymentMethodService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
