package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.SepaPrecioResponse;
import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Servicio SEPA "Precios Claros" (datos.produccion.gob.ar/dataset/sepa-precios).
 *
 * Flujo: resolver recurso (API CKAN) -> descargar zip (cache en disco)
 * -> descomprimir zips anidados -> normalizar CSVs (delimitados por '|')
 * -> emitir cada fila a un consumer (paginación o snapshot en DB).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SepaService {

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    @Value("${sepa.ckan-package-url:https://datos.produccion.gob.ar/api/3/action/package_show?id=sepa-precios}")
    private String ckanPackageUrl;

    @Value("${sepa.cache-dir:${java.io.tmpdir}/sepa-cache}")
    private String cacheDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public record SepaResource(String dia, String fecha, String url) {}

    // ── API pública ──────────────────────────────────────────────────

    /** GET en vivo: filtra y pagina sin cargar todo en memoria. */
    public SepaPreciosPageResponse getPrecios(String dia, String comercio, String producto,
                                              String ean, int page, int size) {
        if (page < 0) page = 0;
        if (size < 1) size = 50;
        if (size > 1000) size = 1000;

        PageCollector collector = new PageCollector((long) page * size, size);
        SepaResource resource = scan(dia, comercio, producto, ean, collector);

        return SepaPreciosPageResponse.builder()
                .dia(resource.dia())
                .fecha(resource.fecha())
                .recursoUrl(resource.url())
                .page(page)
                .size(size)
                .totalElementos(collector.total)
                .totalPaginas((collector.total + size - 1) / size)
                .data(collector.data)
                .build();
    }

    /**
     * Recorre el dataset completo (descarga con cache + descompresión anidada)
     * y emite cada fila que matchea los filtros al consumer, en streaming.
     *
     * @return el recurso (día/fecha/url) efectivamente procesado
     */
    public SepaResource scan(String dia, String comercio, String producto, String ean,
                             Consumer<SepaPrecioResponse> consumer) {
        SepaResource resource = resolveResource(dia);
        Path zipPath = downloadWithCache(resource);
        processOuterZip(zipPath, comercio, producto, ean, consumer);
        return resource;
    }

    /** Consumer de paginación: cuenta el total y colecta solo la ventana pedida. */
    private static final class PageCollector implements Consumer<SepaPrecioResponse> {
        private final long offset;
        private final int size;
        private long total = 0;
        private final List<SepaPrecioResponse> data;

        PageCollector(long offset, int size) {
            this.offset = offset;
            this.size = size;
            this.data = new ArrayList<>(size);
        }

        @Override
        public void accept(SepaPrecioResponse row) {
            if (total >= offset && data.size() < size) {
                data.add(row);
            }
            total++;
        }
    }

    // ── 1. Resolver recurso vía API CKAN ─────────────────────────────

    private SepaResource resolveResource(String dia) {
        JsonNode root;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ckanPackageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("CKAN respondió HTTP " + response.statusCode());
            }
            root = objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar la API de datos.produccion.gob.ar: " + e.getMessage(), e);
        }

        JsonNode resources = root.path("result").path("resources");
        if (!resources.isArray() || resources.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La API CKAN no devolvió recursos para el dataset sepa-precios");
        }

        SepaResource best = null;
        for (JsonNode res : resources) {
            String format = res.path("format").asText("").toLowerCase(Locale.ROOT);
            String url = res.path("url").asText("");
            if (url.isBlank() || (!format.contains("zip") && !url.toLowerCase(Locale.ROOT).endsWith(".zip"))) {
                continue;
            }
            String name = res.path("name").asText("");
            String description = res.path("description").asText("");
            String fecha = extractDate(description, res.path("last_modified").asText(""), url);

            if (dia != null && !dia.isBlank()) {
                String target = normalize(dia);
                if (!normalize(name).contains(target) && !normalize(description).contains(target)) {
                    continue;
                }
            }
            SepaResource candidate = new SepaResource(name, fecha, url);
            if (best == null || candidate.fecha().compareTo(best.fecha()) > 0) {
                best = candidate;
            }
        }

        if (best == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    dia == null ? "No se encontró ningún recurso zip en el dataset"
                                : "No se encontró recurso para el día '" + dia + "'");
        }
        log.info("SEPA: recurso seleccionado '{}' ({}) -> {}", best.dia(), best.fecha(), best.url());
        return best;
    }

    private String extractDate(String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            Matcher m = DATE_PATTERN.matcher(c);
            if (m.find()) return m.group(1);
        }
        return "0000-00-00";
    }

    // ── 2. Descargar con cache en disco ──────────────────────────────

    private synchronized Path downloadWithCache(SepaResource resource) {
        try {
            Path dir = Path.of(cacheDir);
            Files.createDirectories(dir);
            String fileName = "sepa_" + normalize(resource.dia()) + "_" + resource.fecha() + ".zip";
            Path target = dir.resolve(fileName);

            if (Files.exists(target) && Files.size(target) > 0) {
                log.info("SEPA: usando cache {}", target);
                return target;
            }

            log.info("SEPA: descargando {} (puede tardar varios minutos)...", resource.url());
            HttpRequest request = HttpRequest.newBuilder(URI.create(resource.url())).GET().build();
            Path tmp = dir.resolve(fileName + ".part");
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("Descarga falló con HTTP " + response.statusCode());
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("SEPA: descargado {} ({} MB)", target, Files.size(target) / (1024 * 1024));
            return target;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo descargar el dataset SEPA: " + e.getMessage(), e);
        }
    }

    // ── 3/4. Descomprimir zips anidados, normalizar y emitir ─────────

    private void processOuterZip(Path zipPath, String comercio, String producto, String ean,
                                 Consumer<SepaPrecioResponse> consumer) {
        String comercioFilter = normalizeOrNull(comercio);
        String productoFilter = normalizeOrNull(producto);
        String eanFilter = (ean == null || ean.isBlank()) ? null : ean.trim();

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = zipFile.stream()
                    .filter(e -> !e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted((a, b) -> a.getName().compareTo(b.getName()))
                    .toList();
            if (entries.isEmpty()) {
                throw new IOException("El zip descargado no contiene zips internos de comercios");
            }
            for (ZipEntry entry : entries) {
                byte[] innerZip;
                try (InputStream in = zipFile.getInputStream(entry)) {
                    innerZip = in.readAllBytes();
                }
                processInnerZip(innerZip, comercioFilter, productoFilter, eanFilter, consumer);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error procesando el zip SEPA: " + e.getMessage(), e);
        }
    }

    private void processInnerZip(byte[] innerZip, String comercioFilter, String productoFilter,
                                 String eanFilter, Consumer<SepaPrecioResponse> consumer) throws IOException {
        // Primera pasada: metadata del comercio (comercio.csv es chico)
        Map<String, String> comercioInfo = readComercioCsv(innerZip);

        String razonSocial = comercioInfo.getOrDefault("comercio_razon_social", "");
        String bandera = comercioInfo.getOrDefault("comercio_bandera_nombre", "");
        String cuit = comercioInfo.getOrDefault("comercio_cuit", "");
        String idComercio = comercioInfo.getOrDefault("id_comercio", "");

        if (comercioFilter != null
                && !normalize(razonSocial).contains(comercioFilter)
                && !normalize(bandera).contains(comercioFilter)
                && !cuit.equals(comercioFilter)
                && !idComercio.equals(comercioFilter)) {
            return; // este comercio no interesa: no parseamos sus productos
        }

        // Segunda pasada: productos.csv en streaming
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(innerZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().toLowerCase(Locale.ROOT).contains("productos")) continue;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zis, StandardCharsets.UTF_8));
                String headerLine = reader.readLine();
                if (headerLine == null) continue;
                Map<String, Integer> cols = headerIndex(headerLine);
                Integer descIdx = cols.get("productos_descripcion");
                if (descIdx == null) continue; // no es el productos.csv esperado

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] f = line.split("\\|", -1);
                    if (f.length <= descIdx) continue; // línea de cierre/basura

                    if (eanFilter != null && !eanFilter.equals(get(f, cols, "productos_ean"))) continue;
                    if (productoFilter != null
                            && !normalize(get(f, cols, "productos_descripcion")).contains(productoFilter)
                            && !normalize(get(f, cols, "productos_marca")).contains(productoFilter)) {
                        continue;
                    }

                    consumer.accept(SepaPrecioResponse.builder()
                            .comercioId(idComercio.isBlank() ? get(f, cols, "id_comercio") : idComercio)
                            .comercioCuit(cuit)
                            .comercioRazonSocial(razonSocial)
                            .bandera(bandera)
                            .sucursalId(get(f, cols, "id_sucursal"))
                            .productoId(get(f, cols, "id_producto"))
                            .ean(get(f, cols, "productos_ean"))
                            .descripcion(get(f, cols, "productos_descripcion"))
                            .marca(get(f, cols, "productos_marca"))
                            .cantidadPresentacion(get(f, cols, "productos_cantidad_presentacion"))
                            .unidadMedidaPresentacion(get(f, cols, "productos_unidad_medida_presentacion"))
                            .precioLista(toDecimal(get(f, cols, "productos_precio_lista")))
                            .precioReferencia(toDecimal(get(f, cols, "productos_precio_referencia")))
                            .unidadMedidaReferencia(get(f, cols, "productos_unidad_medida_referencia"))
                            .precioPromo1(toDecimal(get(f, cols, "productos_precio_unitario_promo1")))
                            .leyendaPromo1(get(f, cols, "productos_leyenda_promo1"))
                            .precioPromo2(toDecimal(get(f, cols, "productos_precio_unitario_promo2")))
                            .leyendaPromo2(get(f, cols, "productos_leyenda_promo2"))
                            .build());
                }
            }
        }
    }

    private Map<String, String> readComercioCsv(byte[] innerZip) throws IOException {
        Map<String, String> info = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(innerZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("comercio") || name.contains("sucursal")) continue;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zis, StandardCharsets.UTF_8));
                String headerLine = reader.readLine();
                String dataLine = reader.readLine();
                if (headerLine == null || dataLine == null) continue;

                String[] headers = headerLine.split("\\|", -1);
                String[] values = dataLine.split("\\|", -1);
                for (int i = 0; i < headers.length && i < values.length; i++) {
                    info.put(headers[i].trim().toLowerCase(Locale.ROOT), values[i].trim());
                }
                return info;
            }
        }
        return info;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Map<String, Integer> headerIndex(String headerLine) {
        Map<String, Integer> cols = new HashMap<>();
        String[] headers = headerLine.split("\\|", -1);
        for (int i = 0; i < headers.length; i++) {
            cols.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return cols;
    }

    private String get(String[] fields, Map<String, Integer> cols, String col) {
        Integer idx = cols.get(col);
        if (idx == null || idx >= fields.length) return "";
        return fields[idx].trim();
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** minúsculas + sin acentos, para comparaciones tolerantes */
    private String normalize(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String normalizeOrNull(String s) {
        String n = normalize(s);
        return n.isBlank() ? null : n;
    }
}
