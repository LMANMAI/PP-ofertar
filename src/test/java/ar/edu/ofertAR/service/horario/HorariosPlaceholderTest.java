package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HorariosPlaceholder: el 00:00 a 24:00 de relleno de un comercio entero")
class HorariosPlaceholderTest {

    private static HorariosResponse semana(String celda) {
        return HorarioParser.parsearSemana(Collections.nCopies(7, celda));
    }

    private static final HorariosResponse VEINTICUATRO = semana("00:00 a 24:00");

    @Test
    @DisplayName("todas las sucursales, todos los días en 24 h: es relleno (Estación Lima)")
    void todoEnVeinticuatroHorasEsRelleno() {
        assertTrue(HorariosPlaceholder.esRelleno24h(List.of(VEINTICUATRO)));
        assertTrue(HorariosPlaceholder.esRelleno24h(List.of(VEINTICUATRO, VEINTICUATRO, VEINTICUATRO)));
    }

    @Test
    @DisplayName("24 h con segundos cuenta igual: el parser ya lo normalizó")
    void conSegundos() {
        assertTrue(HorariosPlaceholder.esRelleno24h(List.of(VEINTICUATRO, semana("00:00:00 a 24:00:00"))));
    }

    @Test
    @DisplayName("una sola sucursal con otro horario es variación real (Axion: 52 de 55 en 24 h)")
    void conVariacionRealNoEsRelleno() {
        HorariosResponse seisADiez = semana("06:00 a 22:00");
        assertFalse(HorariosPlaceholder.esRelleno24h(List.of(VEINTICUATRO, VEINTICUATRO, seisADiez)));
    }

    @Test
    @DisplayName("un solo día distinto alcanza: no son 24 h todos los días")
    void unDiaDistinto() {
        HorariosResponse domingoCerrado = HorarioParser.parsearSemana(List.of(
                "00:00 a 24:00", "00:00 a 24:00", "00:00 a 24:00", "00:00 a 24:00",
                "00:00 a 24:00", "00:00 a 24:00", "cerrado"));
        assertFalse(HorariosPlaceholder.esRelleno24h(List.of(VEINTICUATRO, domingoCerrado)));
    }

    @Test
    @DisplayName("las sucursales sin horario no cuentan; sin ninguna informada no hay relleno")
    void sinInformar() {
        assertTrue(HorariosPlaceholder.esRelleno24h(Arrays.asList(VEINTICUATRO, null)));
        assertFalse(HorariosPlaceholder.esRelleno24h(Arrays.asList(null, null)));
        assertFalse(HorariosPlaceholder.esRelleno24h(List.of()));
        assertFalse(HorariosPlaceholder.esRelleno24h(null));
    }

    @Test
    @DisplayName("un horario común repetido en todas no es el relleno de 24 h (DIA: 8 a 22)")
    void horarioUniformeNoEsRelleno() {
        HorariosResponse ochoADiez = semana("8:00 a 22:00");
        assertFalse(HorariosPlaceholder.esRelleno24h(List.of(ochoADiez, ochoADiez, ochoADiez)));
    }
}
