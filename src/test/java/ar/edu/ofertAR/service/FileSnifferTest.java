package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSnifferTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[Math.max(v.length, 16)];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[Math.max(s.length(), 16)];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    @Test
    @DisplayName("reconoce jpg, png, gif y pdf por firma")
    void firmasBasicas() {
        assertEquals("jpg", FileSniffer.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)).orElseThrow().extension());
        assertEquals("png", FileSniffer.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).orElseThrow().extension());
        assertEquals("gif", FileSniffer.detect(ascii("GIF89a")).orElseThrow().extension());
        assertEquals("application/pdf", FileSniffer.detect(ascii("%PDF-1.7")).orElseThrow().mimeType());
    }

    @Test
    @DisplayName("reconoce webp y heic")
    void webpYHeic() {
        assertEquals("webp", FileSniffer.detect(ascii("RIFF\0\0\0\0WEBP")).orElseThrow().extension());
        assertEquals("heic", FileSniffer.detect(ascii("\0\0\0\0ftypheic")).orElseThrow().extension());
    }

    @Test
    @DisplayName("rechaza ejecutables, scripts y html aunque declaren image/png")
    void rechazaOtros() {
        assertTrue(FileSniffer.detect(ascii("MZ\u0090\0")).isEmpty());
        assertTrue(FileSniffer.detect(ascii("<?php echo 1;")).isEmpty());
        assertTrue(FileSniffer.detect(ascii("<html><script>")).isEmpty());
        assertTrue(FileSniffer.detect(new byte[0]).isEmpty());
        assertTrue(FileSniffer.detect(null).isEmpty());
    }
}
