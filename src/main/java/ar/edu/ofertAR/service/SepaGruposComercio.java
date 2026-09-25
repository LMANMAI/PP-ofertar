package ar.edu.ofertAR.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agrupa, para UN comercio, las sucursales que cobran cada precio de cada EAN.
 *
 * <p>Ver {@link ar.edu.ofertAR.model.SepaPrecioGrupo} por qué se agrupa en vez de
 * guardar una fila por sucursal. La memoria queda acotada a un comercio a la vez,
 * igual que el desglose por comercio: un entero por fila de producto (unos 16 MB
 * para los 4 millones de filas de DIA) más un objeto por grupo.
 *
 * <p>Las sucursales se citan por el id que la sincronización les asignó. Una fila
 * de una sucursal que no está en la lista (sin coordenadas válidas) se ignora:
 * sin ubicación no se puede ofrecer como cercana.
 */
final class SepaGruposComercio {

    /** Un precio de un producto y las sucursales que lo aplican. */
    record Grupo(String ean, BigDecimal precio, int cantidad, String sucursales) {
    }

    private final Map<String, Integer> idsPorClave;
    private final Map<String, Map<BigDecimal, Ids>> porEan = new HashMap<>();

    SepaGruposComercio(Map<String, Integer> idsPorClave) {
        this.idsPorClave = idsPorClave;
    }

    void agregar(String ean, String banderaId, String sucursalId, BigDecimal precio) {
        Integer id = idsPorClave.get(SepaSucursalData.claveDe(banderaId, sucursalId));
        if (id == null) {
            return;
        }
        // Sin normalizar la escala, 1525 y 1525.00 serían dos precios distintos.
        BigDecimal clave = precio.setScale(2, RoundingMode.HALF_UP);
        porEan.computeIfAbsent(ean, k -> new HashMap<>())
                .computeIfAbsent(clave, k -> new Ids())
                .agregar(id);
    }

    boolean estaVacio() {
        return porEan.isEmpty();
    }

    void paraCadaGrupo(Consumer<Grupo> consumer) {
        for (Map.Entry<String, Map<BigDecimal, Ids>> porPrecio : porEan.entrySet()) {
            for (Map.Entry<BigDecimal, Ids> e : porPrecio.getValue().entrySet()) {
                Ids ids = e.getValue();
                consumer.accept(new Grupo(porPrecio.getKey(), e.getKey(), ids.size, ids.unidos()));
            }
        }
    }

    void limpiar() {
        porEan.clear();
    }

    /** Lista de enteros que crece sin encajonarlos. */
    private static final class Ids {
        private int[] datos = new int[4];
        private int size = 0;

        void agregar(int id) {
            if (size == datos.length) {
                datos = Arrays.copyOf(datos, size * 2);
            }
            datos[size++] = id;
        }

        String unidos() {
            StringBuilder sb = new StringBuilder(size * 5);
            for (int i = 0; i < size; i++) {
                if (i > 0) sb.append(',');
                sb.append(datos[i]);
            }
            return sb.toString();
        }
    }
}
