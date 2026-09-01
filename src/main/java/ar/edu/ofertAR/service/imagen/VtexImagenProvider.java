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
import java.util.List;
import java.util.Optional;

/**
 * Fallback: catálogos VTEX de los supermercados argentinos (Coto, Carrefour,
 * Jumbo, Disco...). Cubren productos locales que Open Food Facts no tiene
 * —limpieza, perfumería, marca propia—.
 *
 * <p>OJO: es una API pública pero no documentada como tal. Puede cambiar sin
 * aviso y su uso masivo puede chocar con los términos del sitio. Por eso va
 * como fallback, detrás de {@code imagenes.vtex.enabled}, con throttle propio
 * y consultando un solo host por vez hasta encontrar la imagen.
 */
@Component
@Order(2)
@Slf4j
public class VtexImagenProvider implements ImagenProvider {

    private static final String NOMBRE = "vtex";

    @Value("${imagenes.vtex.enabled:true}")
    private boolean enabled;

    /** Hosts a consultar, en orden. */
    @Value("${imagenes.vtex.hosts:www.cotodigital3.com.ar,www.carrefour.com.ar,www.jumbo.com.ar,www.disco.com.ar}")
    private List<String> hosts;

    @Value("${imagenes.vtex.requests-por-segundo:2}")
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
        if (!enabled || hosts == null || hosts.isEmpty()) {
            return Optional.empty();
        }

        int fallos = 0;
        for (String host : hosts) {
            try {
                Optional<String> imagen = consultarHost(host.trim(), ean);
                if (imagen.isPresent()) {
                    return imagen;
                }
            } catch (ImagenProviderException e) {
                // Un host caído no invalida a los demás: seguimos con el siguiente.
                fallos++;
                log.debug("VTEX {} falló para {}: {}", host, ean, e.getMessage());
            }
        }

        // Si TODOS fallaron no podemos afirmar que el producto no exista:
        // lo marcamos como error para que se reintente más adelante.
        if (fallos == hosts.size()) {
            throw new ImagenProviderException("Todos los hosts VTEX fallaron para " + ean);
        }
        return Optional.empty();
    }

    private Optional<String> consultarHost(String host, String ean) throws ImagenProviderException {
        String url = "https://" + host
                + "/api/catalog_system/pub/products/search?fq=alternateIds_Ean:" + ean;
        try {
            throttle().esperarTurno();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "OfertAR/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // VTEX devuelve 206 Partial Content en búsquedas paginadas.
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw new ImagenProviderException("HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty()) {
                return Optional.empty();
            }
            for (JsonNode producto : root) {
                for (JsonNode item : producto.path("items")) {
                    for (JsonNode imagen : item.path("images")) {
                        String imageUrl = imagen.path("imageUrl").asText("");
                        if (!imageUrl.isBlank()) {
                            return Optional.of(imageUrl);
                        }
                    }
                }
            }
            return Optional.empty();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImagenProviderException("Interrumpido consultando " + host, e);
        } catch (IOException e) {
            throw new ImagenProviderException(e.getMessage(), e);
        }
    }

    private synchronized Throttle throttle() {
        if (throttle == null) {
            throttle = new Throttle(requestsPorSegundo);
        }
        return throttle;
    }
}
