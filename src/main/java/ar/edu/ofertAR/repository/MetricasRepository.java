package ar.edu.ofertAR.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;

/**
 * Acceso a las tablas de métricas (V9__metricas.sql). Va por JDBC y no por JPA: son
 * inserciones en lote y consultas agregadas, sin entidades que mantener.
 */
@Repository
@RequiredArgsConstructor
public class MetricasRepository {

    private static final int LOTE = 500;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    /** Una serie en un minuto, lista para guardar. */
    public record Muestra(long ts, String name, String tags, String kind,
                          double cnt, double total, double max,
                          Double p50, Double p95, Double p99) {}

    /** Una serie en un intervalo del gráfico (uno o más minutos agregados). */
    public record Punto(String name, String tags, String kind, long t,
                        double cnt, double total, double max,
                        Double p50, Double p95, Double p99) {}

    public record Metrica(String name, String kind, long lastTs) {}

    public record ErrorApp(long id, long ts, String kind, String message, String stack, String screen,
                           String platform, String appVersion, String osVersion, boolean fatal) {}

    public void guardar(List<Muestra> muestras) {
        if (muestras.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO metric_samples (ts, name, tags, kind, cnt, total, max_value, p50, p95, p99) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                muestras, LOTE, (ps, m) -> {
                    ps.setLong(1, m.ts());
                    ps.setString(2, m.name());
                    ps.setString(3, m.tags());
                    ps.setString(4, m.kind());
                    ps.setDouble(5, m.cnt());
                    ps.setDouble(6, m.total());
                    ps.setDouble(7, m.max());
                    setDouble(ps, 8, m.p50());
                    setDouble(ps, 9, m.p95());
                    setDouble(ps, 10, m.p99());
                });
    }

    /**
     * Las series de esas métricas entre {@code desde} (incluido) y {@code hasta} (excluido),
     * agregadas en intervalos de {@code paso} segundos. El p50 se promedia ponderado por la
     * cantidad de eventos; p95 y p99 no se pueden combinar entre minutos, así que se toma el
     * peor del intervalo.
     */
    public List<Punto> series(Collection<String> names, long desde, long hasta, long paso) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("names", names)
                .addValue("desde", desde)
                .addValue("hasta", hasta)
                .addValue("paso", paso);
        return namedJdbc.query("""
                SELECT name, tags, MAX(kind) AS kind, (ts DIV :paso) * :paso AS t,
                       SUM(cnt) AS cnt, SUM(total) AS total, MAX(max_value) AS max_value,
                       SUM(p50 * cnt) / NULLIF(SUM(IF(p50 IS NULL, 0, cnt)), 0) AS p50,
                       MAX(p95) AS p95, MAX(p99) AS p99
                FROM metric_samples
                WHERE name IN (:names) AND ts >= :desde AND ts < :hasta
                GROUP BY name, tags, t
                ORDER BY name, tags, t
                """, params, (rs, i) -> new Punto(
                rs.getString("name"), rs.getString("tags"), rs.getString("kind"), rs.getLong("t"),
                rs.getDouble("cnt"), rs.getDouble("total"), rs.getDouble("max_value"),
                getDouble(rs, "p50"), getDouble(rs, "p95"), getDouble(rs, "p99")));
    }

    /** Las métricas con al menos una muestra desde {@code desde}. */
    public List<Metrica> catalogo(long desde) {
        return jdbc.query(
                "SELECT name, MAX(kind) AS kind, MAX(ts) AS last_ts FROM metric_samples "
                        + "WHERE ts >= ? GROUP BY name ORDER BY name",
                (rs, i) -> new Metrica(rs.getString("name"), rs.getString("kind"), rs.getLong("last_ts")),
                desde);
    }

    /** El minuto de la última muestra guardada, o null si la tabla está vacía. */
    public Long ultimoTs() {
        return jdbc.queryForObject("SELECT MAX(ts) FROM metric_samples", Long.class);
    }

    public void guardarError(long ts, String kind, String message, String stack, String screen,
                             String platform, String appVersion, String osVersion, boolean fatal) {
        jdbc.update(
                "INSERT INTO app_error_events (ts, kind, message, stack, screen, platform, app_version, os_version, fatal) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ts, kind, message, stack, screen, platform, appVersion, osVersion, fatal);
    }

    public List<ErrorApp> errores(long desde, int limite) {
        return jdbc.query(
                "SELECT id, ts, kind, message, stack, screen, platform, app_version, os_version, fatal "
                        + "FROM app_error_events WHERE ts >= ? ORDER BY ts DESC, id DESC LIMIT ?",
                (rs, i) -> new ErrorApp(
                        rs.getLong("id"), rs.getLong("ts"), rs.getString("kind"), rs.getString("message"),
                        rs.getString("stack"), rs.getString("screen"), rs.getString("platform"),
                        rs.getString("app_version"), rs.getString("os_version"), rs.getBoolean("fatal")),
                desde, limite);
    }

    /** Borra hasta {@code limite} filas anteriores a {@code ts}; devuelve cuántas borró. */
    public int purgarMuestras(long ts, int limite) {
        return jdbc.update("DELETE FROM metric_samples WHERE ts < ? LIMIT ?", ts, limite);
    }

    public int purgarErrores(long ts, int limite) {
        return jdbc.update("DELETE FROM app_error_events WHERE ts < ? LIMIT ?", ts, limite);
    }

    private static void setDouble(PreparedStatement ps, int indice, Double valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.DOUBLE);
        } else {
            ps.setDouble(indice, valor);
        }
    }

    private static Double getDouble(ResultSet rs, String columna) throws SQLException {
        double valor = rs.getDouble(columna);
        return rs.wasNull() ? null : valor;
    }
}
