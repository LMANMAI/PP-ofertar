package ar.edu.ofertAR.service.metricas;

import ar.edu.ofertAR.repository.MetricasRepository;
import ar.edu.ofertAR.repository.MetricasRepository.Muestra;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Guarda en la base, una vez por minuto, una foto de las métricas de Micrometer para que el
 * tablero pueda dibujar su historia (Actuator solo conoce el valor actual).
 *
 * <p>Todo se acumula en memoria y baja a MySQL en un único lote por minuto: lo que se escribe
 * depende de cuántas series distintas hubo, no de cuántos requests. Un timer o contador sin
 * actividad en el minuto no genera fila. Con varias réplicas cada una escribe las suyas y la
 * consulta las suma.
 *
 * <p>Qué se guarda lo decide {@link #REGLAS}: las métricas de infraestructura elegidas, más
 * todo lo que empiece con {@code ofertar.} (negocio, ver {@link Metricas}) o {@code app.}
 * (telemetría de la app, ver {@link TelemetriaService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricasSnapshotService {

    /** Una métrica de Micrometer a guardar y los tags que se conservan (null = todos). */
    private record Regla(String nombre, Set<String> tags) {
        boolean aplica(String meter) {
            return nombre.endsWith(".") ? meter.startsWith(nombre) : meter.equals(nombre);
        }
    }

    private static final List<Regla> REGLAS = List.of(
            new Regla("http.server.requests", Set.of("method", "uri", "status", "exception")),
            new Regla("spring.data.repository.invocations", Set.of("repository", "method", "state")),
            new Regla("jvm.memory.used", Set.of("area")),
            new Regla("jvm.memory.max", Set.of("area")),
            new Regla("jvm.threads.live", Set.of()),
            new Regla("jvm.gc.pause", Set.of()),
            new Regla("process.cpu.usage", Set.of()),
            new Regla("system.cpu.usage", Set.of()),
            new Regla("hikaricp.connections.active", Set.of()),
            new Regla("hikaricp.connections.idle", Set.of()),
            new Regla("hikaricp.connections.pending", Set.of()),
            new Regla("hikaricp.connections.max", Set.of()),
            new Regla("hikaricp.connections.acquire", Set.of()),
            new Regla("logback.events", Set.of("level")),
            new Regla("ofertar.", null),
            new Regla("app.", null));

    private static final int TAGS_MAX = 255;
    private static final int VALOR_TAG_MAX = 120;
    /** Usuarios y tickets totales se cuentan en la base: alcanza con refrescarlos cada tanto. */
    private static final int MINUTOS_ENTRE_CONTEOS = 5;
    private static final int PURGA_LOTE = 5000;

    private final MeterRegistry registry;
    private final MetricasRepository repo;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;

    @Value("${metricas.enabled:true}")
    private boolean enabled;

    @Value("${metricas.retencion-dias:14}")
    private int retencionDias;

    /** Último valor acumulado de cada timer/contador, para sacar lo que pasó en el minuto. */
    private final Map<Meter.Id, double[]> anteriores = new HashMap<>();
    private long ultimoTs;
    private long corridas;
    private long usuarios = -1;
    private long tickets = -1;

    /** Acumulado de una serie dentro del minuto (varios meters pueden caer en la misma). */
    private static final class Serie {
        final String name;
        final String tags;
        final String kind;
        double cnt;
        double total;
        double max;
        Double p50;
        Double p95;
        Double p99;

        Serie(String name, String tags, String kind) {
            this.name = name;
            this.tags = tags;
            this.kind = kind;
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public synchronized void tomarMuestra() {
        if (!enabled) {
            return;
        }
        try {
            // Corre al segundo 0: lo que se acumuló corresponde al minuto que acaba de cerrar.
            long ts = (System.currentTimeMillis() / 60_000L - 1) * 60L;
            if (ts <= ultimoTs) {
                return;
            }
            List<Muestra> muestras = recolectar(ts);
            agregarConteos(ts, muestras);
            repo.guardar(muestras);
            ultimoTs = ts;
        } catch (Exception e) {
            log.warn("No se pudieron guardar las métricas del minuto: {}", e.getMessage());
        }
    }

    /** Visible para tests: arma las filas del minuto {@code ts} a partir del registro. */
    List<Muestra> recolectar(long ts) {
        Map<String, Serie> series = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            String nombre = id.getName();
            Regla regla = reglaPara(nombre);
            if (regla == null) {
                continue;
            }
            String tags = tags(id, regla);
            if (tags == null) {
                continue;
            }
            String clave = nombre + '\n' + tags;

            if (meter instanceof Timer timer) {
                double cuenta = timer.count();
                double total = timer.totalTime(TimeUnit.MILLISECONDS);
                double[] antes = anteriores.put(id, new double[] {cuenta, total});
                double deltaCuenta = cuenta - (antes == null ? 0 : antes[0]);
                double deltaTotal = total - (antes == null ? 0 : antes[1]);
                if (deltaCuenta <= 0) {
                    continue;
                }
                Serie s = series.computeIfAbsent(clave, k -> new Serie(nombre, tags, "timer"));
                s.cnt += deltaCuenta;
                s.total += Math.max(0, deltaTotal);
                s.max = Math.max(s.max, timer.max(TimeUnit.MILLISECONDS));
                for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
                    double ms = v.value(TimeUnit.MILLISECONDS);
                    if (cerca(v.percentile(), 0.5)) {
                        s.p50 = mayor(s.p50, ms);
                    } else if (cerca(v.percentile(), 0.95)) {
                        s.p95 = mayor(s.p95, ms);
                    } else if (cerca(v.percentile(), 0.99)) {
                        s.p99 = mayor(s.p99, ms);
                    }
                }
            } else if (meter instanceof Counter || meter instanceof FunctionCounter) {
                double cuenta = meter instanceof Counter c ? c.count() : ((FunctionCounter) meter).count();
                double[] antes = anteriores.put(id, new double[] {cuenta, 0});
                double delta = cuenta - (antes == null ? 0 : antes[0]);
                if (delta <= 0) {
                    continue;
                }
                Serie s = series.computeIfAbsent(clave, k -> new Serie(nombre, tags, "counter"));
                s.cnt += delta;
                s.total += delta;
                s.max = Math.max(s.max, delta);
            } else if (meter instanceof Gauge gauge) {
                double valor = gauge.value();
                // NaN: el objeto medido ya no existe. Negativo: "no disponible" (CPU, tope de memoria).
                if (Double.isNaN(valor) || Double.isInfinite(valor) || valor < 0) {
                    continue;
                }
                // Varios meters en la misma serie se suman (los pools del heap, por ejemplo).
                Serie s = series.computeIfAbsent(clave, k -> new Serie(nombre, tags, "gauge"));
                s.cnt = 1;
                s.total += valor;
                s.max = s.total;
            }
        }

        List<Muestra> muestras = new ArrayList<>(series.size());
        for (Serie s : series.values()) {
            double max = s.max;
            if ("timer".equals(s.kind)) {
                // El máximo de Micrometer es de una ventana móvil y puede haber vencido: nunca menos que el promedio.
                max = Math.max(max, s.total / s.cnt);
            }
            muestras.add(new Muestra(ts, s.name, s.tags, s.kind, s.cnt, s.total, max, s.p50, s.p95, s.p99));
        }
        return muestras;
    }

    /** Usuarios y tickets totales: gauges que no salen de Micrometer sino de la base. */
    private void agregarConteos(long ts, List<Muestra> muestras) {
        try {
            if (corridas++ % MINUTOS_ENTRE_CONTEOS == 0) {
                usuarios = userRepository.count();
                tickets = ticketRepository.count();
            }
        } catch (Exception e) {
            log.debug("No se pudieron contar usuarios/tickets: {}", e.getMessage());
        }
        if (usuarios >= 0) {
            muestras.add(new Muestra(ts, "ofertar.users.total", "", "gauge", 1, usuarios, usuarios, null, null, null));
        }
        if (tickets >= 0) {
            muestras.add(new Muestra(ts, "ofertar.tickets.total", "", "gauge", 1, tickets, tickets, null, null, null));
        }
    }

    /** Borra lo más viejo que la retención, de a lotes chicos para no retener la tabla. */
    @Scheduled(cron = "${metricas.purga-cron:0 40 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void purgar() {
        if (!enabled || retencionDias <= 0) {
            return;
        }
        long limite = System.currentTimeMillis() / 1000L - retencionDias * 86_400L;
        try {
            long borradas = 0;
            int lote;
            int vueltas = 0;
            do {
                lote = repo.purgarMuestras(limite, PURGA_LOTE);
                borradas += lote;
                if (lote == PURGA_LOTE) {
                    Thread.sleep(200);
                }
            } while (lote == PURGA_LOTE && ++vueltas < 1000);
            int errores = repo.purgarErrores(limite, PURGA_LOTE);
            log.info("Purga de métricas: {} muestras y {} errores de la app con más de {} días",
                    borradas, errores, retencionDias);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Falló la purga de métricas: {}", e.getMessage());
        }
    }

    private static Regla reglaPara(String nombre) {
        for (Regla regla : REGLAS) {
            if (regla.aplica(nombre)) {
                return regla;
            }
        }
        return null;
    }

    /** Los tags de la serie como "clave=valor|clave=valor", o null si el meter no se guarda. */
    private static String tags(Meter.Id id, Regla regla) {
        String nombre = id.getName();
        TreeMap<String, String> elegidos = new TreeMap<>();
        for (Tag tag : id.getTagsAsIterable()) {
            String clave = tag.getKey();
            String valor = tag.getValue();
            if (regla.tags() != null && !regla.tags().contains(clave)) {
                continue;
            }
            if (nombre.equals("http.server.requests")) {
                // El tablero y la telemetría de la app no cuentan como tráfico de la API.
                if (clave.equals("uri") && (valor.startsWith("/telemetry") || valor.startsWith("/actuator"))) {
                    return null;
                }
                if (clave.equals("exception") && (valor.isEmpty() || valor.equalsIgnoreCase("none"))) {
                    continue;
                }
            }
            if (nombre.equals("logback.events") && clave.equals("level")
                    && !valor.equals("warn") && !valor.equals("error")) {
                return null;
            }
            if (valor.length() > VALOR_TAG_MAX) {
                valor = valor.substring(0, VALOR_TAG_MAX);
            }
            elegidos.put(clave, valor.replace('|', '/'));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : elegidos.entrySet()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.length() > TAGS_MAX ? sb.substring(0, TAGS_MAX) : sb.toString();
    }

    private static boolean cerca(double a, double b) {
        return Math.abs(a - b) < 0.001;
    }

    private static Double mayor(Double actual, double nuevo) {
        return actual == null ? nuevo : Math.max(actual, nuevo);
    }
}
