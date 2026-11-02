package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.config.SecurityConfig;
import ar.edu.ofertAR.dto.response.BankPromotionsTodayResponse;
import ar.edu.ofertAR.dto.response.PromoView;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.bank.BankPromotionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Calendario de promos bancarias: parámetros y forma del JSON (§4 del SPEC). */
@WebMvcTest(BankPromotionController.class)
@Import(SecurityConfig.class)
class BankPromotionControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean BankPromotionService service;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    private static final User ALEX = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();

    @Test
    @DisplayName("sin sesión es 401")
    void sinSesion() throws Exception {
        mvc.perform(get("/bank-promotions/week")).andExpect(status().isUnauthorized());
        mvc.perform(get("/bank-promotions/today")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("from con fecha inválida es 400")
    void fromInvalido() throws Exception {
        mvc.perform(get("/bank-promotions/week").param("from", "2026-13-01").with(user(ALEX)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("sin parámetros: from null (hoy), mine=true y sin provincia")
    void defaults() throws Exception {
        mvc.perform(get("/bank-promotions/week").with(user(ALEX))).andExpect(status().isOk());
        verify(service).week(any(), isNull(), eq(true), isNull());
        mvc.perform(get("/bank-promotions/week").param("from", "2026-11-10").param("mine", "false")
                .param("province", "AR-X").with(user(ALEX))).andExpect(status().isOk());
        verify(service).week(any(), eq(LocalDate.of(2026, 11, 10)), eq(false), eq("AR-X"));
    }

    @Test
    @DisplayName("PromoView: los opcionales se omiten y los nulables salen en null")
    void formaDePromoView() throws Exception {
        PromoView v = new PromoView(1L, "jumbo", "Jumbo", "Patagonia 35%",
                new PromoView.PromoDiscount("percent", new BigDecimal("15"), null, null, "15% a 35%"),
                null, List.of("store"), null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                List.of(new PromoView.PromoIssuer("banco-patagonia", "Banco Patagonia")), false,
                new PromoView.PromoRequires(null, null, "Patagonia Plus"),
                new PromoView.PercentRange(new BigDecimal("15"), new BigDecimal("35")),
                List.of(), null, "near", "Sólo en Río Negro", null, List.of("days_mismatch_legal"));
        when(service.today(any(), anyBoolean(), any()))
                .thenReturn(new BankPromotionsTodayResponse(LocalDate.of(2026, 11, 10), new BigDecimal("35"), List.of(v)));

        mvc.perform(get("/bank-promotions/today").with(user(ALEX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-10"))
                .andExpect(jsonPath("$.bestPercent").value(35))
                .andExpect(jsonPath("$.promos[0].discount.kind").value("percent"))
                .andExpect(jsonPath("$.promos[0].discount.installments").doesNotExist())
                .andExpect(jsonPath("$.promos[0].discount.text").value("15% a 35%"))
                .andExpect(jsonPath("$.promos[0].cap").isEmpty())
                .andExpect(jsonPath("$.promos[0].formats").isEmpty())
                .andExpect(jsonPath("$.promos[0].validFrom").value("2026-11-01"))
                .andExpect(jsonPath("$.promos[0].requires.package").value("Patagonia Plus"))
                .andExpect(jsonPath("$.promos[0].requires.network").doesNotExist())
                .andExpect(jsonPath("$.promos[0].requires.packageName").doesNotExist())
                .andExpect(jsonPath("$.promos[0].percentRange.max").value(35))
                .andExpect(jsonPath("$.promos[0].regions").isEmpty())
                .andExpect(jsonPath("$.promos[0].match").value("near"))
                .andExpect(jsonPath("$.promos[0].matchReason").value("Sólo en Río Negro"))
                .andExpect(jsonPath("$.promos[0].allMethods").value(false))
                .andExpect(jsonPath("$.promos[0].qualityFlags[0]").value("days_mismatch_legal"));
    }
}
