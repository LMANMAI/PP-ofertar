package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.HorariosResponse;
import ar.edu.ofertAR.dto.response.SepaSucursalesCercanasResponse;
import ar.edu.ofertAR.dto.response.SucursalPrecioResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El JSON que recibe la app, serializado con el ObjectMapper de Spring (el mismo que
 * usan los controllers). El frontend depende de estas claves y formatos exactos.
 */
@JsonTest
@DisplayName("Contrato JSON de horarios")
class HorariosContratoJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    /** El ejemplo del contrato, armado desde celdas de SEPA. */
    private static HorariosResponse ejemplo() {
        return HorarioParser.parsearSemana(List.of(
                "08:00 a 22:00",
                "08:30 a 13:00 y 16:00 a 20:30",
                "cerrado",
                "",
                "00:00 a 24:00",
                "18:00 a 02:00",
                "Cerrado"));
    }

    private static final String CONTRATO = "{"
            + "\"lunes\":[{\"desde\":\"08:00\",\"hasta\":\"22:00\"}],"
            + "\"martes\":[{\"desde\":\"08:30\",\"hasta\":\"13:00\"},{\"desde\":\"16:00\",\"hasta\":\"20:30\"}],"
            + "\"miercoles\":[],"
            + "\"jueves\":null,"
            + "\"viernes\":[{\"desde\":\"00:00\",\"hasta\":\"24:00\"}],"
            + "\"sabado\":[{\"desde\":\"18:00\",\"hasta\":\"02:00\"}],"
            + "\"domingo\":[]"
            + "}";

    @Test
    @DisplayName("siete claves sin tilde, null y [] distintos, horas HH:mm")
    void formaExacta() throws Exception {
        assertEquals(CONTRATO, objectMapper.writeValueAsString(ejemplo()));
    }

    @Test
    @DisplayName("en /sepa/productos/{ean}/sucursales cada sucursal lleva horarios")
    void enSucursalesCercanas() throws Exception {
        SucursalPrecioResponse conHorario = new SucursalPrecioResponse("12", "1", "COTO", 7L, "MURILLO",
                "Supermercado", "Murillo 551", "Villa Crespo", "AR-C", -34.60, -58.44, 0.6,
                new BigDecimal("5600"), ejemplo());
        SucursalPrecioResponse sinHorario = new SucursalPrecioResponse("10", "3", "Express", 8L, "Cuba",
                "Supermercado", "Cuba 2776", "CABA", "AR-C", -34.56, -58.45, 1.2,
                new BigDecimal("5700"), null);
        var respuesta = new SepaSucursalesCercanasResponse("7790895000997", 5, LocalDate.of(2026, 9, 15),
                List.of(conHorario, sinHorario));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(respuesta));

        assertEquals(objectMapper.readTree(CONTRATO), json.at("/sucursales/0/horarios"));
        assertTrue(json.at("/sucursales/1").has("horarios"));
        assertTrue(json.at("/sucursales/1/horarios").isNull(), "sin datos: horarios null");
    }

    @Test
    @DisplayName("en /stores/nearby el item del scraper suma horarios sin perder sus campos")
    void enTiendasCercanas() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chainSlug", "coto");
        item.put("externalId", "coto-82");
        item.put("lat", -34.6016811);
        item.put("lng", -58.4424857);
        item.put("horarios", ejemplo());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(List.of(item)));

        assertEquals(objectMapper.readTree(CONTRATO), json.at("/0/horarios"));
        assertEquals("coto-82", json.at("/0/externalId").asText());
    }

    @Test
    @DisplayName("lo guardado en sepa_sucursal.horarios es el mismo JSON y vuelve igual")
    void idaYVuelta() {
        String guardado = HorariosJson.escribir(ejemplo());

        assertEquals(CONTRATO, guardado);
        assertEquals(ejemplo(), HorariosJson.leer(guardado));
        assertEquals(null, HorariosJson.leer(null));
        assertEquals(null, HorariosJson.leer("{roto"), "un JSON ilegible es un horario desconocido");
    }
}
