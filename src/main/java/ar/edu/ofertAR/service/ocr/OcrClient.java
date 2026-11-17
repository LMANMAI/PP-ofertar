package ar.edu.ofertAR.service.ocr;

import ar.edu.ofertAR.config.OcrProperties;
import ar.edu.ofertAR.exception.OcrException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrClient {

    private final RestClient restClient;
    private final OcrTokenCache tokenCache;
    private final OcrProperties ocrProperties;

    /** Esperas entre reintentos cuando el servicio OCR no está disponible por un
     * momento: lo que tarda Easypanel en reiniciar o redesplegar el contenedor. */
    static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000};

    /** Cuánto del cuerpo de un error entra en el mensaje y en el log. */
    private static final int MAX_DETAIL = 300;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Reemplazable en los tests, para no esperar de verdad. */
    Sleeper sleeper = Thread::sleep;

    public OcrResult processTicket(byte[] fileBytes, String contentType) {
        String fileType = contentType != null && contentType.equals("application/pdf") ? "pdf" : "image";
        String base64Content = Base64.getEncoder().encodeToString(fileBytes);

        boolean reauthenticated = false;
        int retries = 0;
        while (true) {
            try {
                String token = getValidToken();
                @SuppressWarnings("unchecked")
                var response = (Map<String, Object>) restClient.post()
                        .uri(ocrProperties.getServiceUrl() + "/api/v1/ocr/ticket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .body(Map.of("file_type", fileType, "content", base64Content))
                        .retrieve()
                        .body(Map.class);

                return mapToResult(response);
            // HttpStatusCodeException, not HttpClientErrorException: the
            // latter is 4xx only, so a 5xx from the OCR service fell through
            // to the generic catch below and was reported as a connectivity
            // problem, hiding both the status and the response body.
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 401 && !reauthenticated) {
                    tokenCache.invalidate();
                    reauthenticated = true;
                    continue;
                }
                if (isUnavailable(e) && retries < RETRY_DELAYS_MS.length) {
                    waitBeforeRetry(retries++, describe(e));
                    continue;
                }
                throw new OcrException("Error del servicio OCR: " + describe(e), e);
            } catch (OcrException e) {
                // El login también pasa por el proxy y también puede encontrar el servicio caído.
                if (isUnavailable(e.getCause()) && retries < RETRY_DELAYS_MS.length) {
                    waitBeforeRetry(retries++, e.getMessage());
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (isUnavailable(e) && retries < RETRY_DELAYS_MS.length) {
                    waitBeforeRetry(retries++, e.getMessage());
                    continue;
                }
                throw new OcrException("Error al comunicarse con el servicio OCR: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Si la falla es que el servicio no está, no que procesó mal el ticket. El
     * 502 del propio OCR (un error de OpenRouter) viene en JSON y no se reintenta:
     * repetiría el mismo error y se pagaría dos veces. El de Easypanel, con el
     * contenedor caído o reiniciándose, es una página HTML.
     */
    static boolean isUnavailable(Throwable e) {
        if (e instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            return status == 503 || status == 504 || (status == 502 && isHtml(http.getResponseBodyAsString()));
        }
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getCause();
            return cause instanceof ConnectException || cause instanceof UnknownHostException;
        }
        return false;
    }

    private void waitBeforeRetry(int retry, String why) {
        long delay = RETRY_DELAYS_MS[retry];
        log.warn("Servicio OCR no disponible ({}); reintento {} de {} en {} s",
                why, retry + 1, RETRY_DELAYS_MS.length, delay / 1000);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OcrException("Se interrumpió la espera para reintentar el OCR", ie);
        }
    }

    private static boolean isHtml(String body) {
        if (body == null) return false;
        String head = body.stripLeading().toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    private static final Pattern TITLE = Pattern.compile("<title>\\s*(.*?)\\s*</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * El estado y un resumen del cuerpo, nunca una página HTML entera: cuando el
     * contenedor está caído, Easypanel responde con su página "Service is not
     * reachable" y terminaba completa en el log.
     */
    static String describe(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (isHtml(body)) {
            String hint = body.contains("Service is not reachable")
                    ? "el contenedor del OCR no está corriendo (Easypanel: Service is not reachable)"
                    : "página HTML del proxy";
            Matcher title = TITLE.matcher(body);
            return e.getStatusCode() + " - " + hint + (title.find() ? " [" + title.group(1) + "]" : "");
        }
        String detail = body == null ? "" : body.strip();
        return e.getStatusCode() + " - " + (detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) + "…" : detail);
    }

    private String getValidToken() {
        String cached = tokenCache.getToken();
        if (cached != null) {
            return cached;
        }
        return login();
    }

    @SuppressWarnings("unchecked")
    private String login() {
        try {
            var response = (Map<String, Object>) restClient.post()
                    .uri(ocrProperties.getServiceUrl() + "/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", ocrProperties.getUsername(), "password", ocrProperties.getPassword()))
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("access_token")) {
                throw new OcrException("Respuesta de login invalida del servicio OCR");
            }

            String token = (String) response.get("access_token");
            tokenCache.setToken(token, 59);
            log.info("Autenticado exitosamente con el servicio OCR");
            return token;
        } catch (OcrException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            throw new OcrException("Error al autenticar con el servicio OCR: " + describe(e), e);
        } catch (Exception e) {
            throw new OcrException("Error al autenticar con el servicio OCR: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private OcrResult mapToResult(Map<String, Object> response) {
        List<Map<String, Object>> itemsRaw = (List<Map<String, Object>>) response.get("items");
        List<OcrItem> items = new ArrayList<>();

        if (itemsRaw != null) {
            for (Map<String, Object> item : itemsRaw) {
                items.add(new OcrItem(
                        (String) item.get("description"),
                        (String) item.get("raw_description"),
                        toBigDecimal(item.get("price")),
                        toBigDecimal(item.get("original_price")),
                        (String) item.get("code"),
                        toQuantity(item.get("quantity")),
                        (String) item.get("category"),
                        toBigDecimal(item.get("discount") instanceof Map
                                ? ((Map<String, Object>) item.get("discount")).get("amount")
                                : null),
                        item.get("discount") instanceof Map
                                ? (String) ((Map<String, Object>) item.get("discount")).get("description")
                                : null
                ));
            }
        }

        return new OcrResult(
                (String) response.getOrDefault("supermarket_name", ""),
                (String) response.getOrDefault("ticket_id", ""),
                toBigDecimal(response.get("subtotal")),
                toBigDecimal(response.get("total_discounts")),
                toBigDecimal(response.get("total")),
                items
        );
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    /**
     * Kept decimal on purpose: a line sold by weight comes back as 0.52, and
     * truncating that to an int silently turned half a kilo into "1 unit"
     * with the whole line's price as its unit price.
     */
    private BigDecimal toQuantity(Object value) {
        if (value == null) return BigDecimal.ONE;
        BigDecimal qty;
        if (value instanceof BigDecimal bd) {
            qty = bd;
        } else if (value instanceof Number n) {
            qty = BigDecimal.valueOf(n.doubleValue());
        } else {
            try {
                qty = new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return BigDecimal.ONE;
            }
        }
        return qty.signum() > 0 ? qty : BigDecimal.ONE;
    }

    public record OcrResult(
            String supermarketName,
            String ticketId,
            BigDecimal subtotal,
            BigDecimal totalDiscounts,
            BigDecimal total,
            List<OcrItem> items
    ) {}

    public record OcrItem(
            String description,
            String rawDescription,
            BigDecimal price,
            BigDecimal originalPrice,
            String code,
            BigDecimal quantity,
            String category,
            BigDecimal discountAmount,
            String discountDescription
    ) {}
}
