package ar.edu.ofertAR.service.ocr;

import ar.edu.ofertAR.config.OcrProperties;
import ar.edu.ofertAR.exception.OcrException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** El OCR caído un momento (Easypanel reiniciando el contenedor) no tiene que dejar el ticket fallido. */
class OcrClientRetryTest {

    private static final String URL = "http://ocr.test";
    private static final String TICKET = URL + "/api/v1/ocr/ticket";
    private static final String PROXY_PAGE = """
            <!DOCTYPE html>
            <html lang="en"><head><title>Not Found</title></head>
            <body><div>Service is not reachable</div></body></html>
            """;
    private static final String OK = """
            {"supermarket_name":"Coto","ticket_id":"1","items":[],"subtotal":100,"total_discounts":0,"total":100}
            """;

    private MockRestServiceServer server;
    private OcrClient client;
    private final List<Long> waits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OcrProperties props = new OcrProperties();
        props.setServiceUrl(URL);
        OcrTokenCache cache = new OcrTokenCache();
        cache.setToken("token", 59);
        client = new OcrClient(builder.build(), cache, props);
        client.sleeper = waits::add;
    }

    private void proxyDown(int count) {
        server.expect(times(count), requestTo(TICKET)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body(PROXY_PAGE));
    }

    @Test
    @DisplayName("con el contenedor reiniciándose, reintenta y procesa el ticket")
    void retriesWhileTheContainerRestarts() {
        proxyDown(2);
        server.expect(requestTo(TICKET)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertEquals("Coto", client.processTicket(new byte[] {1}, "image/jpeg").supermarketName());
        assertEquals(List.of(5_000L, 15_000L), waits);
        server.verify();
    }

    @Test
    @DisplayName("si sigue caído, se rinde con un mensaje corto y sin la página HTML")
    void givesUpWithAReadableMessage() {
        proxyDown(4);

        OcrException e = assertThrows(OcrException.class, () -> client.processTicket(new byte[] {1}, "image/jpeg"));
        assertTrue(e.getMessage().contains("no está corriendo"), e.getMessage());
        assertFalse(e.getMessage().contains("<html"), e.getMessage());
        assertEquals(3, waits.size());
        server.verify();
    }

    @Test
    @DisplayName("un 502 del propio OCR, en JSON, no se reintenta: se pagaría dos veces")
    void doesNotRetryTheOcrsOwnError() {
        server.expect(requestTo(TICKET)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"OpenRouter returned invalid JSON\"}"));

        OcrException e = assertThrows(OcrException.class, () -> client.processTicket(new byte[] {1}, "image/jpeg"));
        assertTrue(e.getMessage().contains("OpenRouter returned invalid JSON"), e.getMessage());
        assertTrue(waits.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("un 503 también es un servicio que todavía no está")
    void retriesA503() {
        server.expect(requestTo(TICKET)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(TICKET)).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertEquals("Coto", client.processTicket(new byte[] {1}, "image/jpeg").supermarketName());
        assertEquals(1, waits.size());
    }
}
