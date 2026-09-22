package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SepaComercioNombresTest {

    private final SepaComercioNombres nombres = new SepaComercioNombres();

    @Test
    @DisplayName("Carrefour (id 10) con bandera Maxi muestra 'Carrefour · Maxi'")
    void carrefourConBanderaMaxi() {
        assertEquals("Carrefour · Maxi", nombres.nombre("10", "Maxi"));
    }

    @Test
    @DisplayName("Cencosud (id 9) con bandera Vea muestra 'Cencosud · Vea'")
    void cencosudConBanderaVea() {
        assertEquals("Cencosud · Vea", nombres.nombre("9", "Vea"));
    }

    @Test
    @DisplayName("Farmacity (id 24) con bandera SIMPLICITY muestra 'Farmacity · SIMPLICITY'")
    void farmacityConBanderaSimplicity() {
        assertEquals("Farmacity · SIMPLICITY", nombres.nombre("24", "SIMPLICITY"));
    }

    @Test
    @DisplayName("bandera redundante colapsa a la cadena (COTO CICSA -> COTO)")
    void banderaRedundanteColapsa() {
        assertEquals("COTO", nombres.nombre("12", "COTO CICSA"));
        assertEquals("DIA", nombres.nombre("15", "Supermercados DIA"));
        assertEquals("Toledo", nombres.nombre("21", "Toledo"));
        assertEquals("Deheza", nombres.nombre("3", "DEHEZA S.A.I.C.F. e I."));
    }

    @Test
    @DisplayName("bandera con variante de acentos colapsa a la cadena (La Anonima -> La Anónima)")
    void acentosRedundantesColapsan() {
        assertEquals("La Anónima", nombres.nombre("2", "La Anonima"));
    }

    @Test
    @DisplayName("comercio sin mapeo devuelve la bandera tal cual")
    void comercioDesconocidoConservaBandera() {
        assertEquals("Supermercado Inventado", nombres.nombre("999", "Supermercado Inventado"));
    }

    @Test
    @DisplayName("id null devuelve la bandera tal cual")
    void idNullConservaBandera() {
        assertEquals("Maxi", nombres.nombre(null, "Maxi"));
    }

    @Test
    @DisplayName("bandera vacía o null con id curado devuelve solo la cadena")
    void banderaVaciaDevuelveCadena() {
        assertEquals("Carrefour", nombres.nombre("10", null));
        assertEquals("COTO", nombres.nombre("12", ""));
        assertEquals("Carrefour", nombres.nombre("10", "   "));
    }
}