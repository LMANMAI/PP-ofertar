package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.request.RedeemRequest;
import ar.edu.ofertAR.dto.response.PointsBalanceResponse;
import ar.edu.ofertAR.dto.response.PointsHistoryEntryResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.PointsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;

    @GetMapping("/me")
    public ResponseEntity<PointsBalanceResponse> getBalance(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pointsService.getBalance(user));
    }

    @GetMapping("/history")
    public ResponseEntity<List<PointsHistoryEntryResponse>> getHistory(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pointsService.getHistory(user));
    }

    @PostMapping("/redeem")
    public ResponseEntity<PointsBalanceResponse> redeem(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RedeemRequest request
    ) {
        return ResponseEntity.ok(pointsService.redeem(user, request));
    }
}
