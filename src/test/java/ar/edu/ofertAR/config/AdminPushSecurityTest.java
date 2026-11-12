package ar.edu.ofertAR.config;

import ar.edu.ofertAR.controller.AdminPushController;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.UserRepository;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.FollowedProductAlertService;
import ar.edu.ofertAR.service.OfferAlertService;
import ar.edu.ofertAR.service.PushJobOutcome;
import ar.edu.ofertAR.service.PushNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Correr un job de notificaciones a mano le manda un push real a un usuario
 * cualquiera: sólo ADMIN.
 */
@WebMvcTest(AdminPushController.class)
@Import(SecurityConfig.class)
class AdminPushSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserRepository userRepository;
    @MockitoBean FollowedProductAlertService followedProductAlertService;
    @MockitoBean OfferAlertService offerAlertService;
    @MockitoBean PushNotificationService pushNotificationService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    @DisplayName("sin token es 401")
    void anonimo() throws Exception {
        mvc.perform(post("/admin/push/jobs/followed").param("userId", "1")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("con rol USER es 403 y no corre nada")
    void comoUsuario() throws Exception {
        mvc.perform(post("/admin/push/jobs/followed").param("userId", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/push/jobs/offers").param("userId", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/push/jobs/reactivation").param("userId", "1")).andExpect(status().isForbidden());
        verifyNoInteractions(userRepository, followedProductAlertService, offerAlertService, pushNotificationService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("con rol ADMIN corre el job para ese usuario y dice por qué no mandó nada")
    void comoAdmin() throws Exception {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(followedProductAlertService.runForUser(1L))
                .thenReturn(PushJobOutcome.skipped("El usuario no sigue ningún producto."));
        when(pushNotificationService.devicesOf(1L)).thenReturn(List.of());

        mvc.perform(post("/admin/push/jobs/followed").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").value("followed"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.reason").value("El usuario no sigue ningún producto."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("si el job manda pero no hay dispositivos, la razón lo aclara")
    void enviadoSinDispositivos() throws Exception {
        User u = User.builder().id(1L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(offerAlertService.runForUser(u)).thenReturn(PushJobOutcome.sent("Se avisó de una oferta nueva en Coto."));
        when(pushNotificationService.devicesOf(1L)).thenReturn(List.of());

        mvc.perform(post("/admin/push/jobs/offers").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.reason").value(containsString("no tiene dispositivos")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("userId es obligatorio: 400")
    void sinUserId() throws Exception {
        mvc.perform(post("/admin/push/jobs/followed")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("job desconocido: 400")
    void jobInvalido() throws Exception {
        mvc.perform(post("/admin/push/jobs/ticket").param("userId", "1")).andExpect(status().isBadRequest());
        verifyNoInteractions(userRepository);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("usuario inexistente: 404")
    void usuarioInexistente() throws Exception {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/push/jobs/reactivation").param("userId", "99")).andExpect(status().isNotFound());
        verify(pushNotificationService, never()).runReactivationForUser(any());
    }
}
