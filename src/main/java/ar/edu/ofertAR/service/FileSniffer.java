package ar.edu.ofertAR.service;

import java.util.Optional;

/**
 * Detecta el tipo real de un archivo por sus primeros bytes. El Content-Type
 * y el nombre del archivo los elige quien sube el request, así que no sirven
 * para decidir qué se acepta ni con qué extensión se guarda.
 */
public final class FileSniffer {

    public record Detected(String extension, String mimeType) {}

    /** Bytes suficientes para todas las firmas de abajo. */
    public static final int HEADER_BYTES = 16;

    private FileSniffer() {}

    public static Optional<Detected> detect(byte[] h) {
        if (h == null) {
            return Optional.empty();
        }
        if (startsWith(h, 0xFF, 0xD8, 0xFF)) {
            return of("jpg", "image/jpeg");
        }
        if (startsWith(h, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return of("png", "image/png");
        }
        if (startsWith(h, 'G', 'I', 'F', '8')) {
            return of("gif", "image/gif");
        }
        if (startsWith(h, '%', 'P', 'D', 'F', '-')) {
            return of("pdf", "application/pdf");
        }
        if (startsWith(h, 'R', 'I', 'F', 'F') && at(h, 8, 'W', 'E', 'B', 'P')) {
            return of("webp", "image/webp");
        }
        // HEIC/HEIF (fotos de iPhone): caja "ftyp" en el offset 4 con una marca conocida.
        if (at(h, 4, 'f', 't', 'y', 'p')
                && (at(h, 8, 'h', 'e', 'i', 'c') || at(h, 8, 'h', 'e', 'i', 'x')
                || at(h, 8, 'h', 'e', 'i', 'f') || at(h, 8, 'm', 'i', 'f', '1')
                || at(h, 8, 'm', 's', 'f', '1'))) {
            return of("heic", "image/heic");
        }
        return Optional.empty();
    }

    private static Optional<Detected> of(String ext, String mime) {
        return Optional.of(new Detected(ext, mime));
    }

    private static boolean startsWith(byte[] h, int... sig) {
        return at(h, 0, sig);
    }

    private static boolean at(byte[] h, int offset, int... sig) {
        if (h.length < offset + sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((h[offset + i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
