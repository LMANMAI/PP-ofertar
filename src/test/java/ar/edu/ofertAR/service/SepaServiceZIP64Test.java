package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión del parsing ZIP64: el dataset SEPA incluye zips internos con
 * headers ZIP64 (centinela 0xFFFFFFFF) que rompen ZipInputStream. Este test
 * corre el parseo completo contra un zip real y se saltea si no está presente.
 * La ruta se configura con la property -Dsepa.test.zip=<ruta>.
 */
class SepaServiceZIP64Test {

    private static Path zipPath() {
        String fromProp = System.getProperty("sepa.test.zip", "");
        if (!fromProp.isBlank()) {
            return Path.of(fromProp);
        }
        return Path.of("C:/Users/Alx/Downloads/sepa_lunes.zip");
    }

    @Test
    @DisplayName("el dataset real (con zips internos ZIP64) se parsea sin romper")
    void parseaElDatasetReal() {
        Path zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(zip), "zip SEPA de prueba no presente: " + zip);

        SepaPreciosPageResponse res = newSepa(zip).getPrecios(null, null, null, null, 0, 1);

        assertTrue(res.totalElementos() > 0, "el dataset debería contener precios");
    }

    @Test
    @DisplayName("el EAN real (columna id_producto) matchea el filtro de búsqueda")
    void filtraPorEanReal() {
        Path zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(zip), "zip SEPA de prueba no presente: " + zip);

        // 7797942007022: EAN real presente en id_producto del dataset actual
        // (productos_ean es un flag 1/0). Con el fix debe devolver filas.
        SepaPreciosPageResponse res = newSepa(zip).getPrecios(null, null, null, "7797942007022", 0, 5);

        assertTrue(res.totalElementos() > 0, "debería encontrar el EAN real en id_producto");
    }

    private static SepaService newSepa(Path zip) {
        SepaService sepa = new SepaService();
        ReflectionTestUtils.setField(sepa, "resourceFileOverride", zip.toString());
        ReflectionTestUtils.setField(sepa, "resourceFechaOverride", "2026-08-31");
        return sepa;
    }
}