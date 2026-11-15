package ar.edu.ofertAR.config;

import ar.edu.ofertAR.controller.TelemetryController;
import ar.edu.ofertAR.repository.MetricasRepository;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.metricas.TelemetriaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /telemetry/** es público a propósito: la app reporta antes del login y el tablero no
 * tiene sesión. Este test deja asentada esa decisión (y que el CORS propio funciona).
 */
@WebMvcTest(TelemetryController.class)
@Import(SecurityConfig.class)
class TelemetrySecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean TelemetriaService telemetria;
    @MockitoBean MetricasRepository repo;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    @DisplayName("POST /telemetry/events sin token es 202")
    void eventosAnonimos() throws Exception {
        mvc.perform(post("/telemetry/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installId\":\"install-0001\",\"platform\":\"android\","
                                + "\"events\":[{\"type\":\"screen_view\",\"name\":\"Home\",\"campoNuevo\":1}]}"))
                .andExpect(status().isAccepted());
        verify(telemetria).registrar(any());
    }

    @Test
    @DisplayName("un lote sin eventos es 400")
    void loteSinEventos() throws Exception {
        mvc.perform(post("/telemetry/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /telemetry/status sin token es 200")
    void estadoAnonimo() throws Exception {
        mvc.perform(get("/telemetry/status")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /telemetry/series valida los nombres de métrica")
    void seriesValidaNombres() throws Exception {
        mvc.perform(get("/telemetry/series").param("name", "http.server.requests"))
                .andExpect(status().isOk());
        mvc.perform(get("/telemetry/series").param("name", "x'; DROP TABLE users; --"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el tablero puede consultar desde otro origen")
    void corsDelTablero() throws Exception {
        mvc.perform(options("/telemetry/series")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
