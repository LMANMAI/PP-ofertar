package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.config.SecurityConfig;
import ar.edu.ofertAR.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReferralLinkController.class)
@Import(SecurityConfig.class)
class ReferralLinkControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    @DisplayName("sin sesión muestra el código y abre la app con ese código")
    void paginaPublica() throws Exception {
        mvc.perform(get("/r/ab12cd34"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">AB12CD34<")))
                .andExpect(content().string(containsString("ofertar://referido/AB12CD34")))
                .andExpect(content().string(not(containsString("Bajar la app"))));
    }

    @Test
    @DisplayName("un código con otros caracteres es 404 y no llega a la página")
    void codigoInvalido() throws Exception {
        mvc.perform(get("/r/<script>")).andExpect(status().isNotFound());
        mvc.perform(get("/r/a")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el botón de descarga sale sólo con una URL https configurada")
    void descarga() {
        assertTrue(new ReferralLinkController("https://example.com/app.apk").page("AB12CD34").contains("Bajar la app"));
        assertFalse(new ReferralLinkController("javascript:alert(1)").page("AB12CD34").contains("Bajar la app"));
    }
}
