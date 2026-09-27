package ar.edu.ofertAR.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La carpeta vigilada (SEPA_RESOURCE_DIR) y la fecha leída de adentro del zip.
 *
 * <p>El servidor está en Chile y el sitio oficial le responde 403, así que el
 * zip lo baja otro equipo (tools/sepa-relay) y lo deja en una carpeta. Antes,
 * cada archivo nuevo obligaba a cambiar SEPA_RESOURCE_FILE y SEPA_RESOURCE_FECHA
 * a mano, porque "sepa_martes.zip", que es como lo publica el sitio, no dice la
 * fecha en el nombre. Estos tests fijan que ya no hace falta ninguna de las dos.
 */
class SepaServiceCarpetaVigiladaTest {

    /** Un zip con la forma de los de SEPA: sólo importan los nombres de las entradas. */
    private static Path zip(Path dir, String nombre, String... entradas) throws IOException {
        Path destino = dir.resolve(nombre);
        try (OutputStream out = Files.newOutputStream(destino); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String entrada : entradas) {
                zos.putNextEntry(new ZipEntry(entrada));
                if (!entrada.endsWith("/")) {
                    zos.write("x".getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return destino;
    }

    /** El zip de un día tal como lo arma SEPA: carpeta con la fecha y un zip por comercio adentro. */
    private static Path zipSepa(Path dir, String nombre, String fecha) throws IOException {
        return zip(dir, nombre,
                fecha + "/",
                fecha + "/sepa_1_comercio-sepa-12_" + fecha + "_09-05-10.zip",
                fecha + "/sepa_2_comercio-sepa-11_" + fecha + "_01-05-08.zip");
    }

    private static void modificado(Path archivo, String instante) throws IOException {
        Files.setLastModifiedTime(archivo, FileTime.from(Instant.parse(instante)));
    }

    private static SepaService sepa() {
        return new SepaService(new SepaComercioNombres());
    }

    @Nested
    @DisplayName("la fecha se lee de adentro del zip")
    class FechaDelZip {

        @Test
        @DisplayName("un zip como los del sitio: la carpeta YYYY-MM-DD/ dice la fecha")
        void carpetaConFecha(@TempDir Path dir) throws IOException {
            assertEquals("2026-09-15", SepaService.fechaDelZip(zipSepa(dir, "sepa_martes.zip", "2026-09-15")));
        }

        @Test
        @DisplayName("manda la carpeta aunque un zip interno se haya generado al día siguiente")
        void mandaLaCarpeta(@TempDir Path dir) throws IOException {
            Path z = zip(dir, "sepa_martes.zip",
                    "2026-09-15/",
                    "2026-09-15/sepa_1_comercio-sepa-12_2026-09-15_23-55-00.zip",
                    "2026-09-15/sepa_2_comercio-sepa-11_2026-09-16_00-10-00.zip");
            assertEquals("2026-09-15", SepaService.fechaDelZip(z));
        }

        @Test
        @DisplayName("sin carpeta, vale la fecha de los zips internos si es una sola")
        void sinCarpetaUnaSolaFecha(@TempDir Path dir) throws IOException {
            Path z = zip(dir, "x.zip",
                    "sepa_1_comercio-sepa-12_2026-09-15_09-05-10.zip",
                    "sepa_2_comercio-sepa-11_2026-09-15_01-05-08.zip");
            assertEquals("2026-09-15", SepaService.fechaDelZip(z));
        }

        @Test
        @DisplayName("sin carpeta y con internos de fechas distintas no se adivina")
        void sinCarpetaFechasDistintas(@TempDir Path dir) throws IOException {
            Path z = zip(dir, "x.zip",
                    "sepa_1_comercio-sepa-12_2026-09-15_09-05-10.zip",
                    "sepa_2_comercio-sepa-11_2026-09-16_01-05-08.zip");
            assertNull(SepaService.fechaDelZip(z));
        }

        @Test
        @DisplayName("un zip con fecha pero sin zips internos no es un dataset SEPA")
        void sinZipsInternos(@TempDir Path dir) throws IOException {
            assertNull(SepaService.fechaDelZip(zip(dir, "otro.zip", "2026-09-15/", "2026-09-15/leeme.txt")));
        }

        @Test
        @DisplayName("un archivo que no es zip, o uno cortado a la mitad, da null y no una excepción")
        void noEsZipOEstaCortado(@TempDir Path dir) throws IOException {
            Path texto = Files.writeString(dir.resolve("falso.zip"), "no soy un zip");
            assertNull(SepaService.fechaDelZip(texto));

            Path entero = zipSepa(dir, "entero.zip", "2026-09-15");
            byte[] bytes = Files.readAllBytes(entero);
            Path cortado = Files.write(dir.resolve("cortado.zip"), Arrays.copyOf(bytes, bytes.length / 2));
            assertNull(SepaService.fechaDelZip(cortado));
        }

        @Test
        @DisplayName("el zip real del sitio (SEPA_TEST_ZIP) dice su fecha sin mirar el nombre")
        void zipReal() {
            String ruta = System.getenv("SEPA_TEST_ZIP");
            Assumptions.assumeTrue(ruta != null && Files.isRegularFile(Path.of(ruta)),
                    "zip SEPA de prueba no presente (SEPA_TEST_ZIP)");
            String fecha = SepaService.fechaDelZip(Path.of(ruta));
            assertNotNull(fecha);
            assertTrue(fecha.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), fecha);
            System.out.println("fecha leida del zip real: " + fecha);
        }
    }

    @Nested
    @DisplayName("de la carpeta se toma el dataset más reciente")
    class ZipMasReciente {

        @Test
        @DisplayName("gana la fecha del contenido, no la del archivo: una copia vieja subida hoy sigue siendo vieja")
        void ganaLaFechaDelContenido(@TempDir Path dir) throws IOException {
            Path viejo = zipSepa(dir, "sepa_a.zip", "2026-09-08");
            Path nuevo = zipSepa(dir, "sepa_b.zip", "2026-09-15");
            modificado(viejo, "2026-09-20T10:00:00Z");
            modificado(nuevo, "2026-09-15T10:00:00Z");

            SepaService.ZipEnCarpeta elegido = SepaService.zipMasReciente(dir);

            assertEquals(nuevo, elegido.archivo());
            assertEquals("2026-09-15", elegido.fecha());
        }

        @Test
        @DisplayName("un .part a medio subir no se lee aunque sea más nuevo")
        void ignoraLoQueSeEstaSubiendo(@TempDir Path dir) throws IOException {
            Path completo = zipSepa(dir, "sepa_2026-09-15.zip", "2026-09-15");
            zipSepa(dir, "sepa_2026-09-16.zip.part", "2026-09-16");

            assertEquals(completo, SepaService.zipMasReciente(dir).archivo());
        }

        @Test
        @DisplayName("un zip roto en la carpeta se saltea y no tapa a los buenos")
        void salteaLosRotos(@TempDir Path dir) throws IOException {
            Path bueno = zipSepa(dir, "sepa_2026-09-15.zip", "2026-09-15");
            Files.writeString(dir.resolve("sepa_2026-09-16.zip"), "roto");

            assertEquals(bueno, SepaService.zipMasReciente(dir).archivo());
        }

        @Test
        @DisplayName("a igual fecha, el que se escribió último")
        void igualFechaElUltimo(@TempDir Path dir) throws IOException {
            Path primero = zipSepa(dir, "sepa_a.zip", "2026-09-15");
            Path segundo = zipSepa(dir, "sepa_b.zip", "2026-09-15");
            modificado(primero, "2026-09-15T12:00:00Z");
            modificado(segundo, "2026-09-15T08:00:00Z");

            assertEquals(primero, SepaService.zipMasReciente(dir).archivo());
        }

        @Test
        @DisplayName("sin ningún zip válido falla con un error que dice qué revisar")
        void carpetaSinZips(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("leeme.txt"), "nada");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> SepaService.zipMasReciente(dir));
            assertTrue(ex.getReason().contains("SEPA_RESOURCE_DIR"), ex.getReason());
        }

        @Test
        @DisplayName("una carpeta que no existe falla sin exponer la ruta del servidor")
        void carpetaInexistente(@TempDir Path dir) {
            Path noExiste = dir.resolve("no-existe");
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> SepaService.zipMasReciente(noExiste));
            assertTrue(ex.getReason().contains("SEPA_RESOURCE_DIR"), ex.getReason());
            assertFalse(ex.getReason().contains(noExiste.toString()), ex.getReason());
        }
    }

    @Nested
    @DisplayName("resolverRecurso ya no pide la fecha a mano")
    class ResolverRecurso {

        @Test
        @DisplayName("con SEPA_RESOURCE_DIR: el zip más reciente, con la fecha de su contenido")
        void conCarpeta(@TempDir Path dir) throws IOException {
            zipSepa(dir, "sepa_lunes.zip", "2026-09-14");
            Path martes = zipSepa(dir, "sepa_martes.zip", "2026-09-15");
            SepaService sepa = sepa();
            ReflectionTestUtils.setField(sepa, "resourceDirOverride", dir.toString());

            SepaService.SepaResource recurso = sepa.resolverRecurso(null);

            assertEquals("2026-09-15", recurso.fecha());
            assertEquals(martes.toUri().toString(), recurso.url());
        }

        @Test
        @DisplayName("con SEPA_RESOURCE_FILE = sepa_martes.zip ya no hace falta SEPA_RESOURCE_FECHA")
        void archivoSinFechaEnElNombre(@TempDir Path dir) throws IOException {
            Path martes = zipSepa(dir, "sepa_martes.zip", "2026-09-15");
            SepaService sepa = sepa();
            ReflectionTestUtils.setField(sepa, "resourceFileOverride", martes.toString());

            assertEquals("2026-09-15", sepa.resolverRecurso(null).fecha());
        }

        @Test
        @DisplayName("la fecha de adentro le gana a la del nombre")
        void contenidoLeGanaAlNombre(@TempDir Path dir) throws IOException {
            Path renombrado = zipSepa(dir, "sepa_2026-09-01.zip", "2026-09-15");
            SepaService sepa = sepa();
            ReflectionTestUtils.setField(sepa, "resourceFileOverride", renombrado.toString());

            assertEquals("2026-09-15", sepa.resolverRecurso(null).fecha());
        }

        @Test
        @DisplayName("SEPA_RESOURCE_FECHA explícita sigue mandando")
        void fechaExplicitaManda(@TempDir Path dir) throws IOException {
            Path martes = zipSepa(dir, "sepa_martes.zip", "2026-09-15");
            SepaService sepa = sepa();
            ReflectionTestUtils.setField(sepa, "resourceFileOverride", martes.toString());
            ReflectionTestUtils.setField(sepa, "resourceFechaOverride", "2026-09-10");

            assertEquals("2026-09-10", sepa.resolverRecurso(null).fecha());
        }

        @Test
        @DisplayName("el archivo fijo le gana a la carpeta: es lo que se pone a mano para forzar un dataset")
        void archivoLeGanaALaCarpeta(@TempDir Path dir) throws IOException {
            Path carpeta = Files.createDirectory(dir.resolve("vigilada"));
            zipSepa(carpeta, "sepa_nuevo.zip", "2026-09-20");
            Path forzado = zipSepa(dir, "sepa_forzado.zip", "2026-09-15");
            SepaService sepa = sepa();
            ReflectionTestUtils.setField(sepa, "resourceDirOverride", carpeta.toString());
            ReflectionTestUtils.setField(sepa, "resourceFileOverride", forzado.toString());

            SepaService.SepaResource recurso = sepa.resolverRecurso(null);

            assertEquals(forzado.toUri().toString(), recurso.url());
            assertEquals("2026-09-15", recurso.fecha());
        }
    }
}
