package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SepaServiceOverrideTest {

    @Test
    @DisplayName("archivo local seteado resuelve el recurso sin tocar CKAN")
    void archivoLocalResuelveSinCkan() {
        SepaService sepa = new SepaService();
        String overridePath = Path.of("/sepa/sepa_lunes.zip").toString();
        ReflectionTestUtils.setField(sepa, "resourceFileOverride", overridePath);
        ReflectionTestUtils.setField(sepa, "resourceFechaOverride", "2026-08-31");

        SepaService.SepaResource recurso = sepa.resolverRecurso(null);

        assertEquals("2026-08-31", recurso.fecha());
        assertEquals(Path.of(overridePath).toUri().toString(), recurso.url());
    }

    @Test
    @DisplayName("la fecha se extrae del nombre del archivo cuando trae YYYY-MM-DD")
    void fechaSeExtraeDelNombre() {
        SepaService sepa = new SepaService();
        ReflectionTestUtils.setField(sepa, "resourceFileOverride", "/sepa/sepa_2026-08-31.zip");

        SepaService.SepaResource recurso = sepa.resolverRecurso(null);

        assertEquals("2026-08-31", recurso.fecha());
    }

    @Test
    @DisplayName("sin fecha configurable ni en el nombre, falla con error claro")
    void sinFechaLanzaErrorClaro() {
        SepaService sepa = new SepaService();
        ReflectionTestUtils.setField(sepa, "resourceFileOverride", "/sepa/sepa_lunes.zip");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> sepa.resolverRecurso(null));

        assertTrue(ex.getReason().contains("SEPA_RESOURCE_FECHA"));
    }

    @Test
    @DisplayName("una URL http como espejo se respeta tal cual")
    void urlHttpComoEspejo() {
        SepaService sepa = new SepaService();
        ReflectionTestUtils.setField(sepa, "resourceUrlOverride",
                "https://mirror.example/sepa_2026-08-31.zip");

        SepaService.SepaResource recurso = sepa.resolverRecurso(null);

        assertEquals("https://mirror.example/sepa_2026-08-31.zip", recurso.url());
        assertEquals("2026-08-31", recurso.fecha());
    }
}