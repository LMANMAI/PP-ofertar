package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.config.SecurityConfig;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.SavingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.YearMonth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SavingsController.class)
@Import(SecurityConfig.class)
class SavingsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SavingsService savingsService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    private static User usuario() {
        return User.builder().id(1L).name("Test").email("test@test.com").password("x").build();
    }

    @Test
    @DisplayName("un método equivocado sobre una ruta que existe es 405, no 500")
    void metodoNoPermitido() throws Exception {
        mvc.perform(post("/savings/report").with(user(usuario())))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("un mes mal escrito es 400, no 500")
    void mesInvalido() throws Exception {
        mvc.perform(get("/savings/report").param("from", "2026-13").with(user(usuario())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/savings/report").param("to", "ayer").with(user(usuario())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un mes válido llega parseado al servicio y sin meses es el reporte completo")
    void mesValido() throws Exception {
        mvc.perform(get("/savings/report").param("from", "2026-10").with(user(usuario())))
                .andExpect(status().isOk());
        verify(savingsService).getReport(any(), eq(YearMonth.of(2026, 10)), eq(null));
    }
}
