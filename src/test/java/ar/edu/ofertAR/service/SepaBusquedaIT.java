package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaProductoResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La búsqueda por palabras usa el índice FULLTEXT de V4; corre contra MySQL real. */
@SpringBootTest(properties = {
        "jwt.secret=clave-de-test-solo-para-pruebas-0123456789abcdef",
        "ocr.password=test"
})
class SepaBusquedaIT {

    @Autowired SepaCatalogoService catalogo;
    @Autowired JdbcTemplate jdbc;

    private void insertar(String ean, String desc, String marca, int ofertas) {
        jdbc.update("INSERT INTO sepa_producto (ean, descripcion, marca, precio_minimo, precio_maximo, "
                + "precio_promedio, cantidad_ofertas, fecha_dataset) VALUES (?, ?, ?, 10, 20, 15, ?, '2026-01-05')",
                ean, desc, marca, ofertas);
    }

    private List<String> eans(Page<SepaProductoResponse> p) {
        return p.getContent().stream().map(SepaProductoResponse::ean).toList();
    }

    @Test
    @DisplayName("busca por palabras (prefijo), ordena por ofertas y cubre 1-2 letras y término vacío")
    void busqueda() {
        insertar("9990000000011", "Zzleche entera larga vida", "Zzserenisima", 5);
        insertar("9990000000012", "Zzleche descremada", "Zzserenisima", 50);
        insertar("9990000000013", "Zzyogur natural", "Zzdanone", 500);

        // dos palabras: deben estar las dos
        assertEquals(List.of("9990000000012"), eans(catalogo.buscar("zzleche desc", 0, 10)));
        // una palabra que está en dos: orden por ofertas descendente
        assertEquals(List.of("9990000000012", "9990000000011"), eans(catalogo.buscar("zzleche", 0, 10)));
        // busca también en la marca, sin importar mayúsculas
        assertEquals(List.of("9990000000013"), eans(catalogo.buscar("ZZDANONE", 0, 10)));
        // sin coincidencias
        assertTrue(catalogo.buscar("zzinexistente", 0, 10).isEmpty());
        // los operadores del modo booleano no se interpretan (no debe tirar error de sintaxis)
        assertTrue(catalogo.buscar("+zzleche -zzleche* (", 0, 10).getTotalElements() >= 0);
        // término corto: cae al "contiene"
        assertTrue(eans(catalogo.buscar("zz", 0, 50)).contains("9990000000013"));
        // vacío: lista por ofertas
        List<String> todos = eans(catalogo.buscar("", 0, 200));
        assertTrue(todos.indexOf("9990000000013") < todos.indexOf("9990000000011"));
    }
}
