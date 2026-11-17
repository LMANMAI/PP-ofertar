package ar.edu.ofertAR.service;

import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los importes de cada línea y el total del ticket, con renglones de un ticket
 * de Makro: "2x4048,99 / ACEITE GIRASOL COCINERO 8098,00 / MERCADOPAGO 10% OF
 * -809,80". El ticket 58 (Makro, $302.306 pagados) quedó en $248.702 porque el
 * descuento de cada línea se restaba dos veces y la foto con el TOTAL no tenía
 * un subtotal que cerrara.
 */
class TicketProcessingServiceImportesTest {

    private static OcrItem item(String price, String original, String discount) {
        return new OcrItem("ACEITE GIRASOL COCINERO", "ACEITE GIRASOL COCINERO (21.00)",
                price == null ? null : new BigDecimal(price),
                original == null ? null : new BigDecimal(original),
                "7790060023684", new BigDecimal("2"), "Almacen",
                discount == null ? null : new BigDecimal(discount), discount == null ? null : "MERCADOPAGO 10% OF");
    }

    private static OcrResult page(String subtotal, String discounts, String total) {
        return new OcrResult("Makro", "", new BigDecimal(subtotal), new BigDecimal(discounts),
                new BigDecimal(total), List.of());
    }

    @Test
    @DisplayName("con neto y bruto, la línea vale el bruto impreso")
    void netoYBruto() {
        assertEquals(new BigDecimal("8098.00"), TicketProcessingService.grossLine(item("7288.20", "8098.00", "809.80")));
    }

    @Test
    @DisplayName("si el OCR ya devolvió el bruto en los dos campos, no se suma el descuento otra vez")
    void brutoEnLosDos() {
        assertEquals(new BigDecimal("8098.00"), TicketProcessingService.grossLine(item("8098.00", "8098.00", "809.80")));
    }

    @Test
    @DisplayName("sin precio original, el bruto es el neto más el descuento")
    void sinOriginal() {
        assertEquals(new BigDecimal("8098.00"), TicketProcessingService.grossLine(item("7288.20", null, "809.80")));
    }

    @Test
    @DisplayName("un precio original menor que el neto es el unitario mal puesto: se ignora")
    void originalUnitario() {
        assertEquals(new BigDecimal("8098.00"), TicketProcessingService.grossLine(item("7288.20", "4048.99", "-809.80")));
    }

    @Test
    @DisplayName("sin descuento la línea vale su precio")
    void sinDescuento() {
        assertEquals(new BigDecimal("4999.01"), TicketProcessingService.grossLine(item("4999.01", "4999.01", null)));
        assertEquals(new BigDecimal("4999.01"), TicketProcessingService.grossLine(item("4999.01", null, null)));
    }

    @Test
    @DisplayName("sin precio la línea vale cero en vez de romper el ticket")
    void sinPrecio() {
        assertEquals(BigDecimal.ZERO, TicketProcessingService.grossLine(item(null, null, null)));
    }

    @Test
    @DisplayName("el TOTAL impreso se usa aunque el subtotal de esa foto no cierre")
    void totalSinSubtotalCoherente() {
        List<OcrResult> pages = List.of(page("0", "0", "0"), page("0", "0", "0"), page("0", "13799.59", "141421.40"));
        assertNull(TicketProcessingService.printedTotals(pages));
        assertEquals(new BigDecimal("141421.40"), TicketProcessingService.printedTotalOnly(pages));
    }

    @Test
    @DisplayName("sin ninguna línea TOTAL no hay total impreso")
    void sinTotal() {
        assertNull(TicketProcessingService.printedTotalOnly(List.of(page("0", "0", "0"))));
    }
}
