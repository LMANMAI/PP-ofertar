package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SucursalPrecioResponse;
import ar.edu.ofertAR.model.SepaPrecioGrupo;
import ar.edu.ofertAR.model.SepaSucursal;
import ar.edu.ofertAR.repository.SepaPrecioGrupoRepository;
import ar.edu.ofertAR.repository.SepaSucursalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SepaSucursalPreciosService: la sucursal más barata cerca, por cadena")
class SepaSucursalPreciosServiceTest {

    @Mock private SepaSucursalRepository sucursalRepository;
    @Mock private SepaPrecioGrupoRepository grupoRepository;

    private SepaSucursalPreciosService service;

    // Plaza de Mayo: el punto de búsqueda de los tests.
    private static final double LAT = -34.6083;
    private static final double LNG = -58.3712;

    @BeforeEach
    void setUp() {
        service = new SepaSucursalPreciosService(sucursalRepository, grupoRepository);
    }

    private static SepaSucursal suc(long id, String comercio, String bandera, String banderaId, double lat, double lng) {
        return SepaSucursal.builder().id(id).comercioId(comercio).banderaId(banderaId).sucursalId("s" + id)
                .bandera(bandera).nombre("Sucursal " + id).tipo("Supermercado").direccion("Calle " + id + " 100")
                .localidad("CABA").provincia("AR-C").latitud(lat).longitud(lng).build();
    }

    private static SepaPrecioGrupo grupo(String comercio, String precio, String ids) {
        return SepaPrecioGrupo.builder().ean("779").comercioId(comercio).precio(new BigDecimal(precio))
                .cantidadSucursales(ids.split(",").length).sucursales(ids).build();
    }

    private static Map<Long, SepaSucursalPreciosService.Cercana> cercanas(SepaSucursal... sucursales) {
        Map<Long, SepaSucursalPreciosService.Cercana> out = new HashMap<>();
        for (SepaSucursal s : sucursales) {
            out.put(s.getId(), new SepaSucursalPreciosService.Cercana(s,
                    SepaSucursalPreciosService.distanciaKm(LAT, LNG, s.getLatitud(), s.getLongitud())));
        }
        return out;
    }

    @Test
    @DisplayName("de cada cadena elige la sucursal cercana de menor precio, aunque no sea la más cerca")
    void eligeElPrecioMasBajoDentroDeLaCadena() {
        SepaSucursal cerca = suc(1, "12", "COTO", "1", -34.6100, -58.3700);   // ~0,2 km
        SepaSucursal lejos = suc(2, "12", "COTO", "1", -34.6300, -58.3900);   // ~3 km
        var grupos = List.of(
                grupo("12", "5000", "2"),      // la lejana es más barata
                grupo("12", "5600", "1"));

        List<SucursalPrecioResponse> r = SepaSucursalPreciosService.elegir(cercanas(cerca, lejos), grupos);

        assertEquals(1, r.size());
        assertEquals(2L, r.get(0).sucursalId());
        assertEquals(0, new BigDecimal("5000").compareTo(r.get(0).precio()));
    }

    @Test
    @DisplayName("con igual precio en varias sucursales, gana la más cercana")
    void empateGanaLaMasCercana() {
        SepaSucursal a = suc(1, "12", "COTO", "1", -34.6300, -58.3900);
        SepaSucursal b = suc(2, "12", "COTO", "1", -34.6100, -58.3700);
        var grupos = List.of(grupo("12", "5000", "1,2"));

        List<SucursalPrecioResponse> r = SepaSucursalPreciosService.elegir(cercanas(a, b), grupos);

        assertEquals(2L, r.get(0).sucursalId());
    }

    @Test
    @DisplayName("Jumbo y Disco del mismo comercio son cadenas distintas")
    void banderasDelMismoComercioPorSeparado() {
        SepaSucursal jumbo = suc(1, "9", "Jumbo", "3", -34.6100, -58.3700);
        SepaSucursal disco = suc(2, "9", "Disco", "2", -34.6110, -58.3710);
        var grupos = List.of(grupo("9", "4000", "1"), grupo("9", "4200", "2"));

        List<SucursalPrecioResponse> r = SepaSucursalPreciosService.elegir(cercanas(jumbo, disco), grupos);

        assertEquals(2, r.size());
        assertEquals("Jumbo", r.get(0).bandera());
        assertEquals("Disco", r.get(1).bandera());
    }

    @Test
    @DisplayName("el resultado sale de menor a mayor precio, una por cadena")
    void ordenadoPorPrecio() {
        SepaSucursal coto = suc(1, "12", "COTO", "1", -34.6100, -58.3700);
        SepaSucursal dia = suc(2, "15", "DIA", "1", -34.6120, -58.3720);
        var grupos = List.of(grupo("15", "4800", "2"), grupo("12", "5600", "1"), grupo("12", "5900", "1"));

        List<SucursalPrecioResponse> r = SepaSucursalPreciosService.elegir(cercanas(coto, dia), grupos.stream()
                .sorted(Comparator.comparing(SepaPrecioGrupo::getPrecio)).toList());

        assertEquals(List.of("DIA", "COTO"), r.stream().map(SucursalPrecioResponse::bandera).toList());
    }

    @Test
    @DisplayName("una sucursal que no está cerca no aporta su precio")
    void ignoraLasQueNoEstanCerca() {
        SepaSucursal cerca = suc(1, "12", "COTO", "1", -34.6100, -58.3700);
        var grupos = List.of(grupo("12", "3000", "99"), grupo("12", "5600", "1"));

        List<SucursalPrecioResponse> r = SepaSucursalPreciosService.elegir(cercanas(cerca), grupos);

        assertEquals(0, new BigDecimal("5600").compareTo(r.get(0).precio()));
    }

    @Test
    @DisplayName("el radio recorta las sucursales de la caja: 3 km sí, 40 km no")
    void elRadioRecortaLaCaja() {
        SepaSucursal cerca = suc(1, "12", "COTO", "1", -34.6300, -58.3900);
        SepaSucursal lejos = suc(2, "12", "COTO", "1", -34.9000, -58.6000);
        when(sucursalRepository.findInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(cerca, lejos));

        var r = service.sucursalesCercanas(LAT, LNG, 5.0);

        assertTrue(r.containsKey(1L));
        assertFalse(r.containsKey(2L));
    }

    @Test
    @DisplayName("sin sucursales en el radio no consulta precios")
    void sinSucursalesNoConsultaPrecios() {
        when(sucursalRepository.findInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());

        assertTrue(service.masBaratasCerca(List.of("779"), LAT, LNG, 5).isEmpty());
        verify(grupoRepository, never()).findByEanAndComercioIdInOrderByPrecioAsc(eq("779"), anyCollection());
    }

    @Test
    @DisplayName("prueba el EAN tal cual y normalizado hasta encontrar precios")
    void pruebaLasVariantesDelEan() {
        SepaSucursal cerca = suc(1, "12", "COTO", "1", -34.6100, -58.3700);
        when(sucursalRepository.findInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(cerca));
        when(grupoRepository.findByEanAndComercioIdInOrderByPrecioAsc(eq("0779"), anyCollection())).thenReturn(List.of());
        when(grupoRepository.findByEanAndComercioIdInOrderByPrecioAsc(eq("779"), anyCollection()))
                .thenReturn(List.of(grupo("12", "5600", "1")));

        var r = service.masBaratasCerca(List.of("0779", "779"), LAT, LNG, 5);

        assertEquals(1, r.size());
    }

    @Test
    @DisplayName("la distancia entre dos puntos conocidos es la esperada (Obelisco a Congreso, cerca de 1,3 km)")
    void haversine() {
        double d = SepaSucursalPreciosService.distanciaKm(-34.6037, -58.3816, -34.6099, -58.3927);

        assertTrue(d > 1.0 && d < 1.7, "distancia inesperada: " + d);
    }
}
