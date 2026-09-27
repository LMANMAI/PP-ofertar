package ar.edu.ofertAR.service.horario;

import ar.edu.ofertAR.dto.response.FranjaHorariaResponse;
import ar.edu.ofertAR.dto.response.HorariosResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduce las celdas {@code sucursales_<día>_horario_atencion} de SEPA a franjas.
 *
 * <p>Cada comercio escribe el horario a mano y a su manera. Sobre las ~20.900 celdas
 * del dataset (septiembre 2026) las formas son, de más a menos frecuente:
 * "08:00 a 22:00", "8:30 a 22:00", "08:00 A 22:00", "08:30:00 a 20:30:00",
 * turno partido con "-", "y", "Y" o "y de" ("8:30  a 14:00 y de 16:00 a  21:00"),
 * horas sin minutos ("8 a 12 y 16 a 21"), "cerrado" en cualquier caja, celdas
 * vacías y espacios de relleno al final ("9 a 21:30                     ").
 *
 * <p><b>Nunca adivina.</b> La app le va a decir a alguien que un súper está abierto
 * en base a esto, así que ante la duda la celda es {@code null} ("no informado") y
 * no un rango aproximado. Quedan en null, a propósito:
 * <ul>
 *   <li>Minutos con un solo dígito después de un punto ("15.3", "12.3", de La
 *       Agrícola Regional): puede ser 15:30, 15:03 o 15,3 horas. "10.30" (dos
 *       dígitos, una celda de Carrefour) sí es inequívoco y se acepta.</li>
 *   <li>Apertura igual al cierre. Farmacity declara "11:24 a 11:24", "11:36 a
 *       11:36"... (una marca de tiempo, no un horario) y 279 celdas "00:00 a 00:00",
 *       que puede ser 24 horas o un valor de relleno: no hay forma de saberlo.</li>
 *   <li>Franjas desordenadas o que se pisan, y cualquier cosa que no sea una lista
 *       de "hora a hora" ("cerra a", "08 a 13 a 15.3 a 20.3").</li>
 *   <li>Un tramo que cruza la medianoche escrito sin minutos ("9 a 1"): lo más
 *       probable es que sea 9 a 13 en notación de 12 horas, no 9 a 1 de la mañana.</li>
 *   <li>Segundos distintos de cero: nadie cierra a las 21:00:30; es otra cosa.</li>
 * </ul>
 *
 * <p>El cierre "00:00" se normaliza a "24:00" ("8:30 a 00:00" de COTO): es la misma
 * hora y así la app no confunde "cierra a medianoche" con "cruza la medianoche".
 */
public final class HorarioParser {

    /** Orden de las columnas de SEPA y de {@link HorariosResponse}. */
    public static final List<String> DIAS =
            List.of("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo");

    private static final int DIA_MINUTOS = 24 * 60;

    /** Entre franjas: "-" con o sin espacios, "y", "y de". */
    private static final Pattern SEPARADOR_FRANJAS = Pattern.compile("\\s*-\\s*|\\s+y\\s+(?:de\\s+)?");

    private static final Pattern FRANJA = Pattern.compile("(?:de\\s+)?(\\S+)\\s+a\\s+(\\S+)");

    /** 8, 08, 8:30, 08:30:00 o 10.30. Nunca "15.3": ver la clase. */
    private static final Pattern HORA = Pattern.compile("(\\d{1,2})(?::(\\d{2})(?::(\\d{2}))?|\\.(\\d{2}))?");

    private HorarioParser() {
    }

    /**
     * Los siete días de una sucursal, en el orden de {@link #DIAS}.
     *
     * @return null si ningún día se pudo entender
     */
    public static HorariosResponse parsearSemana(List<String> celdas) {
        if (celdas == null || celdas.size() != DIAS.size()) {
            return null;
        }
        List<List<FranjaHorariaResponse>> dias = new ArrayList<>(DIAS.size());
        for (String celda : celdas) {
            dias.add(parsear(celda));
        }
        return HorariosResponse.deDias(dias);
    }

    /**
     * Una celda de horario.
     *
     * @return las franjas en orden; lista vacía si dice cerrado; null si está vacía
     *         o no se entiende con certeza
     */
    public static List<FranjaHorariaResponse> parsear(String celda) {
        if (celda == null) {
            return null;
        }
        String texto = celda.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (texto.isEmpty()) {
            return null;
        }
        if (texto.equals("cerrado")) {
            return List.of();
        }

        List<int[]> franjas = new ArrayList<>();
        for (String parte : SEPARADOR_FRANJAS.split(texto, -1)) {
            int[] franja = franja(parte);
            if (franja == null) {
                return null;
            }
            franjas.add(franja);
        }
        if (!ordenadas(franjas)) {
            return null;
        }

        List<FranjaHorariaResponse> out = new ArrayList<>(franjas.size());
        for (int[] f : franjas) {
            out.add(new FranjaHorariaResponse(formatear(f[0]), formatear(f[1])));
        }
        return List.copyOf(out);
    }

    /** [desde, hasta] en minutos desde las 00:00, con el cierre a medianoche como 1440. */
    private static int[] franja(String texto) {
        Matcher m = FRANJA.matcher(texto);
        if (!m.matches()) {
            return null;
        }
        Hora desde = hora(m.group(1));
        Hora hasta = hora(m.group(2));
        if (desde == null || hasta == null || desde.minutos() >= DIA_MINUTOS) {
            return null; // "24:00 a ..." no es una apertura
        }
        if (desde.minutos() == hasta.minutos()) {
            return null; // "11:24 a 11:24", "00:00 a 00:00": ver la clase ("00:00 a 24:00" sí vale)
        }
        int cierre = hasta.minutos() == 0 ? DIA_MINUTOS : hasta.minutos();
        boolean cruzaMedianoche = cierre < desde.minutos();
        if (cruzaMedianoche && (desde.sinMinutos() || hasta.sinMinutos())) {
            return null; // "9 a 1": probablemente 9 a 13, no 9 a 1 de la mañana
        }
        return new int[]{desde.minutos(), cierre};
    }

    private record Hora(int minutos, boolean sinMinutos) {
    }

    private static Hora hora(String texto) {
        Matcher m = HORA.matcher(texto);
        if (!m.matches()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        String minutos = m.group(2) != null ? m.group(2) : m.group(4);
        int min = minutos == null ? 0 : Integer.parseInt(minutos);
        if (m.group(3) != null && Integer.parseInt(m.group(3)) != 0) {
            return null;
        }
        if (min > 59 || h > 24 || (h == 24 && min != 0)) {
            return null;
        }
        return new Hora(h * 60 + min, minutos == null);
    }

    /**
     * Las franjas de un día van en orden y sin pisarse. Solo la última puede cruzar la
     * medianoche, y entonces tiene que cerrar antes de que abra la primera.
     */
    private static boolean ordenadas(List<int[]> franjas) {
        for (int i = 0; i < franjas.size(); i++) {
            int[] f = franjas.get(i);
            boolean cruza = f[1] < f[0];
            boolean ultima = i == franjas.size() - 1;
            if (cruza && !ultima) {
                return false;
            }
            if (i > 0 && f[0] < franjas.get(i - 1)[1]) {
                return false;
            }
            if (cruza && franjas.size() > 1 && f[1] > franjas.get(0)[0]) {
                return false;
            }
        }
        return true;
    }

    private static String formatear(int minutos) {
        return String.format(Locale.ROOT, "%02d:%02d", minutos / 60, minutos % 60);
    }
}
