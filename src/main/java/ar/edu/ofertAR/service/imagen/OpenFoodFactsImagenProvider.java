package ar.edu.ofertAR.service.imagen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Open Food Facts: base colaborativa y abierta de productos (CC-BY-SA).
 * Sin API key. Buena cobertura en alimentos y bebidas, floja en limpieza,
 * perfumería y productos de marca propia.
 *
 * <p>Es el proveedor primario porque es el único con términos de uso que
 * habilitan explícitamente este consumo.
 */
@Component
@Order(1)
@Slf4j
public class OpenFoodFactsImagenProvider implements ImagenProvider {

    private static final String NOMBRE = "openfoodfacts";

    @Value("${imagenes.openfoodfacts.url:https://world.openfoodfacts.org/api/v2/product/%s.json}")
    private String urlTemplate;

    /** OFF pide un User-Agent identificatorio; sin él bloquean por abuso. */
    @Value("${imagenes.openfoodfacts.user-agent:OfertAR/1.0 (https://github.com/LMANMAI/PP-ofertar)}")
    private String userAgent;

    @Value("${imagenes.openfoodfacts.requests-por-segundo:4}")
    private double requestsPorSegundo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Throttle throttle;

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public Optional<String> buscarImagen(String ean) throws ImagenProviderException {
        String url = String.format(urlTemplate, ean)
                + "?fields=code,image_front_url,image_front_small_url,image_url";
        try {
            throttle().esperarTurno();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 404 = el EAN no existe en OFF. No es un error: es una respuesta.
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new ImagenProviderException("OFF respondió HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("status").asInt(0) != 1) {
                return Optional.empty();
            }
            JsonNode producto = root.path("product");
            for (String campo : new String[]{"image_front_url", "image_url", "image_front_small_url"}) {
                String valor = producto.path(campo).asText("");
                if (!valor.isBlank()) {
                    return Optional.of(valor);
                }
            }
            // El producto existe pero nadie le sacó foto todavía.
            return Optional.empty();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImagenProviderException("Interrumpido consultando OFF para " + ean, e);
        } catch (IOException e) {
            throw new ImagenProviderException("Fallo consultando OFF para " + ean + ": " + e.getMessage(), e);
        }
    }

    private synchronized Throttle throttle() {
        if (throttle == null) {
            throttle = new Throttle(requestsPorSegundo);
        }
        return throttle;
    }
}
