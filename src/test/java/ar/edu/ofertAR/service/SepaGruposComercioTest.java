package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SepaGruposComercio: precios por sucursal agrupados")
class SepaGruposComercioTest {

    private static SepaGruposComercio conSucursales() {
        // banderaId:sucursalId -> id que le asignó la sincronización
        return new SepaGruposComercio(Map.of("1:10", 100, "1:11", 101, "1:12", 102, "2:10", 200));
    }

    private static List<SepaGruposComercio.Grupo> grupos(SepaGruposComercio g) {
        List<SepaGruposComercio.Grupo> out = new ArrayList<>();
        g.paraCadaGrupo(out::add);
        return out;
    }

    private static Set<String> ids(SepaGruposComercio.Grupo g) {
        return Set.of(g.sucursales().split(","));
    }

    @Test
    @DisplayName("sucursales con el mismo precio quedan en un solo grupo")
    void mismoPrecioUnGrupo() {
        SepaGruposComercio g = conSucursales();
        g.agregar("779", "1", "10", new BigDecimal("1500"));
        g.agregar("779", "1", "11", new BigDecimal("1500"));
        g.agregar("779", "1", "12", new BigDecimal("1800"));

        List<SepaGruposComercio.Grupo> todos = grupos(g);

        assertEquals(2, todos.size());
        SepaGruposComercio.Grupo barato = todos.stream()
                .filter(x -> x.precio().compareTo(new BigDecimal("1500")) == 0).findFirst().orElseThrow();
        assertEquals(2, barato.cantidad());
        assertEquals(Set.of("100", "101"), ids(barato));
    }

    @Test
    @DisplayName("1525 y 1525.00 son el mismo precio")
    void escalaNoDuplicaGrupos() {
        SepaGruposComercio g = conSucursales();
        g.agregar("779", "1", "10", new BigDecimal("1525"));
        g.agregar("779", "1", "11", new BigDecimal("1525.00"));

        assertEquals(1, grupos(g).size());
    }

    @Test
    @DisplayName("la misma sucursal en otra bandera es otra sucursal")
    void banderaDistingueSucursales() {
        SepaGruposComercio g = conSucursales();
        g.agregar("779", "1", "10", new BigDecimal("1500"));
        g.agregar("779", "2", "10", new BigDecimal("1500"));

        SepaGruposComercio.Grupo unico = grupos(g).get(0);

        assertEquals(Set.of("100", "200"), ids(unico));
    }

    @Test
    @DisplayName("una sucursal sin ubicación (no está en la lista) se ignora")
    void sucursalDesconocidaSeIgnora() {
        SepaGruposComercio g = conSucursales();
        g.agregar("779", "1", "999", new BigDecimal("1500"));

        assertTrue(g.estaVacio());
    }

    @Test
    @DisplayName("productos distintos no se mezclan y limpiar deja el agrupador vacío")
    void productosSeparadosYLimpiar() {
        SepaGruposComercio g = conSucursales();
        g.agregar("111", "1", "10", new BigDecimal("100"));
        g.agregar("222", "1", "10", new BigDecimal("100"));

        assertEquals(Set.of("111", "222"), grupos(g).stream().map(SepaGruposComercio.Grupo::ean)
                .collect(Collectors.toSet()));

        g.limpiar();
        assertTrue(g.estaVacio());
    }
}
