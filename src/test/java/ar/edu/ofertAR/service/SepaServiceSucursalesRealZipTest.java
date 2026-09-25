package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaPrecioResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lee sucursales y precios por sucursal de un dataset SEPA real. Se saltea si no hay
 * zip: la ruta va en la variable de entorno SEPA_TEST_ZIP o en -Dsepa.test.zip.
 * Filtra por comercio, así que corre en segundos aunque el zip pese cientos de MB.
 */
class SepaServiceSucursalesRealZipTest {

    private static Path zipPath() {
        String fromEnv = System.getenv("SEPA_TEST_ZIP");
        if (fromEnv != null && !fromEnv.isBlank()) return Path.of(fromEnv);
        String fromProp = System.getProperty("sepa.test.zip", "");
        return fromProp.isBlank() ? Path.of("/no-hay-zip-sepa") : Path.of(fromProp);
    }

    private static SepaService newSepa(Path zip) {
        SepaService sepa = new SepaService(new SepaComercioNombres());
        ReflectionTestUtils.setField(sepa, "resourceFileOverride", zip.toString());
        ReflectionTestUtils.setField(sepa, "resourceFechaOverride", "2026-09-18");
        return sepa;
    }

    @Test
    @DisplayName("COTO: las sucursales traen dirección y coordenadas, y sus precios se agrupan sin perder ninguno")
    void cotoSucursalesYGrupos() {
        Path zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(zip), "zip SEPA de prueba no presente: " + zip);

        List<SepaComercioSucursales> comercios = new ArrayList<>();
        Map<String, Integer> ids = new HashMap<>();
        SepaGruposComercio[] grupos = new SepaGruposComercio[1];
        AtomicInteger filas = new AtomicInteger();
        AtomicInteger sinSucursal = new AtomicInteger();

        newSepa(zip).scan(null, "12", null, null, p -> {
            BigDecimal precio = p.precioLista();
            if (p.ean() == null || precio == null || precio.signum() <= 0) return;
            filas.incrementAndGet();
            if (!ids.containsKey(SepaSucursalData.claveDe(p.banderaId(), p.sucursalId()))) sinSucursal.incrementAndGet();
            grupos[0].agregar(p.ean(), p.banderaId(), p.sucursalId(), precio);
        }, c -> {
            comercios.add(c);
            int next = 1;
            for (SepaSucursalData s : c.sucursales()) ids.putIfAbsent(s.clave(), next++);
            grupos[0] = new SepaGruposComercio(ids);
        });

        assertEquals(1, comercios.size());
        List<SepaSucursalData> sucursales = comercios.get(0).sucursales();
        assertTrue(sucursales.size() > 100, "COTO tiene más de 100 sucursales con coordenadas: " + sucursales.size());
        SepaSucursalData s = sucursales.get(0);
        assertEquals("12", s.comercioId());
        assertFalse(s.direccion().isBlank(), "la dirección se arma con calle y número");
        assertTrue(s.latitud() < -21 && s.latitud() > -56 && s.longitud() < -53 && s.longitud() > -74);
        assertNotNull(s.bandera(), "la bandera sale de comercio.csv");

        // Todo precio de una sucursal con ubicación queda en algún grupo.
        List<Integer> cantidades = new ArrayList<>();
        grupos[0].paraCadaGrupo(g -> cantidades.add(g.cantidad()));
        int enGrupos = cantidades.stream().mapToInt(Integer::intValue).sum();
        assertEquals(filas.get() - sinSucursal.get(), enGrupos);

        // Y el agrupado comprime de verdad: del orden de un millón de filas a decenas de miles.
        assertTrue(cantidades.size() < filas.get() / 5,
                "grupos=" + cantidades.size() + " filas=" + filas.get());
    }

    @Test
    @DisplayName("Carrefour: el BOM del comercio.csv ya no deja el id del comercio vacío, y las banderas se nombran")
    void carrefourConBom() {
        Path zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(zip), "zip SEPA de prueba no presente: " + zip);

        SepaPrecioResponse[] primera = new SepaPrecioResponse[1];
        List<SepaComercioSucursales> comercios = new ArrayList<>();

        newSepa(zip).scan(null, "10", null, "7790895000997", p -> {
            if (primera[0] == null) primera[0] = p;
        }, comercios::add);

        assertEquals(1, comercios.size());
        assertEquals("10", comercios.get(0).comercioId());
        assertNotNull(primera[0]);
        assertEquals("10", primera[0].comercioId());
        assertTrue(comercios.get(0).sucursales().stream().anyMatch(s -> "Maxi".equals(s.bandera())));
        assertTrue(comercios.get(0).sucursales().stream().anyMatch(s -> "Express".equals(s.bandera())));
    }
}
