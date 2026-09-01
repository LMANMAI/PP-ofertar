package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaSyncEstadoResponse;
import ar.edu.ofertAR.dto.response.SepaSyncResponse;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshot semanal en DB: agrega el dataset SEPA por EAN (precio min/prom/max).
 *
 * <p><b>Estrategia de escritura (blue/green).</b> La versión anterior hacía
 * {@code DELETE FROM sepa_producto} + cientos de miles de INSERT dentro de UNA
 * transacción. Eso produce exactamente el pico que queremos evitar: un undo log
 * enorme, la tabla bloqueada durante minutos, un solo commit gigante al final y
 * cero tolerancia a fallas (si explota en el 90%, se pierde todo y la tabla
 * queda vacía para los usuarios).
 *
 * <p>Ahora se carga en una tabla de staging, con commits por lote y una pausa
 * configurable entre lotes, y recién al final se hace un {@code RENAME TABLE}
 * atómico. La tabla que leen los usuarios queda intacta y completa durante toda
 * la carga, y el cambio se ve en un instante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SepaSnapshotService {

    private static final String TABLA = "sepa_producto";
    private static final String TABLA_STAGING = "sepa_producto_staging";
    private static final String TABLA_VIEJA = "sepa_producto_old";

    private static final String TABLA_COM = "sepa_precio_comercio";
    private static final String TABLA_COM_STAGING = "sepa_precio_comercio_staging";
    private static final String TABLA_COM_VIEJA = "sepa_precio_comercio_old";

    private static final String INSERT_COMERCIO = "INSERT INTO " + TABLA_COM_STAGING
            + " (ean, comercio_id, bandera, razon_social, precio_minimo, precio_maximo, "
            + "cantidad_sucursales, fecha_dataset) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final SepaService sepaService;
    private final JdbcTemplate jdbcTemplate;

    /** Filas por INSERT batch. Con rewriteBatchedStatements viaja como un solo statement. */
    @Value("${sepa.batch-size:1000}")
    private int batchSize;

    /**
     * Pausa entre lotes. Es el dial para no comerse la IO de la base:
     * subilo si el sync compite con tráfico de usuarios, bajalo a 0 en local.
     */
    @Value("${sepa.batch-pausa-ms:50}")
    private long batchPausaMs;

    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicLong filasProcesadas = new AtomicLong();
    private final AtomicLong productosInsertados = new AtomicLong();
    private final AtomicLong preciosComercioInsertados = new AtomicLong();
    private final AtomicReference<SepaSyncEstadoResponse> estado = new AtomicReference<>(
            SepaSyncEstadoResponse.builder().estado(SepaSyncEstadoResponse.Estado.IDLE).build());

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sepa-sync");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ── API pública ──────────────────────────────────────────────────

    /** Carga automática semanal (configurable con sepa.sync-cron). */
    @Scheduled(cron = "${sepa.sync-cron:0 0 3 * * MON}", zone = "America/Argentina/Buenos_Aires")
    public void scheduledSync() {
        try {
            lanzarAsync(null);
        } catch (ResponseStatusException e) {
            log.warn("SEPA sync programado salteado: {}", e.getReason());
        }
    }

    /**
     * Dispara el sync en background y vuelve enseguida.
     * El progreso se consulta con {@link #getEstado()}.
     */
    public SepaSyncEstadoResponse lanzarAsync(String dia) {
        if (!syncing.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una sincronización SEPA en curso");
        }
        filasProcesadas.set(0);
        productosInsertados.set(0);
        estado.set(SepaSyncEstadoResponse.builder()
                .estado(SepaSyncEstadoResponse.Estado.EN_CURSO)
                .dia(dia)
                .inicio(LocalDateTime.now().toString())
                .build());

        executor.submit(() -> {
            String inicio = estado.get().inicio();
            try {
                SepaSyncResponse resultado = ejecutarSync(dia);
                estado.set(SepaSyncEstadoResponse.builder()
                        .estado(SepaSyncEstadoResponse.Estado.OK)
                        .dia(resultado.dia())
                        .fechaDataset(resultado.fechaDataset())
                        .inicio(inicio)
                        .fin(LocalDateTime.now().toString())
                        .filasProcesadas(resultado.filasProcesadas())
                        .productosGuardados(resultado.productosGuardados())
                        .productosInsertados(resultado.productosGuardados())
                        .duracionSegundos(resultado.duracionSegundos())
                        .build());
                log.info("SEPA sync OK: {}", resultado);
            } catch (Exception e) {
                log.error("SEPA sync falló", e);
                estado.set(SepaSyncEstadoResponse.builder()
                        .estado(SepaSyncEstadoResponse.Estado.ERROR)
                        .dia(dia)
                        .inicio(inicio)
                        .fin(LocalDateTime.now().toString())
                        .filasProcesadas(filasProcesadas.get())
                        .productosInsertados(productosInsertados.get())
                        .error(e.getMessage())
                        .build());
            } finally {
                syncing.set(false);
            }
        });

        return getEstado();
    }

    /** Estado actual, con contadores en vivo mientras corre. */
    public SepaSyncEstadoResponse getEstado() {
        SepaSyncEstadoResponse actual = estado.get();
        if (actual.estado() != SepaSyncEstadoResponse.Estado.EN_CURSO) {
            return actual;
        }
        return SepaSyncEstadoResponse.builder()
                .estado(actual.estado())
                .dia(actual.dia())
                .inicio(actual.inicio())
                .filasProcesadas(filasProcesadas.get())
                .productosInsertados(productosInsertados.get())
                .build();
    }

    /**
     * Sync sincrónico completo. Público para tests y para el uso por CLI;
     * el camino normal es {@link #lanzarAsync(String)}.
     */
    public SepaSyncResponse sync(String dia) {
        if (!syncing.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una sincronización SEPA en curso");
        }
        try {
            return ejecutarSync(dia);
        } finally {
            syncing.set(false);
        }
    }

    // ── Implementación ───────────────────────────────────────────────

    private SepaSyncResponse ejecutarSync(String dia) {
        long start = System.currentTimeMillis();
        filasProcesadas.set(0);
        productosInsertados.set(0);
        preciosComercioInsertados.set(0);

        // La fecha del dataset se necesita antes de empezar a volcar filas,
        // porque va en cada INSERT del desglose por comercio.
        LocalDate fecha = parseFecha(sepaService.resolverRecurso(dia).fecha());

        crearStaging();

        Map<String, Agg> byEan = new HashMap<>(1 << 19);
        AcumuladorComercio acumulador = new AcumuladorComercio(fecha);

        SepaService.SepaResource resource = sepaService.scan(dia, null, null, null, p -> {
            filasProcesadas.incrementAndGet();
            String ean = p.ean();
            BigDecimal precio = p.precioLista();
            if (ean == null || ean.isBlank() || precio == null || precio.signum() <= 0) {
                return;
            }
            byEan.computeIfAbsent(ean, k -> new Agg(p.descripcion(), p.marca())).add(precio);
            acumulador.acumular(p, precio);
        });

        acumulador.volcar();   // el último comercio del dataset
        cargarProductos(byEan, fecha);
        reconstruirIndices();
        swap();

        long seconds = (System.currentTimeMillis() - start) / 1000;
        log.info("SEPA snapshot: {} filas -> {} productos y {} precios por comercio en {}s",
                filasProcesadas.get(), byEan.size(), preciosComercioInsertados.get(), seconds);

        return SepaSyncResponse.builder()
                .dia(resource.dia())
                .fechaDataset(fecha.toString())
                .filasProcesadas(filasProcesadas.get())
                .productosGuardados(byEan.size())
                .duracionSegundos(seconds)
                .build();
    }

    /**
     * Acumula el desglose por comercio SIN cargar el dataset entero en memoria.
     *
     * <p>El zip de SEPA trae un zip interno por comercio, así que las filas
     * llegan agrupadas: cuando cambia el id_comercio sabemos que el anterior
     * terminó y lo volcamos a la staging. La memoria queda acotada a UN
     * comercio por vez —decenas de miles de EANs— en vez de crecer con el
     * producto de comercios por productos.
     */
    private final class AcumuladorComercio {

        private final LocalDate fecha;
        private final Map<String, Agg> porEan = new HashMap<>(1 << 16);
        private String comercioId;
        private String bandera;
        private String razonSocial;

        AcumuladorComercio(LocalDate fecha) {
            this.fecha = fecha;
        }

        void acumular(ar.edu.ofertAR.dto.response.SepaPrecioResponse p, BigDecimal precio) {
            String id = p.comercioId() == null ? "" : p.comercioId();
            if (comercioId != null && !comercioId.equals(id)) {
                volcar();
            }
            comercioId = id;
            bandera = p.bandera();
            razonSocial = p.comercioRazonSocial();
            porEan.computeIfAbsent(p.ean(), k -> new Agg(null, null)).add(precio);
        }

        void volcar() {
            if (porEan.isEmpty()) {
                return;
            }
            List<Object[]> batch = new ArrayList<>(batchSize);
            for (Map.Entry<String, Agg> e : porEan.entrySet()) {
                Agg a = e.getValue();
                batch.add(new Object[]{
                        truncate(e.getKey(), 20),
                        truncate(comercioId, 20),
                        truncate(bandera, 255),
                        truncate(razonSocial, 255),
                        a.min,
                        a.max,
                        a.count,
                        Date.valueOf(fecha)
                });
                if (batch.size() == batchSize) {
                    insertarLote(INSERT_COMERCIO, batch, preciosComercioInsertados);
                }
            }
            if (!batch.isEmpty()) {
                insertarLote(INSERT_COMERCIO, batch, preciosComercioInsertados);
            }
            porEan.clear();
        }
    }

    /**
     * Vuelca el agregado por EAN a la staging, en lotes con commit propio.
     * El swap lo hace {@link #swap()} recién cuando las dos tablas están listas.
     */
    private void cargarProductos(Map<String, Agg> byEan, LocalDate fecha) {
        String sql = "INSERT INTO " + TABLA_STAGING + " "
                + "(ean, descripcion, marca, precio_minimo, precio_promedio, precio_maximo, "
                + "cantidad_ofertas, fecha_dataset) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batch = new ArrayList<>(batchSize);
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
            if (batch.size() == batchSize) {
                insertarLote(sql, batch, productosInsertados);
            }
        }
        if (!batch.isEmpty()) {
            insertarLote(sql, batch, productosInsertados);
        }
    }

    /**
     * Cada lote es su propia transacción (autocommit): commits chicos y
     * frecuentes en vez de uno gigante al final. Entre lotes cedemos IO para
     * que el tráfico de usuarios no sufra.
     */
    private void insertarLote(String sql, List<Object[]> batch, AtomicLong contador) {
        jdbcTemplate.batchUpdate(sql, batch);
        contador.addAndGet(batch.size());
        batch.clear();
        if (batchPausaMs > 0) {
            try {
                Thread.sleep(batchPausaMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Carga del snapshot interrumpida", ie);
            }
        }
    }

    private void crearStaging() {
        for (String t : new String[]{TABLA_STAGING, TABLA_VIEJA, TABLA_COM_STAGING, TABLA_COM_VIEJA}) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + t);
        }
        // LIKE copia columnas, tipos, AUTO_INCREMENT e índices de la tabla viva
        jdbcTemplate.execute("CREATE TABLE " + TABLA_STAGING + " LIKE " + TABLA);
        jdbcTemplate.execute("CREATE TABLE " + TABLA_COM_STAGING + " LIKE " + TABLA_COM);
        // Insertar con los índices secundarios armados cuesta caro: los sacamos
        // y los reconstruimos de una al final. Si falla, seguimos igual.
        soltarIndice(TABLA_STAGING, "idx_sepa_producto_descripcion");
        soltarIndice(TABLA_COM_STAGING, "idx_sepa_precio_comercio_ean");
    }

    private void reconstruirIndices() {
        crearIndice(TABLA_STAGING, "idx_sepa_producto_descripcion", "descripcion");
        crearIndice(TABLA_COM_STAGING, "idx_sepa_precio_comercio_ean", "ean");
    }

    private void soltarIndice(String tabla, String indice) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + tabla + " DROP INDEX " + indice);
        } catch (Exception e) {
            log.debug("No se pudo soltar {} en {}: {}", indice, tabla, e.getMessage());
        }
    }

    private void crearIndice(String tabla, String indice, String columnas) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + tabla + " ADD INDEX " + indice + " (" + columnas + ")");
        } catch (Exception e) {
            log.warn("No se pudo recrear {} en {}: {}", indice, tabla, e.getMessage());
        }
    }

    /**
     * RENAME TABLE es atómico en MySQL: los lectores ven la tabla vieja o la
     * nueva, nunca una a medio cargar ni una vacía. Es el único momento en que
     * se toma un lock, y dura milisegundos.
     */
    private void swap() {
        // Un solo RENAME para las dos tablas: nunca se ve un producto con el
        // agregado nuevo y el desglose viejo, ni al revés.
        jdbcTemplate.execute("RENAME TABLE "
                + TABLA + " TO " + TABLA_VIEJA + ", "
                + TABLA_STAGING + " TO " + TABLA + ", "
                + TABLA_COM + " TO " + TABLA_COM_VIEJA + ", "
                + TABLA_COM_STAGING + " TO " + TABLA_COM);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLA_VIEJA);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLA_COM_VIEJA);
        log.info("SEPA snapshot: swap completo, {} productos y {} precios por comercio activos",
                productosInsertados.get(), preciosComercioInsertados.get());
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
