package ar.edu.ofertAR.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * El contrato de la API vive commiteado en openapi.json (el frontend genera sus tipos
 * de ahí). Este test lo regenera y falla si el archivo quedó desactualizado.
 *
 * Para actualizarlo tras cambiar un DTO o un endpoint:
 *   ./gradlew test --tests '*OpenApiExportTest' -Dopenapi.update=true
 */
@SpringBootTest(properties = {
        "jwt.secret=clave-de-test-solo-para-pruebas-0123456789abcdef",
        "ocr.password=test",
        "springdoc.api-docs.enabled=true"
})
@AutoConfigureMockMvc
class OpenApiExportTest {

    private static final Path ARCHIVO = Path.of("openapi.json");

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("openapi.json está al día con los controllers y DTO")
    void contratoAlDia() throws Exception {
        String crudo = mvc.perform(get("/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode arbol = mapper.readTree(crudo);
        assertNotNull(arbol.get("paths"), "springdoc no devolvió el contrato");
        ((ObjectNode) arbol).remove("servers"); // depende de dónde corra
        // Con ORDER_MAP_ENTRIES_BY_KEYS solo ordena Maps: pasar por Object para ordenar también los objetos JSON.
        String generado = mapper.writeValueAsString(mapper.convertValue(arbol, Object.class)) + "\n";

        if (Boolean.getBoolean("openapi.update") || !Files.exists(ARCHIVO)) {
            Files.writeString(ARCHIVO, generado, StandardCharsets.UTF_8);
            return;
        }
        String actual = Files.readString(ARCHIVO, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(actual, generado,
                "openapi.json está desactualizado. Regeneralo con: ./gradlew test --tests '*OpenApiExportTest' -Dopenapi.update=true");
    }
}
