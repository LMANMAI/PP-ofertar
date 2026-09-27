package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * El sync programado se saltea cuando el dataset disponible ya está cargado.
 *
 * <p>Con la carpeta vigilada el cron corre cada hora, para tomar el zip apenas
 * lo deja el relay. Sin este chequeo reimportaría los mismos ~300 MB cada hora;
 * con uno demasiado agresivo, la app se quedaría con precios viejos sin que
 * nadie se entere. Por eso ante cualquier duda se importa.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SepaSnapshotService: el cron no reimporta lo que ya está cargado")
class SepaSnapshotServiceYaCargadoTest {

    @Mock private SepaService sepaService;
    @Mock private JdbcTemplate jdbcTemplate;

    private SepaSnapshotService service() {
        return new SepaSnapshotService(sepaService, jdbcTemplate);
    }

    private void disponible(String fecha) {
        when(sepaService.resolverRecurso(null)).thenReturn(new SepaService.SepaResource("martes", fecha, "file:///sepa.zip"));
    }

    private void cargado(LocalDate fecha) {
        when(jdbcTemplate.queryForObject(anyString(), eq(LocalDate.class))).thenReturn(fecha);
    }

    @Test
    @DisplayName("misma fecha que la cargada: se saltea")
    void mismaFecha() {
        disponible("2026-09-15");
        cargado(LocalDate.of(2026, 9, 15));
        assertTrue(service().datasetYaCargado());
    }

    @Test
    @DisplayName("una fecha más nueva: se importa")
    void masNueva() {
        disponible("2026-09-16");
        cargado(LocalDate.of(2026, 9, 15));
        assertFalse(service().datasetYaCargado());
    }

    @Test
    @DisplayName("una fecha más vieja que la cargada no pisa lo que hay")
    void masVieja() {
        disponible("2026-09-08");
        cargado(LocalDate.of(2026, 9, 15));
        assertTrue(service().datasetYaCargado());
    }

    @Test
    @DisplayName("tabla vacía: se importa")
    void tablaVacia() {
        disponible("2026-09-15");
        cargado(null);
        assertFalse(service().datasetYaCargado());
    }

    @Test
    @DisplayName("tabla inexistente o base caída: se importa, no se asume nada")
    void errorDeBase() {
        disponible("2026-09-15");
        when(jdbcTemplate.queryForObject(anyString(), eq(LocalDate.class)))
                .thenThrow(new BadSqlGrammarException("max", "SELECT MAX(fecha_dataset) FROM sepa_producto",
                        new SQLException("Table 'sepa_producto' doesn't exist")));
        assertFalse(service().datasetYaCargado());

        reset(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("sin conexión"));
        assertFalse(service().datasetYaCargado());
    }

    @Test
    @DisplayName("fecha ilegible del recurso: se importa")
    void fechaIlegible() {
        disponible("0000-00-00");
        assertFalse(service().datasetYaCargado());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("la fecha cargada sale de sepa_producto")
    void consultaSepaProducto() {
        disponible("2026-09-15");
        cargado(LocalDate.of(2026, 9, 15));
        service().datasetYaCargado();
        verify(jdbcTemplate).queryForObject("SELECT MAX(fecha_dataset) FROM sepa_producto", LocalDate.class);
    }

    @Test
    @DisplayName("cron con el dataset ya cargado: no arranca ningún sync")
    void cronSeSaltea() {
        disponible("2026-09-15");
        cargado(LocalDate.of(2026, 9, 15));
        SepaSnapshotService service = service();

        service.scheduledSync();

        verify(sepaService, never()).scan(any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate, never()).execute(anyString());
        assertNotEquals(ar.edu.ofertAR.dto.response.SepaSyncEstadoResponse.Estado.EN_CURSO, service.getEstado().estado());
    }

    @Test
    @DisplayName("cron con un dataset nuevo: arranca el sync")
    void cronImportaLoNuevo() {
        disponible("2026-09-16");
        cargado(LocalDate.of(2026, 9, 15));

        service().scheduledSync();

        // El sync corre en su propio hilo: se espera a que llegue a escanear.
        verify(sepaService, timeout(5000)).scan(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("si no hay zip en la carpeta, el cron avisa y no revienta")
    void sinZipNoRevienta() {
        when(sepaService.resolverRecurso(null)).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No hay ningún zip SEPA válido en SEPA_RESOURCE_DIR (¿corrió el relay?)"));

        assertDoesNotThrow(() -> service().scheduledSync());
        verify(sepaService, never()).scan(any(), any(), any(), any(), any(), any());
    }
}
