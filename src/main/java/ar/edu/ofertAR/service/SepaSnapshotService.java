package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaSyncResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshot semanal en DB: agrega el dataset SEPA por EAN (precio min/prom/max)
 * y reemplaza la tabla sepa_producto completa en cada sincronización.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SepaSnapshotService {

    private static final int BATCH_SIZE = 2000;

    private final SepaService sepaService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    /** Carga automática semanal (configurable con sepa.sync-cron). */
    @Scheduled(cron = "${sepa.sync-cron:0 0 3 * * MON}", zone = "America/Argentina/Buenos_Aires")
    public void scheduledSync() {
        try {
            SepaSyncResponse result = sync(null);
            log.info("SEPA sync programado OK: {}", result);
        } catch (Exception e) {
            log.error("SEPA sync programado falló", e);
        }
    }

    /**
     * Descarga -> descomprime -> agrega por EAN -> reemplaza el snapshot en DB.
     * Tarda varios minutos (dataset de cientos de MB).
     */
    public SepaSyncResponse sync(String dia) {
        if (!syncing.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una sincronización SEPA en curso");
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Agg> byEan = new HashMap<>(1_048_576);
            AtomicLong rows = new AtomicLong();

            SepaService.SepaResource resource = sepaService.scan(dia, null, null, null, p -> {
                rows.incrementAndGet();
                String ean = p.ean();
                BigDecimal precio = p.precioLista();
                if (ean == null || ean.isBlank() || precio == null
                        || precio.signum() <= 0) {
                    return;
                }
                byEan.computeIfAbsent(ean, k -> new Agg(p.descripcion(), p.marca())).add(precio);
            });

            LocalDate fecha = parseFecha(resource.fecha());
            replaceSnapshot(byEan, fecha);

            long seconds = (System.currentTimeMillis() - start) / 1000;
            log.info("SEPA snapshot: {} filas -> {} productos en {}s",
                    rows.get(), byEan.size(), seconds);
            return SepaSyncResponse.builder()
                    .dia(resource.dia())
                    .fechaDataset(fecha.toString())
                    .filasProcesadas(rows.get())
                    .productosGuardados(byEan.size())
                    .duracionSegundos(seconds)
                    .build();
        } finally {
            syncing.set(false);
        }
    }

    /** Reemplazo transaccional: DELETE total + batch insert. */
    private void replaceSnapshot(Map<String, Agg> byEan, LocalDate fecha) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM sepa_producto");

            String sql = "INSERT INTO sepa_producto "
                    + "(ean, descripcion, marca, precio_minimo, precio_promedio, precio_maximo, "
                    + "cantidad_ofertas, fecha_dataset) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
            for (Map.Entry<String, Agg> e : byEan.entrySet()) {
                Agg a = e.getValue();
                batch.add(new Object[]{
                        truncate(e.getKey(), 20),
                        truncate(a.descripcion, 500),
                        truncate(a.marca, 255),
                        a.min,
                        a.avg(),
                        a.max,
                        a.count,
                        Date.valueOf(fecha)
                });
                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
        });
    }

    private LocalDate parseFecha(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (DateTimeParseException e) {
            return LocalDate.now();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final class Agg {
        final String descripcion;
        final String marca;
        BigDecimal min;
        BigDecimal max;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;

        Agg(String descripcion, String marca) {
            this.descripcion = descripcion;
            this.marca = marca;
        }

        void add(BigDecimal precio) {
            if (min == null || precio.compareTo(min) < 0) min = precio;
            if (max == null || precio.compareTo(max) > 0) max = precio;
            sum = sum.add(precio);
            count++;
        }

        BigDecimal avg() {
            if (count == 0) return null;
            return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
    }
}
