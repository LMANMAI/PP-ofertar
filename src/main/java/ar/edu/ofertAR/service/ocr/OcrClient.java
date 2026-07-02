package ar.edu.ofertAR.service.ocr;

import ar.edu.ofertAR.config.OcrProperties;
import ar.edu.ofertAR.exception.OcrException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrClient {

    private final RestClient restClient;
    private final OcrTokenCache tokenCache;
    private final OcrProperties ocrProperties;

    public OcrResult processTicket(byte[] fileBytes, String contentType) {
        String fileType = contentType != null && contentType.equals("application/pdf") ? "pdf" : "image";
        String base64Content = Base64.getEncoder().encodeToString(fileBytes);

        String token = getValidToken();

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                var response = (Map<String, Object>) restClient.post()
                        .uri(ocrProperties.getServiceUrl() + "/api/v1/ocr/ticket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .body(Map.of("file_type", fileType, "content", base64Content))
                        .retrieve()
                        .body(Map.class);

                return mapToResult(response);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 401) {
                    tokenCache.invalidate();
                    token = getValidToken();
                    continue;
                }
                throw new OcrException("Error del servicio OCR: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
            } catch (OcrException e) {
                throw e;
            } catch (Exception e) {
                throw new OcrException("Error al comunicarse con el servicio OCR: " + e.getMessage(), e);
            }
        }

        throw new OcrException("No se pudo autenticar con el servicio OCR despues de varios intentos");
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
                        toInt(item.get("quantity")),
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

    private int toInt(Object value) {
        if (value == null) return 1;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
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
            int quantity,
            String category,
            BigDecimal discountAmount,
            String discountDescription
    ) {}
}
