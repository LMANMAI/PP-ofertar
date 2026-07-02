package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.SavingsReportResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.SavingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

@RestController
@RequestMapping("/savings")
@RequiredArgsConstructor
public class SavingsController {

    private final SavingsService savingsService;

    @GetMapping("/report")
    public ResponseEntity<SavingsReportResponse> getReport(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        YearMonth fromYm = from != null ? YearMonth.parse(from) : null;
        YearMonth toYm = to != null ? YearMonth.parse(to) : null;
        return ResponseEntity.ok(savingsService.getReport(user, fromYm, toYm));
    }
}
