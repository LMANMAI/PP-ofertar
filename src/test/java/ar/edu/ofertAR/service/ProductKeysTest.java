package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductKeysTest {

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("el mismo producto aunque el código venga en otro formato")
    @CsvSource({
            "7790895000997, 07790895000997",
            "7790895000997, ' 7790895000997 '",
            "12345670, 0000012345670",
            "779089500099, 0779089500099",
    })
    void sameEan_acrossFormats(String a, String b) {
        assertTrue(ProductKeys.sameEan(a, b));
        assertTrue(ProductKeys.sameEan(b, a));
    }

    @ParameterizedTest(name = "{0} != {1}")
    @DisplayName("productos distintos, o códigos vacíos, no son el mismo")
    @CsvSource(value = {
            "7790895000997, 7790895000998",
            "7790895000997, NULL",
            "7790895000997, ''",
            "NULL, NULL",
            "00000000, 7790895000997",
    }, nullValues = "NULL")
    void sameEan_different(String a, String b) {
        assertFalse(ProductKeys.sameEan(a, b));
    }

    @Test
    @DisplayName("un código que no se puede normalizar sigue siendo igual a sí mismo")
    void sameEan_unusableCodeEqualsItself() {
        assertTrue(ProductKeys.sameEan("00000000", "00000000"));
    }

    /**
     * Estos casos tienen que dar lo mismo que {@code isLooseMatch} de
     * {@code src/utils/productMatch.ts} del frontend; si no, el push y la
     * pantalla vuelven a decir cosas distintas.
     */
    @ParameterizedTest(name = "\"{0}\" vs \"{1}\" -> {2}")
    @DisplayName("match flojo: el mismo criterio que la app")
    @CsvSource(value = {
            "Gaseosa Coca Cola 2.25 L | Gaseosa Coca Cola 2.25 l. | false",
            "Gaseosa Coca Cola 2.25 L | Agua saborizada Aquarius pomelo 1.5 l. | true",
            "BOLSA RESIDUOS MAXI | Rollo de cocina Maxi 3 u. | true",
            "Laurel molido Alicante | Laurel Molido Alicante Sobre 25 Grs | false",
            "ARROZ LARGO FINO GALLO | Arroz Gallo Oro 1 kg | true",
            "Arroz Gallo Oro | Arroz largo fino Gallo Oro 1 kg | false",
            "Jabón en polvo Ala | Jabon En Polvo Ala Matic 800 g | false",
            "Fideos tirabuzón Lucchetti | Fideos Lucchetti Tirabuzones 500 g | false",
            "Gaseosa | NULL | false",
            "Gaseosa | '' | false",
            "1 kg | Arroz Gallo Oro 1 kg | false",
    }, delimiter = '|', nullValues = "NULL")
    void looseMatch_sameAsTheApp(String bought, String matched, boolean loose) {
        assertEquals(loose, ProductKeys.isLooseMatch(bought, matched));
    }
}
