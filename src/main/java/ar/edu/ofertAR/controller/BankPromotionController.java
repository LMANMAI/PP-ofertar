package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.BankPromotionsTodayResponse;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.bank.BankPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Calendario de promos bancarias (OFT-52): las promos que escribe el scraper,
 * cruzadas con "Mis tarjetas".
 */
@RestController
@RequestMapping("/bank-promotions")
@RequiredArgsConstructor
@Tag(name = "Promos bancarias", description = "Calendario semanal y tira de Home")
public class BankPromotionController {

    private static final String PROVINCE_DOC =
            "Zona del usuario: una o varias provincias separadas por coma, por nombre (\"Córdoba\"), "
                    + "forma canónica (\"CORDOBA\") o código ISO 3166-2 (\"AR-X\" o \"X\"); \"AMBA\" o \"GBA\" "
                    + "es CABA + Buenos Aires. Sin zona, las promos regionales nunca dan match. "
                    + "Una provincia que no se reconoce es 400.";

    private final BankPromotionService bankPromotionService;

    @Operation(summary = "Siete días de promos bancarias desde from",
            description = "Con mine=true no vienen las promos que no le sirven al usuario (match=none).")
    @GetMapping("/week")
    public ResponseEntity<BankPromotionsWeekResponse> week(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Primer día (YYYY-MM-DD). Por defecto, hoy en Argentina")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "true: sólo las que le sirven al usuario (match, all, near)")
            @RequestParam(defaultValue = "true") boolean mine,
            @Parameter(description = PROVINCE_DOC)
            @RequestParam(required = false) String province
    ) {
        return ResponseEntity.ok(bankPromotionService.week(user, from, mine, province));
    }

    @Operation(summary = "Las promos de hoy, para la tira de Home (máximo 3)")
    @GetMapping("/today")
    public ResponseEntity<BankPromotionsTodayResponse> today(
            @AuthenticationPrincipal User user,
            @Parameter(description = "true: sólo las que le sirven al usuario (match, all, near)")
            @RequestParam(defaultValue = "true") boolean mine,
            @Parameter(description = PROVINCE_DOC)
            @RequestParam(required = false) String province
    ) {
        return ResponseEntity.ok(bankPromotionService.today(user, mine, province));
    }
}
