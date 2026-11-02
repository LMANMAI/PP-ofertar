package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.config.SecurityConfig;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse.PaymentMethodView;
import ar.edu.ofertAR.dto.response.PaymentIssuerView;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.security.JwtService;
import ar.edu.ofertAR.service.bank.PaymentMethodService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Mis tarjetas": autenticación, validación del body y forma del JSON (§4 del SPEC). */
@WebMvcTest(PaymentMethodController.class)
@Import(SecurityConfig.class)
class PaymentMethodControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean PaymentMethodService service;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserDetailsService userDetailsService;

    private static final User ALEX = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();

    @Test
    @DisplayName("sin sesión es 401")
    void sinSesion() throws Exception {
        mvc.perform(get("/me/payment-methods")).andExpect(status().isUnauthorized());
        mvc.perform(get("/payment-methods/catalog")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET devuelve network y cardType en null (presentes) y los beneficios como slugs")
    void formaDelGet() throws Exception {
        when(service.get(any())).thenReturn(new MyPaymentMethodsResponse(List.of(
                new PaymentMethodView(3L, new PaymentIssuerView("mercado-pago", "Mercado Pago", "wallet"), null, null)),
                List.of("jubilado")));
        mvc.perform(get("/me/payment-methods").with(user(ALEX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methods[0].id").value(3))
                .andExpect(jsonPath("$.methods[0].issuer.slug").value("mercado-pago"))
                .andExpect(jsonPath("$.methods[0].issuer.kind").value("wallet"))
                .andExpect(jsonPath("$.methods[0].network").isEmpty())
                .andExpect(jsonPath("$.methods[0].cardType").isEmpty())
                .andExpect(jsonPath("$.benefits[0]").value("jubilado"));
    }

    @Test
    @DisplayName("PUT sin methods es 400 y no llega al servicio")
    void putSinMethods() throws Exception {
        mvc.perform(put("/me/payment-methods").with(user(ALEX))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"benefits\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.methods").value("Falta la lista de medios de pago"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT con un medio sin emisor es 400")
    void putSinEmisor() throws Exception {
        mvc.perform(put("/me/payment-methods").with(user(ALEX))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methods\":[{\"issuerSlug\":\"  \",\"network\":\"visa\"}],\"benefits\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['methods[0].issuerSlug']").value("Falta el emisor"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT con un slug fuera del catálogo: el 400 del servicio sale con su mensaje")
    void putSlugInvalido() throws Exception {
        when(service.replace(any(), any())).thenThrow(new IllegalArgumentException("La red 'unionpay' no está en el catálogo"));
        mvc.perform(put("/me/payment-methods").with(user(ALEX))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methods\":[{\"issuerSlug\":\"banco-galicia\",\"network\":\"unionpay\"}],\"benefits\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La red 'unionpay' no está en el catálogo"));
    }

    @Test
    @DisplayName("un número de tarjeta en el body se ignora: no llega al servicio")
    void ignoraNumeroDeTarjeta() throws Exception {
        when(service.replace(any(), any())).thenReturn(new MyPaymentMethodsResponse(List.of(), List.of()));
        mvc.perform(put("/me/payment-methods").with(user(ALEX))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methods\":[{\"issuerSlug\":\"banco-galicia\",\"number\":\"4111111111111111\",\"cvv\":\"123\"}],\"benefits\":[]}"))
                .andExpect(status().isOk());
        verify(service).replace(eq(ALEX), argThat(r -> r.getMethods().get(0).toString().equals(
                "UpdatePaymentMethodsRequest.PaymentMethodInput(issuerSlug=banco-galicia, network=null, cardType=null)")));
    }

    @Test
    @DisplayName("DELETE propio es 204; ajeno o inexistente, 404")
    void borrar() throws Exception {
        mvc.perform(delete("/me/payment-methods/5").with(user(ALEX))).andExpect(status().isNoContent());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontramos ese medio de pago"))
                .when(service).delete(any(), eq(99L));
        mvc.perform(delete("/me/payment-methods/99").with(user(ALEX)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No encontramos ese medio de pago"));
    }
}
