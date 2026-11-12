package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.dto.response.BankPromotionsTodayResponse;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse.PromoDay;
import ar.edu.ofertAR.dto.response.PromoView;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.bank.BankPromotionReader.Promo;
import ar.edu.ofertAR.service.bank.PaymentIssuerReader.Issuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static ar.edu.ofertAR.service.bank.PromoFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BankPromotionServiceTest {

    /** Martes 10/11/2026, 23:30 en Argentina (ya es miércoles en UTC). */
    private static final Clock RELOJ = Clock.fixed(
            ZonedDateTime.of(2026, 11, 10, 23, 30, 0, 0, ZoneId.of("America/Argentina/Buenos_Aires")).toInstant(),
            ZoneId.of("UTC"));

    @Mock private BankPromotionReader promotionReader;
    @Mock private PaymentIssuerReader issuerReader;
    @Mock private PaymentMethodService paymentMethodService;

    private BankPromotionService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new BankPromotionService(promotionReader, issuerReader, paymentMethodService, RELOJ);
        user = User.builder().id(7L).name("Alex").email("alex@test").password("x").build();
        lenient().when(issuerReader.findAll()).thenReturn(List.of(
                new Issuer("banco-galicia", "Banco Galicia", "bank"),
                new Issuer("banco-patagonia", "Banco Patagonia", "bank")));
        lenient().when(paymentMethodService.userMethods(any(), any()))
                .thenReturn(List.of(metodo("banco-galicia", "visa", "credit")));
        lenient().when(paymentMethodService.userBenefits(any())).thenReturn(Set.of());
    }

    private void promos(Promo... promos) {
        when(promotionReader.findValidBetween(any(), any())).thenReturn(List.of(promos));
    }

    private static List<Long> ids(PromoDay day) {
        return day.promos().stream().map(PromoView::id).toList();
    }

    @Test
    @DisplayName("sin from, la semana arranca hoy en hora argentina y trae 7 días con su weekday")
    void semanaDesdeHoy() {
        promos();
        BankPromotionsWeekResponse week = service.week(user, null, true, null);
        assertEquals(LocalDate.of(2026, 11, 10), week.from());
        assertEquals(LocalDate.of(2026, 11, 16), week.to());
        assertEquals(7, week.days().size());
        assertEquals(2, week.days().get(0).weekday(), "el 10/11/2026 es martes");
        assertEquals(1, week.days().get(6).weekday(), "el 16/11/2026 es lunes");
        assertTrue(week.hasPaymentMethods());
        verify(promotionReader).findValidBetween(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16));
    }

    @Test
    @DisplayName("orden: match/all, near y none; % de mayor a menor y las cuotas debajo de los % (D8)")
    void orden() {
        promos(
                promo(1).cuotas(12).build(),                                                       // all, cuotas
                promo(2).percent("10").targets(target("banco-galicia", "visa", "credit")).build(), // match 10%
                promo(3).percent("30").build(),                                                    // all 30%
                promo(4).percent("50").targets(target("banco-galicia", "visa", "debit")).build(),  // near 50%
                promo(5).percent("40").targets(target("banco-nacion", null, null)).build(),        // none 40%
                promo(6).monto("100000").build(),                                                  // all, monto
                promo(7).percent("25").targets(target("banco-galicia", null, "credit")).build());  // match 25%

        PromoDay todas = service.week(user, null, false, null).days().get(0);
        assertEquals(List.of(3L, 7L, 2L, 1L, 6L, 4L, 5L), ids(todas));
        assertEquals(new BigDecimal("50"), todas.bestPercent(), "con mine=false cuenta todo");

        PromoDay mias = service.week(user, null, true, null).days().get(0);
        assertEquals(List.of(3L, 7L, 2L, 1L, 6L, 4L), ids(mias), "con mine=true no vienen las none");
        assertEquals(new BigDecimal("30"), mias.bestPercent(), "con mine=true el near no cuenta para el mejor %");
    }

    @Test
    @DisplayName("cada día trae sólo las promos de ese día de la semana y dentro de la vigencia")
    void diasYVigencia() {
        int soloMiercoles = 1 << 2;
        promos(
                promo(1).dias(soloMiercoles).build(),
                promo(2).vigencia(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 12)).build());

        List<PromoDay> days = service.week(user, null, true, null).days();
        assertEquals(List.of(2L), ids(days.get(0)), "martes 10");
        assertEquals(List.of(1L, 2L), ids(days.get(1)), "miércoles 11");
        assertEquals(List.of(2L), ids(days.get(2)), "jueves 12");
        assertEquals(List.of(), ids(days.get(3)), "viernes 13: la 2 venció");
        assertNull(days.get(3).bestPercent());
    }

    @Test
    @DisplayName("sólo cuotas: bestPercent null")
    void sinPorcentaje() {
        promos(promo(1).cuotas(6).build());
        PromoDay day = service.week(user, null, true, null).days().get(0);
        assertEquals(1, day.promos().size());
        assertNull(day.bestPercent());
        assertEquals("6 cuotas sin interés", day.promos().get(0).discount().text());
        assertNull(day.promos().get(0).discount().percent());
    }

    @Test
    @DisplayName("nota de cambio de mes desde el día 1 si vencieron promos el 31")
    void rollover() {
        LocalDate fin = LocalDate.of(2026, 11, 30);
        promos(
                promo(1).vigencia(LocalDate.of(2026, 11, 1), fin).build(),
                promo(2).vigencia(LocalDate.of(2026, 11, 1), fin).build(),
                promo(3).vigencia(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)).build());

        BankPromotionsWeekResponse week = service.week(user, LocalDate.of(2026, 11, 28), false, null);
        assertEquals(1, week.notes().size());
        assertEquals("rollover", week.notes().get(0).kind());
        assertEquals(LocalDate.of(2026, 12, 1), week.notes().get(0).fromDate());
        assertEquals(List.of(1L, 2L, 3L), ids(week.days().get(2)), "30/11");
        assertEquals(List.of(3L), ids(week.days().get(3)), "1/12");
    }

    @Test
    @DisplayName("sin cambio de mes no hay nota")
    void sinRollover() {
        promos(promo(1).build());
        assertTrue(service.week(user, LocalDate.of(2026, 11, 10), false, null).notes().isEmpty());
    }

    @Test
    @DisplayName("today trae hasta 3 promos y el mejor % se calcula sobre todas las del día")
    void hoy() {
        promos(
                promo(1).percent("10").build(),
                promo(2).percent("15").build(),
                promo(3).percent("20").build(),
                promo(4).percent("35").targets(target("banco-galicia", "visa", "credit")).build(),
                promo(5).percent("5").build());
        BankPromotionsTodayResponse today = service.today(user, true, null);
        assertEquals(LocalDate.of(2026, 11, 10), today.date());
        assertEquals(List.of(4L, 3L, 2L), today.promos().stream().map(PromoView::id).toList());
        assertEquals(new BigDecimal("35"), today.bestPercent());
    }

    @Test
    @DisplayName("una provincia que no se reconoce es un 400")
    void provinciaInvalida() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.week(user, null, true, "Narnia"));
        assertTrue(e.getMessage().contains("Narnia"));
        verifyNoInteractions(promotionReader);
    }

    @Test
    @DisplayName("la provincia del query param llega canónica al match")
    void provinciaCanonica() {
        promos(promo(1).regiones("CORDOBA").build());
        assertEquals("all", service.week(user, null, true, "AR-X").days().get(0).promos().get(0).match());
        assertEquals("near", service.week(user, null, true, null).days().get(0).promos().get(0).match());
    }

    @Test
    @DisplayName("variantes por paquete: percentRange, requires.package y el mejor % es el del mejor paquete")
    void paquetes() {
        promos(promo(1).percent("15").targets(
                paquete("banco-patagonia", "Patagonia Plus", "15"),
                paquete("banco-patagonia", "Patagonia Plus", "35")).tope("20000").build());
        PromoDay day = service.week(user, null, false, null).days().get(0);
        PromoView v = day.promos().get(0);
        assertEquals(new BigDecimal("15"), v.percentRange().min());
        assertEquals(new BigDecimal("35"), v.percentRange().max());
        assertEquals("Patagonia Plus", v.requires().packageName());
        assertNull(v.requires().network());
        assertEquals(List.of("banco-patagonia"), v.issuers().stream().map(PromoView.PromoIssuer::slug).toList());
        assertEquals("Banco Patagonia", v.issuers().get(0).name());
        assertFalse(v.allMethods());
        assertEquals(new BigDecimal("20000"), v.cap().amount());
        assertEquals(new BigDecimal("35"), day.bestPercent());
    }

    @Test
    @DisplayName("emisor desconocido: sin issuers, allMethods false y none, así no aparece en 'Con mis tarjetas'")
    void emisorDesconocidoEnLaVista() {
        promos(promo(1).flags("issuer_unknown").build());
        PromoView v = service.week(user, null, false, null).days().get(0).promos().get(0);
        assertEquals("none", v.match());
        assertFalse(v.allMethods());
        assertTrue(v.issuers().isEmpty());
        assertEquals(List.of("issuer_unknown"), v.qualityFlags());
        assertTrue(service.week(user, null, true, null).days().get(0).promos().isEmpty());
    }

    @Test
    @DisplayName("el % sale sin ceros de más y el texto del descuento cae al % si la fuente no lo trae")
    void discountText() {
        promos(promo(1).percent("12.50").build(), promo(2).percent("30.00").text("30% de ahorro").build());
        List<PromoView> list = service.week(user, null, false, null).days().get(0).promos();
        assertEquals("30% de ahorro", list.get(0).discount().text());
        assertEquals("30", list.get(0).discount().percent().toPlainString());
        assertEquals("12.5%", list.get(1).discount().text());
    }
}
