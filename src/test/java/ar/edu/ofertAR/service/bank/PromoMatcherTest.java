package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.service.bank.BankPromotionReader.Promo;
import ar.edu.ofertAR.service.bank.PromoMatcher.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static ar.edu.ofertAR.service.bank.PromoFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** La regla de match de la §4 del SPEC de promos bancarias. */
class PromoMatcherTest {

    private static final PromoMatcher.UserMethod GALICIA_VISA_CREDITO = metodo("banco-galicia", "visa", "credit");

    private static Result eval(Promo p, PromoMatcher.UserContext u) {
        return PromoMatcher.evaluate(p, u);
    }

    @Nested
    @DisplayName("todos los medios")
    class Todos {

        @Test
        @DisplayName("un target sin emisor, red ni tipo da all, tenga o no tarjetas el usuario")
        void targetAbiertoDaAll() {
            Promo p = promo(1).build();
            assertEquals(new Result("all", null), eval(p, usuario()));
            assertEquals(new Result("all", null), eval(p, usuario(GALICIA_VISA_CREDITO)));
            assertTrue(PromoMatcher.opensToAll(p));
        }

        @Test
        @DisplayName("emisor desconocido nunca matchea como 'todos los medios': da none")
        void emisorDesconocidoNuncaEsTodos() {
            Promo p = promo(1).flags("issuer_unknown").build();
            assertEquals(new Result("none", null), eval(p, usuario()));
            assertEquals(new Result("none", null), eval(p, usuario(GALICIA_VISA_CREDITO)));
            assertFalse(PromoMatcher.opensToAll(p));
            assertTrue(PromoMatcher.usableTargets(p).isEmpty());
        }

        @Test
        @DisplayName("emisor desconocido como lo guarda el scraper (sin filas de targets): none, nunca all")
        void emisorDesconocidoSinTargets() {
            Promo p = promo(1).flags("issuer_unknown").targets().build();
            assertEquals(new Result("none", null), eval(p, usuario()));
            assertEquals(new Result("none", null), eval(p, usuario(GALICIA_VISA_CREDITO)));
            assertFalse(PromoMatcher.opensToAll(p));
        }

        @Test
        @DisplayName("una promo sin targets y sin marca tampoco es 'todos los medios'")
        void sinTargetsNoEsTodos() {
            Promo p = promo(1).targets().build();
            assertEquals("none", eval(p, usuario(GALICIA_VISA_CREDITO)).match());
        }

        @Test
        @DisplayName("club regional (kind club) como medio: matchea su target y sólo en su provincia")
        void clubRegional() {
            Promo p = promo(1).regiones("CORDOBA").targets(target("club-la-voz", null, null)).build();
            var socio = new PromoMatcher.UserMethod("club-la-voz", "Club La Voz", "club", null, null);
            assertEquals(new Result("near", "Sólo en Córdoba"), eval(p, usuario(socio)));
            assertEquals(new Result("match", "Con tu Club La Voz"), eval(p, usuario(List.of(socio), Set.of(), "CORDOBA")));
        }

        @Test
        @DisplayName("emisor desconocido tampoco da near aunque el target sin emisor pida Visa")
        void emisorDesconocidoConRed() {
            Promo p = promo(1).flags("issuer_unknown").targets(target(null, "visa", null)).build();
            assertEquals("none", eval(p, usuario(metodo("banco-galicia", "mastercard", "credit"))).match());
            assertEquals("none", eval(p, usuario(GALICIA_VISA_CREDITO)).match());
        }
    }

    @Nested
    @DisplayName("medios del usuario")
    class Medios {

        @Test
        @DisplayName("emisor, red y tipo iguales dan match con la razón 'Con tu ...'")
        void matchExacto() {
            Promo p = promo(1).targets(target("banco-galicia", "visa", "credit")).build();
            assertEquals(new Result("match", "Con tu Banco Galicia Visa"), eval(p, usuario(GALICIA_VISA_CREDITO)));
        }

        @Test
        @DisplayName("red o tipo null en el target aceptan cualquiera")
        void targetSinRedNiTipo() {
            Promo p = promo(1).targets(target("banco-galicia", null, null)).build();
            assertEquals("match", eval(p, usuario(metodo("banco-galicia", "mastercard", "debit"))).match());
        }

        @Test
        @DisplayName("mismo emisor y otro tipo da near 'Pide crédito'")
        void pideCredito() {
            Promo p = promo(1).targets(target("banco-galicia", "visa", "credit")).build();
            assertEquals(new Result("near", "Pide crédito"), eval(p, usuario(metodo("banco-galicia", "visa", "debit"))));
        }

        @Test
        @DisplayName("mismo emisor y otra red da near 'Pide Visa'")
        void pideVisa() {
            Promo p = promo(1).targets(target("banco-galicia", "visa", null)).build();
            assertEquals(new Result("near", "Pide Visa"), eval(p, usuario(metodo("banco-galicia", "mastercard", "credit"))));
        }

        @Test
        @DisplayName("con varias opciones dice lo mínimo que falta")
        void eligeElNearConMenosDiferencias() {
            Promo p = promo(1).targets(
                    target("banco-galicia", "amex", "credit"),
                    target("banco-galicia", "visa", "credit")).build();
            assertEquals(new Result("near", "Pide crédito"), eval(p, usuario(metodo("banco-galicia", "visa", "debit"))));
        }

        @Test
        @DisplayName("una red sin tarjeta declarada no se supone: pide la red")
        void redNoDeclarada() {
            Promo p = promo(1).targets(target("banco-galicia", "visa", "credit")).build();
            assertEquals(new Result("near", "Pide Visa crédito"), eval(p, usuario(metodo("banco-galicia", null, null))));
        }

        @Test
        @DisplayName("otro emisor da none")
        void otroEmisor() {
            Promo p = promo(1).targets(target("banco-nacion", null, null)).build();
            assertEquals(new Result("none", null), eval(p, usuario(GALICIA_VISA_CREDITO)));
        }

        @Test
        @DisplayName("una billetera cargada sin tipo matchea un target 'wallet'")
        void billetera() {
            Promo p = promo(1).targets(target("mercado-pago", null, "wallet")).build();
            assertEquals(new Result("match", "Con tu Mercado Pago"), eval(p, usuario(metodo("mercado-pago", null, null))));
        }
    }

    @Nested
    @DisplayName("condiciones de la persona (D6)")
    class Condiciones {

        private final Promo jubilados = promo(1).condiciones("jubilado")
                .targets(target("banco-galicia", null, null)).build();

        @Test
        @DisplayName("sin declararla da near 'Para jubilados'")
        void sinDeclarar() {
            assertEquals(new Result("near", "Para jubilados"), eval(jubilados, usuario(GALICIA_VISA_CREDITO)));
        }

        @Test
        @DisplayName("declarada da match")
        void declarada() {
            var u = usuario(List.of(GALICIA_VISA_CREDITO), Set.of("jubilado"), null);
            assertEquals("match", eval(jubilados, u).match());
        }

        @Test
        @DisplayName("una promo para todos los medios con condición tampoco es all si no la declaró")
        void todosConCondicion() {
            Promo p = promo(2).condiciones("plan_sueldo").build();
            assertEquals(new Result("near", "Con plan sueldo"), eval(p, usuario()));
            assertEquals("all", eval(p, usuario(List.of(), Set.of("plan_sueldo"), null)).match());
        }

        @Test
        @DisplayName("hacen falta todas las condiciones")
        void todasLasCondiciones() {
            Promo p = promo(3).condiciones("jubilado", "plan_sueldo").build();
            var u = usuario(List.of(), Set.of("jubilado"), null);
            assertEquals(new Result("near", "Con plan sueldo"), eval(p, u));
        }

        @Test
        @DisplayName("sin el medio, la condición no convierte un none en near")
        void sinMedioSigueNone() {
            Promo p = promo(4).condiciones("jubilado").targets(target("banco-nacion", null, null)).build();
            assertEquals("none", eval(p, usuario(GALICIA_VISA_CREDITO)).match());
        }
    }

    @Nested
    @DisplayName("regiones (D7)")
    class Regiones {

        private final Promo cordoba = promo(1).regiones("CORDOBA").build();

        @Test
        @DisplayName("sin zona del usuario no cuenta como match: near 'Sólo en Córdoba'")
        void sinZona() {
            assertEquals(new Result("near", "Sólo en Córdoba"), eval(cordoba, usuario()));
        }

        @Test
        @DisplayName("con la provincia de la promo da all/match")
        void mismaProvincia() {
            assertEquals("all", eval(cordoba, usuario(List.of(), Set.of(), "CORDOBA")).match());
        }

        @Test
        @DisplayName("con otra provincia da near")
        void otraProvincia() {
            assertEquals(new Result("near", "Sólo en Córdoba"), eval(cordoba, usuario(List.of(), Set.of(), "MENDOZA")));
        }

        @Test
        @DisplayName("varias provincias y regiones escritas distinto en la base")
        void variasProvincias() {
            Promo p = promo(2).regiones("Río Negro", "NEUQUEN").targets(target("banco-galicia", null, null)).build();
            assertEquals(new Result("near", "Sólo en Río Negro y Neuquén"), eval(p, usuario(GALICIA_VISA_CREDITO)));
            var neuquino = usuario(List.of(GALICIA_VISA_CREDITO), Set.of(), Provincias.canonica("Neuquén").orElseThrow());
            assertEquals("match", eval(p, neuquino).match());
        }

        @Test
        @DisplayName("zona de varias provincias (AMBA): alcanza con que una esté en la promo")
        void zonaAmba() {
            var amba = new PromoMatcher.UserContext(List.of(), Set.of(), Provincias.zona("AMBA"));
            assertEquals("all", eval(promo(3).regiones("BUENOS_AIRES").build(), amba).match());
            assertEquals("all", eval(promo(4).regiones("CABA").build(), amba).match());
            assertEquals(new Result("near", "Sólo en Córdoba"), eval(cordoba, amba));
        }
    }

    @Nested
    @DisplayName("provincias")
    class ProvinciasTest {

        @Test
        @DisplayName("nombre, sin tildes, canónica e ISO 3166-2 son la misma provincia")
        void normaliza() {
            for (String v : List.of("Córdoba", "cordoba", "CORDOBA", "AR-X", "x", " Provincia de Córdoba ")) {
                assertEquals("CORDOBA", Provincias.canonica(v).orElse(null), v);
            }
            assertEquals("CABA", Provincias.canonica("Capital Federal").orElse(null));
            assertEquals("CABA", Provincias.canonica("CABA").orElse(null));
            assertEquals("BUENOS_AIRES", Provincias.canonica("BUENOS_AIRES").orElse(null));
            assertEquals("RIO_NEGRO", Provincias.canonica("rio negro").orElse(null));
        }

        @Test
        @DisplayName("zona: lista separada por coma, AMBA/GBA = CABA + Buenos Aires, vacío sin zona")
        void zona() {
            assertEquals(Set.of("CABA", "BUENOS_AIRES"), Provincias.zona("AMBA"));
            assertEquals(Set.of("CABA", "BUENOS_AIRES"), Provincias.zona("gba"));
            assertEquals(Set.of("CABA", "BUENOS_AIRES"), Provincias.zona("CABA,BUENOS_AIRES"));
            assertEquals(Set.of("CORDOBA", "CABA"), Provincias.zona("Córdoba, Capital Federal"));
            assertEquals(Set.of(), Provincias.zona(null));
            assertEquals(Set.of(), Provincias.zona(" "));
            var e = assertThrows(IllegalArgumentException.class, () -> Provincias.zona("CABA,Narnia"));
            assertTrue(e.getMessage().contains("Narnia"));
        }

        @Test
        @DisplayName("lo que no es una provincia no se reconoce")
        void noReconoce() {
            assertTrue(Provincias.canonica("Narnia").isEmpty());
            assertTrue(Provincias.canonica("  ").isEmpty());
        }
    }
}
