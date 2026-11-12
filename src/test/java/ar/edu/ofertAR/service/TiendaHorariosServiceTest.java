package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import ar.edu.ofertAR.model.SepaSucursal;
import ar.edu.ofertAR.repository.SepaSucursalRepository;
import ar.edu.ofertAR.service.horario.HorarioParser;
import ar.edu.ofertAR.service.horario.HorariosJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiendaHorariosService: horario SEPA para las tiendas del mapa")
class TiendaHorariosServiceTest {

    @Mock private SepaSucursalRepository sucursalRepository;

    private TiendaHorariosService service;

    // Murillo 551, Villa Crespo: un COTO real, el punto de los tests.
    private static final double LAT = -34.6016811;
    private static final double LNG = -58.4424857;

    private static final HorariosResponse COTO = semana("8:30 a 22:00");
    private static final HorariosResponse DIA = semana("8:00 a 22:00");
    private static final HorariosResponse JUMBO = semana("08:00 a 23:00");

    @BeforeEach
    void setUp() {
        service = new TiendaHorariosService(sucursalRepository);
    }

    private static HorariosResponse semana(String celda) {
        return HorarioParser.parsearSemana(Collections.nCopies(7, celda));
    }

    /** Un punto a tantos metros al norte de la tienda. */
    private static double alNorte(double metros) {
        return LAT + metros / 111_320.0;
    }

    private static SepaSucursal suc(long id, String comercio, String bandera, double lat, HorariosResponse h) {
        return SepaSucursal.builder().id(id).comercioId(comercio).banderaId(bandera).sucursalId("s" + id)
                .latitud(lat).longitud(LNG).horarios(HorariosJson.escribir(h)).build();
    }

    private static Map<String, Object> tienda(String cadena, double lat, String direccion) {
        Map<String, Object> m = new HashMap<>();
        m.put("chainSlug", cadena);
        m.put("externalId", cadena + "-" + lat);
        m.put("name", cadena + " Villa Crespo");
        m.put("address", direccion);
        m.put("lat", lat);
        m.put("lng", LNG);
        m.put("distanceKm", 0.5);
        return m;
    }

    private void sepaTiene(SepaSucursal... sucursales) {
        when(sucursalRepository.findInBoxDeComercios(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyCollection())).thenReturn(List.of(sucursales));
    }

    @Test
    @DisplayName("toma el horario de la sucursal de su cadena a menos de 100 m, sin perder sus campos")
    void matchCercano() {
        sepaTiene(suc(1, "12", "1", alNorte(40), COTO));

        var r = service.conHorarios(List.of(tienda("coto", LAT, "Murillo 551")));

        assertEquals(COTO, r.get(0).get("horarios"));
        assertEquals("Murillo 551", r.get(0).get("address"));
        assertEquals(0.5, r.get(0).get("distanceKm"));
    }

    @Test
    @DisplayName("una sucursal de OTRA cadena al lado no le presta su horario")
    void ignoraOtrasCadenas() {
        // El mapa trae todas las cadenas juntas: el DIA de al lado está en la misma consulta.
        sepaTiene(suc(1, "15", "1", alNorte(10), DIA), suc(2, "12", "1", alNorte(400), COTO),
                suc(3, "15", "1", alNorte(3000), DIA));

        var r = service.conHorarios(List.of(
                tienda("coto", LAT, "Murillo 551"), tienda("dia", alNorte(3000), "Av. Corrientes 5000")));

        assertFalse(r.get(0).containsKey("horarios"), "el DIA de al lado no es el COTO");
        assertEquals(DIA, r.get(1).get("horarios"));
    }

    @Test
    @DisplayName("Jumbo y Disco son el mismo comercio pero distinta bandera: no se mezclan")
    void banderaDelMismoComercio() {
        sepaTiene(suc(1, "9", "2", alNorte(15), semana("08:00 a 21:00")),   // Disco
                suc(2, "9", "3", alNorte(20), JUMBO));                      // Jumbo

        var r = service.conHorarios(List.of(tienda("jumbo", LAT, "Av. Acoyte 702")));

        assertEquals(JUMBO, r.get(0).get("horarios"));
    }

    @Test
    @DisplayName("entre 100 y 250 m solo si no hay otra de la cadena a 1,5 km (hipermercados)")
    void aisladaHasta250m() {
        sepaTiene(suc(1, "9", "3", alNorte(200), JUMBO), suc(2, "9", "3", alNorte(3000), JUMBO));

        assertEquals(JUMBO, service.conHorarios(List.of(tienda("jumbo", LAT, "x"))).get(0).get("horarios"));
    }

    @Test
    @DisplayName("entre 100 y 250 m con otra de la cadena cerca es ambiguo: sin horario")
    void noAisladaEsAmbigua() {
        sepaTiene(suc(1, "12", "1", alNorte(190), COTO), suc(2, "12", "1", alNorte(-700), COTO));

        assertFalse(service.conHorarios(List.of(tienda("coto", LAT, "Av. Nazca 1244"))).get(0)
                .containsKey("horarios"));
    }

    @Test
    @DisplayName("más de 250 m no es la misma sucursal, aunque esté sola")
    void lejosNoMatchea() {
        sepaTiene(suc(1, "10", "1", alNorte(300), COTO));

        assertFalse(service.conHorarios(List.of(tienda("carrefour", LAT, "x"))).get(0)
                .containsKey("horarios"));
    }

    @Test
    @DisplayName("tiendas en el mismo punto exacto con distinta dirección tienen coordenadas de relleno")
    void coordenadasDeRelleno() {
        sepaTiene(suc(1, "9", "1", alNorte(7), semana("08:00 a 22:00")));

        var r = service.conHorarios(List.of(
                tienda("vea", LAT, "AVENIDA CORDOBA 6103  - C1427BZB - CIUDAD AUTONOMA BUENOS AIRES"),
                tienda("vea", LAT, "Vea Manzanares 3953 - Ciudad Autónoma de Buenos Aires"),
                tienda("vea", LAT, "Vea Bulnes 1048 - Ciudad Autónoma de Buenos Aires")));

        r.forEach(t -> assertFalse(t.containsKey("horarios"), "" + t.get("address")));
    }

    @Test
    @DisplayName("la misma tienda repetida (Retirá en / Pickup) sí toma el horario")
    void repetidasDeVerdad() {
        HorariosResponse hiper = semana("08:00 a 22:00");
        sepaTiene(suc(1, "11", "5", alNorte(30), hiper));

        var r = service.conHorarios(List.of(
                tienda("changomas", LAT, "Cerrito y Rubén Darío S/N"),
                tienda("changomas", LAT, "Cerrito y  Rubén Darío S/N"),
                tienda("carrefour", alNorte(5000), "Av. Sta Fe 2349 2349")));

        assertEquals(hiper, r.get(0).get("horarios"));
        assertEquals(hiper, r.get(1).get("horarios"));
    }

    @Test
    @DisplayName("una sucursal sin horario informado deja a la tienda sin el campo")
    void sucursalSinHorario() {
        sepaTiene(suc(1, "12", "1", alNorte(10), null));

        assertFalse(service.conHorarios(List.of(tienda("coto", LAT, "x"))).get(0).containsKey("horarios"));
    }

    @Test
    @DisplayName("una sola consulta a la base, solo por los comercios mapeados; Makro queda como vino")
    void unaSolaConsulta() {
        sepaTiene(suc(1, "12", "1", alNorte(10), COTO), suc(2, "15", "1", alNorte(900), DIA));
        Map<String, Object> makro = tienda("makro", LAT, "x");

        var r = service.conHorarios(List.of(
                tienda("coto", LAT, "a 1"), tienda("dia", alNorte(900), "b 2"), makro));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> comercios = ArgumentCaptor.forClass(Collection.class);
        verify(sucursalRepository, times(1)).findInBoxDeComercios(anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), comercios.capture());
        assertEquals(Set.of("12", "15"), Set.copyOf(comercios.getValue()));
        assertEquals(COTO, r.get(0).get("horarios"));
        assertEquals(DIA, r.get(1).get("horarios"));
        assertSame(makro, r.get(2));
    }

    @Test
    @DisplayName("si la base falla, el mapa sale igual, sin horarios")
    void falloDeBase() {
        when(sucursalRepository.findInBoxDeComercios(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyCollection())).thenThrow(new RuntimeException("Connection refused"));
        List<Map<String, Object>> tiendas = List.of(tienda("coto", LAT, "x"));

        assertSame(tiendas, service.conHorarios(tiendas));
    }

    @Test
    @DisplayName("la caja de la consulta cubre la tienda más el margen de 1,5 km")
    void cajaConMargen() {
        sepaTiene();
        service.conHorarios(List.of(tienda("coto", LAT, "x")));

        ArgumentCaptor<Double> minLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxLat = ArgumentCaptor.forClass(Double.class);
        verify(sucursalRepository).findInBoxDeComercios(minLat.capture(), maxLat.capture(), anyDouble(),
                anyDouble(), anyCollection());
        double margenKm = (maxLat.getValue() - minLat.getValue()) / 2 * 111.32;
        assertTrue(Math.abs(margenKm - 1.5) < 0.01, "margen " + margenKm);
    }
}
