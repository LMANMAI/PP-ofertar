package ar.edu.ofertAR.service;

import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dos fotos de un ticket de Coto del 13/11/2026 tal como las devolvió el OCR: en
 * la primera los códigos quedaron pegados a la descripción; en la segunda,
 * aparte y con el código interno delante, y con varios dígitos y letras mal
 * leídos. Antes se sumaba todo dos veces y el total quedaba en 0.
 */
class TicketProcessingServiceOcrDriftTest {

    private static OcrItem line(String description, String price, String code) {
        return new OcrItem(description, description, new BigDecimal(price), new BigDecimal(price),
                code, BigDecimal.ONE, "Hogar", null, null);
    }

    private static final List<OcrItem> FIRST = List.of(
            line("ACOLCHADO FLANNEL TEXGRIS QUEEN 0000614920 0280000614920", "69999.30", "0280000614920"),
            line("CUCHILLO SANTOKU LARGO BHANGO ABS 0000496132 02800004961325", "5499.00", "02800004961325"),
            line("4,000 POR 110,00 BSA.CAN.VERDE PROV 55X60X20 NF POR 1 UNIDAD 0000651704 023000065 7044", "440.00", "0230000657044"),
            line("2,000 POR 18999,00 CORTINA BAÑO EST MICRO DOBC/GAN 180X180 0000485698 02800004856980", "37998.00", "02800004856980"),
            line("6,000 POR 3299,00 VASO FROST 0000602123 00078917233026", "9897.00", "00078917233026"),
            line("TOALLA 450GR MARRON/BLANCO 50X90CM 0000594938 02800005949384", "12999.00", "02800005949384"),
            line("TOALLA 450GR MARRON/GRIS 50X90CM 0000594943 02800005949438", "12999.00", "02800005949438"),
            line("ALFOMBRA DE BAÑO BUCLENEGRO 45X70 0000603252 02800006032526", "14999.00", "02800006032526"),
            line("ALFOMBRA DE BAÑO BROD GRIS 50X70 0000577485 02800005774856", "12599.40", "02800005774856"),
            line("2,000 POR 3999,00 REPASADOR TOALLA MINICUADROS 42 POR 60 0000498820 02800004988209", "7998.00", "02800004988209"),
            line("JABON LIQ.BIO ENZIMASSKIP DOY 3 LTR 0000531050 07791290792791", "3645.89", "07791290792791"),
            line("2,000 POR 4292,99 P.HIGIENICO DOBLE HOJAPLUS HIGIENOL X4 UN. 3 0000580544 077902500 5536", "6435.98", "0779025005536"),
            line("2,000 POR 1690,00 SODA SIERRA DE LOS PADRES SIF 1,75 LTR 0000558892 07798069734314", "3380.00", "07798069734314"),
            line("YOG.GRI.SAB.NAT.S/DUL.YOGURISIMO POT 300 GRM 000061239 07791337040017", "2975.68", "07791337040017"),
            line("AMARGO TERMA SERRANO CEROBOT 1.35 LITROS 000028855 07790950133325", "2700.00", "07790950133325"),
            line("3,000 POR 7999,00 JARRO MUG 0000618150 02800006181509", "16800.90", "02800006181509"));

    private static final List<OcrItem> SECOND = List.of(
            line("CORTINA BAÑO EST MICRO DDC/GAN 180X180", "37998.00", "0000640498 02800004856980"),
            line("VASO FROST", "19794.00", "0000602123 00078917213026"),
            line("TOALLA 450GR MARROBLANCO 50X90CM", "12999.00", "00001936 02800005949384"),
            line("TOALLA 450GR MARROGRIS 50X90CM", "12999.00", "0000594943 02800005949438"),
            line("ALFOMBRA DE BAÑO BUEN NEGRO 45X70", "14999.00", "0000603257 02800006032528"),
            line("ALFOMBRA DE BAJO GRIS 50X70", "20999.00", "0000577485 02800005774856"),
            line("REPASADOR TOALLA MIN CUADROS 42 POR 60", "7998.00", "0000498820 02800004988209"),
            line("JABON LIQ BIO ENZIMASSKIP DOY 3 LIR", "5608.99", "0000531050 07791290792791"),
            line("P.HIGIENICO DOBLE HOJAS PLUS HIGIENOL X4 UN 3", "65.98", "0000580544 07790250055386"),
            line("SODA SIERRA DE LOS PADRES SIF 1.75 LIR", "3380.00", "0000558892 07798065734314"),
            line("YOG.GRIE.SAB.NAT.S/ENDUL.YOGURISIMO POT 300 GRM", "4635.00", "000061239 0779133700017"),
            line("AMARGD TERMA SERRANO CERUBOT 1.35 LITROS", "2700.00", "0000288551 07790950133325"),
            line("JARRU MUG", "23997.00", "0000618150 02800006181509"),
            line("FIDEOS MATARAZZO TIRABUZON 500 GRM", "1890.00", "0000512233 07790070318107"),
            line("ARROZ GALLO ORO 1 KGM", "2450.00", "0000400121 07790070227737"));

    @Test
    @DisplayName("las dos fotos del ticket real se unen y cada producto queda una sola vez")
    void joinsTheRealTicket() {
        List<OcrItem> merged = TicketProcessingService.mergePages(List.of(FIRST, SECOND));

        assertEquals(18, merged.size());
        assertEquals("FIDEOS MATARAZZO TIRABUZON 500 GRM", merged.get(16).description());
        assertEquals("ARROZ GALLO ORO 1 KGM", merged.get(17).description());
    }

    @Test
    @DisplayName("el EAN se reconoce aunque venga con el código interno delante")
    void readsTheEanBehindTheInternalCode() {
        assertEquals(TicketProcessingService.eanOf("02800004856980"),
                TicketProcessingService.eanOf("0000640498 02800004856980"));
    }

    @Test
    @DisplayName("la descripción se compara sin la cantidad delante ni los códigos detrás")
    void comparesTheBareDescription() {
        assertEquals("VASOFROST", TicketProcessingService.bareDescription("6,000 POR 3299,00 VASO FROST 0000602123 00078917233026"));
    }

    @Test
    @DisplayName("dos productos que sólo cambian de color no son la misma línea")
    void doesNotMergeSiblingProducts() {
        OcrItem white = line("TOALLA 450GR MARRON/BLANCO 50X90CM", "12999.00", "");
        OcrItem grey = line("TOALLA 450GR MARRON/GRIS 50X90CM", "12999.00", "");
        assertFalse(TicketProcessingService.similarLine(white, grey));
    }

    @Test
    @DisplayName("dos fotos de partes distintas del ticket no se unen por parecido")
    void doesNotJoinUnrelatedPages() {
        List<OcrItem> other = List.of(
                line("LECHE ENTERA LA SERENISIMA 1 LT", "1899.00", "07790742656018"),
                line("PAN LACTAL BIMBO 550 GRM", "3150.00", "07796989001234"),
                line("YERBA PLAYADITO 1 KGM", "5250.00", "07793704000928"),
                line("ACEITE COCINERO 1,5 LTR", "7129.00", "07790060023684"));
        assertEquals(FIRST.size() + other.size(),
                TicketProcessingService.mergePages(List.of(FIRST, other)).size());
    }

    @Test
    @DisplayName("una página con el total en 0 no le gana a la suma de los ítems")
    void ignoresAZeroTotal() {
        OcrResult zeros = new OcrResult("Coto", "2208-06954701", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        assertNull(TicketProcessingService.printedTotals(List.of(zeros, zeros)));
    }
}
