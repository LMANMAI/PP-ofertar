package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.FranjaHorariaResponse;
import ar.edu.ofertAR.dto.response.HorariosResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Salvo las marcadas "armado", las celdas son literales del sucursales.csv de SEPA
 * (septiembre 2026), con sus espacios de relleno: cada forma frecuente del dataset
 * tiene la suya. Las armadas cubren bordes que el dataset todavía no trae.
 */
@DisplayName("HorarioParser: celdas de horario de SEPA a franjas")
class HorarioParserTest {

    private static List<FranjaHorariaResponse> franjas(String... desdeHasta) {
        FranjaHorariaResponse[] out = new FranjaHorariaResponse[desdeHasta.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = new FranjaHorariaResponse(desdeHasta[2 * i], desdeHasta[2 * i + 1]);
        }
        return Arrays.asList(out);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1} a {2}")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "08:00 a 22:00|08:00|22:00",                        // la forma más común (10.330 celdas)
            "8:30 a 22:00|08:30|22:00",                         // COTO, sin cero a la izquierda
            "08:00 A 22:00|08:00|22:00",                        // Carrefour, "A" mayúscula
            "08:30:00 a 20:30:00|08:30|20:30",                  // Cooperativa Obrera, con segundos
            "9 a 21:30                     |09:00|21:30",       // Unicoop, relleno al final
            "10.30 a 20.00|10:30|20:00",                        // Carrefour: punto con dos dígitos
            "00:00 a 24:00|00:00|24:00",                        // 24 h: 24:00 vale como cierre
    })
    void rangoSimple(String celda, String desde, String hasta) {
        assertEquals(franjas(desde, hasta), HorarioParser.parsear(celda));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "08:30:00 a 12:30:00 - 16:00:00 a 20:30:00|08:30|12:30|16:00|20:30", // Cooperativa Obrera
            "08:30 a 13:00 y 16:30 a 21:00|08:30|13:00|16:30|21:00",             // Cencosud
            "08:30 a 13:00 Y 17:00 A 21:30|08:30|13:00|17:00|21:30",             // Carrefour
            "09:00 a 13:00 - 17:00 a 21:00|09:00|13:00|17:00|21:00",
            "8:30 a 13:00 - 16:00 a 20:30|08:30|13:00|16:00|20:30",              // COTO
            "8:30  a 14:00 y de 16:00 a  21:00|08:30|14:00|16:00|21:00",   // COTO: "y de" y dobles espacios
            "8 a 12 y 16 a 21|08:00|12:00|16:00|21:00",                    // horas sin minutos
            "8:30 a 12:30 y 16 a 20        |08:30|12:30|16:00|20:00",      // Unicoop, mezcla y relleno
            "9 a 13:30 y 17 a 21           |09:00|13:30|17:00|21:00",
            "00:00 a 06:00-22:00 a 00:00|00:00|06:00|22:00|24:00",         // Deheza: abierto de noche
    })
    void turnoPartido(String celda, String d1, String h1, String d2, String h2) {
        assertEquals(franjas(d1, h1, d2, h2), HorarioParser.parsear(celda));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"cerrado", "Cerrado", "CERRADO", "Cerrado                       "})
    void cerradoEsListaVacia(String celda) {
        List<FranjaHorariaResponse> r = HorarioParser.parsear(celda);
        assertNotNull(r, "cerrado no es 'no informado'");
        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName("un cierre anterior a la apertura cruza la medianoche y se conserva tal cual")
    void cruzaLaMedianoche() {
        assertEquals(franjas("07:30", "01:00"), HorarioParser.parsear("07:30 a 01:00")); // Farmacity
        assertEquals(franjas("18:00", "02:00"), HorarioParser.parsear("18:00 a 02:00")); // armado
    }

    @Test
    @DisplayName("cerrar a las 00:00 es cerrar a las 24:00, no cruzar la medianoche")
    void cierreAMedianocheSeNormaliza() {
        assertEquals(franjas("08:30", "24:00"), HorarioParser.parsear("8:30 a 00:00")); // COTO
        assertEquals(franjas("07:30", "24:00"), HorarioParser.parsear("07:30 a 00:00"));
    }

    @ParameterizedTest(name = "\"{0}\" -> null")
    @ValueSource(strings = {
            "",                                 // 252 celdas de Carrefour
            "   ",                              // armado
            "cerra a",                          // Farmacity
            "8 a 12 y 15.3 a 19.3",             // La Agrícola: ¿15:30, 15:03 o 15,3 h?
            "8 a 12.3 y 17 a 21",
            "08 a 13 a 15.3 a 20.3",            // además, mal armado
            "11:24 a 11:24",                    // Farmacity: una marca de tiempo, no un horario
            "00:00 a 00:00",                    // ¿24 h o relleno? no se sabe
            // Armados:
            "24:00 a 08:00",                    // 24:00 no es una apertura
            "08:00 a 24:30",
            "25:00 a 26:00",
            "08:60 a 20:00",
            "08:30:30 a 20:30:00",              // segundos que no son cero
            "9 a 1",                            // cruza la medianoche sin minutos: ¿9 a 13?
            "08:00 a 14:00 y 12:00 a 20:00",    // franjas que se pisan
            "16:00 a 20:00 y 08:00 a 12:00",    // franjas desordenadas
            "22:00 a 02:00 y 08:00 a 12:00",    // solo la última puede cruzar la medianoche
            "08:00 a 12:00 y 16:00 a 09:00",    // cruza y se come la apertura del día siguiente
            "de lunes a viernes",
            "08:00",
    })
    void loDudosoNoSeAdivina(String celda) {
        assertNull(HorarioParser.parsear(celda));
    }

    @Test
    @DisplayName("una celda que falta es no informado")
    void celdaNula() {
        assertNull(HorarioParser.parsear(null));
    }

    @Test
    @DisplayName("la semana: cada día por separado, y null si ninguno se entiende")
    void semana() {
        HorariosResponse h = HorarioParser.parsearSemana(List.of(
                "8:30 a 22:00", "8:30 a 13:00 - 16:30 a 20:30", "Cerrado", "", "00:00 a 24:00",
                "18:00 a 02:00", "cerrado"));

        assertNotNull(h);
        assertEquals(franjas("08:30", "22:00"), h.lunes());
        assertEquals(franjas("08:30", "13:00", "16:30", "20:30"), h.martes());
        assertEquals(List.of(), h.miercoles());
        assertNull(h.jueves());
        assertEquals(HorariosResponse.TODO_EL_DIA, h.viernes());
        assertEquals(franjas("18:00", "02:00"), h.sabado());
        assertEquals(List.of(), h.domingo());

        assertNull(HorarioParser.parsearSemana(List.of("", "", "", "", "", "", "")));
        assertNull(HorarioParser.parsearSemana(List.of("08:00 a 22:00")), "no son siete días");
    }
}
