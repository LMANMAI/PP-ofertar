package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import ar.edu.ofertAR.service.horario.HorarioParser;
import ar.edu.ofertAR.service.horario.HorariosJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El sync semanal reemplaza sepa_sucursal entera (staging + RENAME), así que es él el
 * que lleva los horarios a TODAS las sucursales, también a las que ya estaban
 * guardadas. Si el INSERT no los incluyera, la columna quedaría en null para siempre.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SepaSnapshotService: el sync guarda el horario de cada sucursal")
class SepaSnapshotServiceHorariosTest {

    @Mock private SepaService sepaService;
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("cada sucursal va a la staging con su horario en JSON, o null si no lo informa")
    void insertaElHorarioComoJson() {
        HorariosResponse coto = HorarioParser.parsearSemana(Collections.nCopies(7, "8:30 a 22:00"));
        var comercio = new SepaComercioSucursales("12", "COTO CICSA", List.of(
                new SepaSucursalData("12", "1", "82", "COTO", "MURILLO", "Supermercado", "Murillo 551",
                        "Villa Crespo", "AR-C", -34.6016811, -58.4424857, coto),
                new SepaSucursalData("12", "1", "83", "COTO", "VILLA LURO", "Supermercado", "Av. Rivadavia 9840",
                        "Villa Luro", "AR-C", -34.63, -58.50, null)));
        var recurso = new SepaService.SepaResource("martes", "2026-09-15", "file:///sepa.zip");
        when(sepaService.resolverRecurso(null)).thenReturn(recurso);
        when(sepaService.scan(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            inv.<SepaService.ComercioListener>getArgument(5).onComercio(comercio);
            return recurso;
        });
        // insertarLote vacía el lote después de mandarlo: se copia lo que llega a la base.
        List<String> sqls = new ArrayList<>();
        List<Object[]> filas = new ArrayList<>();
        when(jdbcTemplate.batchUpdate(startsWith("INSERT INTO sepa_sucursal_staging"), anyList()))
                .thenAnswer(inv -> {
                    sqls.add(inv.getArgument(0));
                    filas.addAll(inv.<List<Object[]>>getArgument(1));
                    return new int[0];
                });

        SepaSnapshotService service = new SepaSnapshotService(sepaService, jdbcTemplate);
        ReflectionTestUtils.setField(service, "batchSize", 1000);
        service.sync(null);

        assertEquals(1, sqls.size());
        assertTrue(sqls.get(0).contains("latitud, longitud, horarios)"), sqls.get(0));
        assertEquals(2, filas.size());
        assertEquals(13, sqls.get(0).chars().filter(c -> c == '?').count());
        assertEquals(13, filas.get(0).length, "un valor por cada ? del INSERT");
        assertEquals(HorariosJson.escribir(coto), filas.get(0)[12]);
        assertNull(filas.get(1)[12]);
        // La staging se crea copiando la tabla viva, que ya tiene la columna, y se publica con el RENAME.
        verify(jdbcTemplate).execute("CREATE TABLE sepa_sucursal_staging LIKE sepa_sucursal");
        verify(jdbcTemplate).execute(startsWith("RENAME TABLE "));
    }
}
