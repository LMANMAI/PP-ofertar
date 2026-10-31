package ar.edu.ofertAR.config;

import ar.edu.ofertAR.controller.SepaController;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.SepaCatalogoService;
import ar.edu.ofertAR.service.SepaService;
import ar.edu.ofertAR.service.SepaSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién puede tocar los endpoints caros de SEPA. /sepa/sync hace DROP/RENAME
 * sobre las tablas de producción y /sepa/precios parsea el dataset completo
 * dentro del request: ninguno puede quedar al alcance de un usuario común.
 */
@WebMvcTest(SepaController.class)
@Import(SecurityConfig.class)
class SepaSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean SepaService sepaService;
    @MockitoBean SepaSnapshotService sepaSnapshotService;
    @MockitoBean SepaCatalogoService catalogo;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    @DisplayName("POST /sepa/sync sin token es 401")
    void syncAnonimo() throws Exception {
        mvc.perform(post("/sepa/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /sepa/sync con rol USER es 403")
    void syncComoUsuario() throws Exception {
        mvc.perform(post("/sepa/sync")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /sepa/sync con rol ADMIN es 202")
    void syncComoAdmin() throws Exception {
        mvc.perform(post("/sepa/sync")).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("GET /sepa/precios sin token es 401")
    void preciosAnonimo() throws Exception {
        mvc.perform(get("/sepa/precios")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /sepa/precios con rol USER es 403")
    void preciosComoUsuario() throws Exception {
        mvc.perform(get("/sepa/precios")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /sepa/sync/estado con rol USER es 403")
    void estadoComoUsuario() throws Exception {
        mvc.perform(get("/sepa/sync/estado")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /sepa/productos sigue siendo público")
    void productosPublico() throws Exception {
        int status = mvc.perform(get("/sepa/productos")).andReturn().getResponse().getStatus();
        assertNotEquals(401, status);
        assertNotEquals(403, status);
    }
}
