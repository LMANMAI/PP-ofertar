package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaPrecioResponse;
import ar.edu.ofertAR.dto.response.SepaSyncResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * El sync arma el agregado por producto comercio a comercio en la staging (no en
 * un mapa en memoria). Corre contra MySQL real: verifica min, max, cantidad,
 * promedio y qué descripción queda, y que la tabla final no arrastre la columna auxiliar.
 */
@SpringBootTest(properties = {
        "jwt.secret=clave-de-test-solo-para-pruebas-0123456789abcdef",
        "ocr.password=test",
        "sepa.batch-pausa-ms=0"
})
class SepaSnapshotAgregadoIT {

    @Autowired SepaSnapshotService snapshot;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SepaService sepaService;

    private static SepaPrecioResponse fila(String comercio, String ean, String desc, String marca, String precio) {
        return SepaPrecioResponse.builder()
                .comercioId(comercio).comercioRazonSocial("Razon " + comercio).bandera("B" + comercio)
                .banderaId("1").sucursalId("1").ean(ean).descripcion(desc).marca(marca)
                .precioLista(new BigDecimal(precio)).build();
    }

    @Test
    @DisplayName("el agregado por producto sale igual que sumando todo el dataset junto")
    void agregadoPorProducto() {
        when(sepaService.resolverRecurso(any())).thenReturn(new SepaService.SepaResource("d", "2026-01-05", "u"));
        when(sepaService.scan(any(), isNull(), isNull(), isNull(), any(), any())).thenAnswer(inv -> {
            Consumer<SepaPrecioResponse> consumer = inv.getArgument(4);
            SepaService.ComercioListener listener = inv.getArgument(5);

            listener.onComercio(new SepaComercioSucursales("1", "Razon 1", List.of()));
            consumer.accept(fila("1", "111", "Leche", "M1", "100.10"));
            consumer.accept(fila("1", "111", "Leche otra", "M9", "200.20"));
            consumer.accept(fila("1", "222", "Pan", null, "50.00"));

            listener.onComercio(new SepaComercioSucursales("2", "Razon 2", List.of()));
            consumer.accept(fila("2", "111", "Leche B", "M2", "300.30"));
            consumer.accept(fila("2", "222", "Pan B", "M3", "70.00"));
            consumer.accept(fila("2", "333", "Sal", "M4", "10.00"));
            consumer.accept(fila("2", "333", "Sal", "M4", "0"));      // precio invalido: se ignora
            return new SepaService.SepaResource("d", "2026-01-05", "u");
        });

        SepaSyncResponse r = snapshot.sync(null);
        assertEquals(3, r.productosGuardados());

        Map<String, Object> leche = jdbc.queryForMap("SELECT * FROM sepa_producto WHERE ean = '111'");
        assertEquals(0, new BigDecimal("100.10").compareTo((BigDecimal) leche.get("precio_minimo")));
        assertEquals(0, new BigDecimal("300.30").compareTo((BigDecimal) leche.get("precio_maximo")));
        assertEquals(3, ((Number) leche.get("cantidad_ofertas")).intValue());
        assertEquals(0, new BigDecimal("200.20").compareTo((BigDecimal) leche.get("precio_promedio")));
        assertEquals("Leche", leche.get("descripcion"));      // gana la primera que llego
        assertEquals("M1", leche.get("marca"));

        Map<String, Object> pan = jdbc.queryForMap("SELECT * FROM sepa_producto WHERE ean = '222'");
        assertEquals("Pan", pan.get("descripcion"));
        assertEquals("M3", pan.get("marca"));                  // la primera fue null: completa la siguiente
        assertEquals(0, new BigDecimal("60.00").compareTo((BigDecimal) pan.get("precio_promedio")));

        // la tabla que quedo en produccion no arrastra la columna auxiliar
        Integer aux = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'sepa_producto' AND column_name = 'precio_suma'",
                Integer.class);
        assertEquals(0, aux);
        assertNull(jdbc.queryForObject("SELECT MAX(precio_minimo) FROM sepa_producto WHERE ean = 'no-existe'",
                BigDecimal.class));
    }
}
