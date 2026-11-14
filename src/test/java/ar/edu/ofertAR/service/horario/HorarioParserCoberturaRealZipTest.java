package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.FranjaHorariaResponse;
import ar.edu.ofertAR.dto.response.HorariosResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobertura del parser sobre TODAS las celdas de horario de un dataset SEPA real.
 * Se saltea si no hay zip: la ruta va en SEPA_TEST_ZIP o en -Dsepa.test.zip, como en
 * {@code SepaServiceSucursalesRealZipTest}. Imprime el reparto (parseado / cerrado /
 * null), las formas que quedan en null y los comercios con 24 h de relleno.
 */
class HorarioParserCoberturaRealZipTest {

    private static Path zipPath() {
        String fromEnv = System.getenv("SEPA_TEST_ZIP");
        if (fromEnv != null && !fromEnv.isBlank()) return Path.of(fromEnv);
        String fromProp = System.getProperty("sepa.test.zip", "");
        return fromProp.isBlank() ? Path.of("/no-hay-zip-sepa") : Path.of(fromProp);
    }

    @Test
    @DisplayName("más del 96% de las celdas reales se entienden, y lo que sale respeta el contrato")
    void cobertura() throws IOException {
        Path zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(zip), "zip SEPA de prueba no presente: " + zip);

        int celdas = 0, parseadas = 0, cerradas = 0, nulas = 0;
        Map<String, Integer> formasNulas = new TreeMap<>();
        List<String> relleno = new ArrayList<>();

        for (Map.Entry<String, List<List<String>>> comercio : sucursalesPorComercio(zip).entrySet()) {
            List<HorariosResponse> semanas = new ArrayList<>();
            for (List<String> fila : comercio.getValue()) {
                for (String celda : fila) {
                    celdas++;
                    List<FranjaHorariaResponse> r = HorarioParser.parsear(celda);
                    if (r == null) {
                        nulas++;
                        formasNulas.merge(comercio.getKey() + " \"" + celda + "\"", 1, Integer::sum);
                    } else if (r.isEmpty()) {
                        cerradas++;
                    } else {
                        parseadas++;
                        r.forEach(f -> {
                            assertTrue(f.desde().matches("([01]\\d|2[0-3]):[0-5]\\d"), celda + " -> " + f);
                            assertTrue(f.hasta().matches("([01]\\d|2[0-3]):[0-5]\\d|24:00"), celda + " -> " + f);
                        });
                    }
                }
                semanas.add(HorarioParser.parsearSemana(fila));
            }
            if (HorariosPlaceholder.esRelleno24h(semanas)) {
                relleno.add(comercio.getKey() + " (" + semanas.size() + " sucursales)");
            }
        }

        System.out.printf(Locale.ROOT, "celdas=%d parseadas=%d (%.2f%%) cerradas=%d (%.2f%%) null=%d (%.2f%%)%n",
                celdas, parseadas, 100.0 * parseadas / celdas, cerradas, 100.0 * cerradas / celdas,
                nulas, 100.0 * nulas / celdas);
        formasNulas.forEach((forma, n) -> System.out.printf("  null %5d  comercio %s%n", n, forma));
        System.out.println("comercios con 24 h de relleno: " + relleno);

        assertTrue(celdas > 10_000, "celdas: " + celdas);
        // Septiembre 2026: 97,0%. Lo que queda en null son celdas vacías de Carrefour (252),
        // Farmacity ("00:00 a 00:00" y marcas de tiempo, 356) y los "15.3" de La Agrícola (14).
        assertTrue(parseadas + cerradas > celdas * 0.96, "cobertura baja");
    }

    /** id_comercio -> filas de sucursales.csv, cada una con sus siete celdas de horario. */
    private static Map<String, List<List<String>>> sucursalesPorComercio(Path outer) throws IOException {
        Map<String, List<List<String>>> out = new TreeMap<>();
        Path tmp = Files.createTempFile("sepa-inner", ".zip");
        try (ZipFile outerZip = new ZipFile(outer.toFile())) {
            Enumeration<? extends ZipEntry> en = outerZip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".zip") || e.getSize() == 0) {
                    continue;
                }
                try (InputStream is = outerZip.getInputStream(e)) {
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                leerSucursales(tmp, out);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return out;
    }

    private static void leerSucursales(Path inner, Map<String, List<List<String>>> out) throws IOException {
        try (ZipFile zip = new ZipFile(inner.toFile())) {
            ZipEntry entry = Collections.list(zip.entries()).stream()
                    .filter(z -> z.getName().toLowerCase(Locale.ROOT).contains("sucursales"))
                    .findFirst().orElse(null);
            if (entry == null) {
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(entry),
                    StandardCharsets.UTF_8))) {
                String header = r.readLine();
                if (header == null) {
                    return;
                }
                String[] h = header.replace("﻿", "").split("\\|", -1);
                Map<String, Integer> cols = new HashMap<>();
                for (int i = 0; i < h.length; i++) cols.put(h[i].trim().toLowerCase(Locale.ROOT), i);
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\\|", -1);
                    Integer idSuc = cols.get("id_sucursal");
                    if (idSuc == null || idSuc >= f.length || f[idSuc].isBlank()) continue;
                    // Las filas partidas por saltos de línea dentro de un campo (Comodín) no
                    // llegan a las columnas de horario: SepaService ya las descarta por
                    // coordenadas, así que tampoco cuentan acá.
                    Integer ultima = cols.get("sucursales_domingo_horario_atencion");
                    if (ultima == null || ultima >= f.length) continue;
                    List<String> fila = new ArrayList<>(7);
                    for (String dia : HorarioParser.DIAS) {
                        fila.add(f[cols.get("sucursales_" + dia + "_horario_atencion")]);
                    }
                    out.computeIfAbsent(f[cols.get("id_comercio")].trim(), k -> new ArrayList<>()).add(fila);
                }
            }
        }
    }
}
