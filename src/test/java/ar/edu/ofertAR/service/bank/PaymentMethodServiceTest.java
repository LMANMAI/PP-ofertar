package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.dto.request.UpdatePaymentMethodsRequest;
import ar.edu.ofertAR.dto.request.UpdatePaymentMethodsRequest.PaymentMethodInput;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.model.UserBenefit;
import ar.edu.ofertAR.model.UserPaymentMethod;
import ar.edu.ofertAR.repository.UserBenefitRepository;
import ar.edu.ofertAR.repository.UserPaymentMethodRepository;
import ar.edu.ofertAR.service.bank.PaymentIssuerReader.Issuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    @Mock private UserPaymentMethodRepository methodRepository;
    @Mock private UserBenefitRepository benefitRepository;
    @Mock private PaymentIssuerReader issuerReader;

    private PaymentMethodService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PaymentMethodService(methodRepository, benefitRepository, issuerReader);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(issuerReader.findAll()).thenReturn(List.of(
                new Issuer("banco-galicia", "Banco Galicia", "bank"),
                new Issuer("mercado-pago", "Mercado Pago", "wallet")));
        lenient().when(methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of());
        lenient().when(benefitRepository.findByUserIdOrderByBenefitAsc(7L)).thenReturn(List.of());
    }

    private static UpdatePaymentMethodsRequest request(List<PaymentMethodInput> methods, List<String> benefits) {
        return new UpdatePaymentMethodsRequest(new ArrayList<>(methods), new ArrayList<>(benefits));
    }

    private UserPaymentMethod guardado(long id, String issuer, String network, String cardType) {
        return UserPaymentMethod.builder().id(id).user(user).issuerSlug(issuer).network(network).cardType(cardType).build();
    }

    @Test
    @DisplayName("no se puede borrar el medio de otro usuario: 404 y no se borra nada")
    void noBorraAjeno() {
        when(methodRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.delete(user, 99L));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(methodRepository, never()).delete(any());
        verify(methodRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("borrar uno propio lo busca con el id del usuario y lo borra")
    void borraPropio() {
        UserPaymentMethod m = guardado(5L, "banco-galicia", "visa", "credit");
        when(methodRepository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(m));
        service.delete(user, 5L);
        verify(methodRepository).delete(m);
    }

    @Test
    @DisplayName("un emisor, red, tipo o beneficio fuera del catálogo es 400 y no se guarda nada")
    void validaContraCatalogo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.replace(user, request(
                List.of(new PaymentMethodInput("banco-inventado", "visa", "credit"),
                        new PaymentMethodInput("banco-galicia", "unionpay", "credit"),
                        new PaymentMethodInput("banco-galicia", "visa", "oro")),
                List.of("vip"))));
        assertTrue(e.getMessage().contains("banco-inventado"), e.getMessage());
        assertTrue(e.getMessage().contains("unionpay"), e.getMessage());
        assertTrue(e.getMessage().contains("oro"), e.getMessage());
        assertTrue(e.getMessage().contains("vip"), e.getMessage());
        verify(methodRepository, never()).saveAll(any());
        verify(methodRepository, never()).deleteAll(any());
        verify(benefitRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("sin catálogo de emisores (tabla del scraper caída) es 503, no un 400 engañoso")
    void sinCatalogo() {
        when(issuerReader.findAll()).thenReturn(List.of());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.replace(user,
                request(List.of(new PaymentMethodInput("banco-galicia", null, null)), List.of())));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    @DisplayName("reemplazar conserva los que siguen (mismo id), borra los que no vienen y agrega los nuevos sin duplicar")
    @SuppressWarnings("unchecked")
    void reemplaza() {
        UserPaymentMethod sigue = guardado(1L, "banco-galicia", "visa", "credit");
        UserPaymentMethod sale = guardado(2L, "banco-galicia", "mastercard", "debit");
        when(methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(sigue, sale));
        when(benefitRepository.findByUserIdOrderByBenefitAsc(7L)).thenReturn(List.of(
                UserBenefit.builder().userId(7L).benefit("jubilado").build()));

        service.replace(user, request(List.of(
                new PaymentMethodInput(" Banco-Galicia ", "VISA", "credit"),
                new PaymentMethodInput("mercado-pago", "", null),
                new PaymentMethodInput("mercado-pago", null, null)), List.of("plan_sueldo", "plan_sueldo")));

        verify(methodRepository).deleteAll(List.of(sale));
        ArgumentCaptor<List<UserPaymentMethod>> nuevos = ArgumentCaptor.forClass(List.class);
        verify(methodRepository).saveAll(nuevos.capture());
        assertEquals(1, nuevos.getValue().size(), "Mercado Pago una sola vez; Galicia Visa ya estaba");
        UserPaymentMethod mp = nuevos.getValue().get(0);
        assertEquals("mercado-pago", mp.getIssuerSlug());
        assertNull(mp.getNetwork());
        assertNull(mp.getCardType());
        assertSame(user, mp.getUser());

        verify(benefitRepository).deleteAll(List.of(UserBenefit.builder().userId(7L).benefit("jubilado").build()));
        ArgumentCaptor<List<UserBenefit>> beneficios = ArgumentCaptor.forClass(List.class);
        verify(benefitRepository).saveAll(beneficios.capture());
        assertEquals(List.of("plan_sueldo"), beneficios.getValue().stream().map(UserBenefit::getBenefit).toList());
    }

    @Test
    @DisplayName("GET arma emisor con nombre y tipo; un emisor que ya no está en el catálogo sale con su slug")
    void get() {
        when(methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(
                guardado(1L, "banco-galicia", "visa", "credit"),
                guardado(2L, "banco-viejo", null, null)));
        when(benefitRepository.findByUserIdOrderByBenefitAsc(7L)).thenReturn(List.of(
                UserBenefit.builder().userId(7L).benefit("jubilado").build()));

        MyPaymentMethodsResponse r = service.get(user);
        assertEquals(2, r.methods().size());
        assertEquals("Banco Galicia", r.methods().get(0).issuer().name());
        assertEquals("bank", r.methods().get(0).issuer().kind());
        assertEquals("visa", r.methods().get(0).network());
        assertEquals("banco-viejo", r.methods().get(1).issuer().name());
        assertNull(r.methods().get(1).network());
        assertEquals(List.of("jubilado"), r.benefits());
    }

    @Test
    @DisplayName("el catálogo trae emisores de la tabla del scraper y redes, tipos y beneficios fijos")
    void catalogo() {
        var c = service.catalog();
        assertEquals(List.of("banco-galicia", "mercado-pago"), c.issuers().stream().map(i -> i.slug()).toList());
        assertTrue(c.networks().stream().anyMatch(n -> n.slug().equals("visa")));
        assertTrue(c.cardTypes().stream().anyMatch(n -> n.slug().equals("credit")));
        assertEquals(List.of("jubilado", "empleado_publico", "plan_sueldo", "club_la_nacion", "jumbo_mas", "mi_carrefour"),
                c.benefits().stream().map(b -> b.slug()).toList());
    }
}
