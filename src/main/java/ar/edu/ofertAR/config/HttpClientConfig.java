package ar.edu.ofertAR.config;

import ar.edu.ofertAR.service.metricas.Metricas;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final OcrProperties ocrProperties;

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ocrProperties.getTimeout() * 1000);
        factory.setReadTimeout(ocrProperties.getTimeout() * 1000);
        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(medirLlamadas())
                .build();
    }

    /**
     * Mide cada llamada a un servicio externo (OCR, ofertas, push de Expo, catálogos de
     * imágenes, SEPA) en la métrica ofertar.outbound.requests, por host y resultado. El
     * tiempo es hasta recibir los headers de la respuesta.
     */
    private static ClientHttpRequestInterceptor medirLlamadas() {
        return (request, body, execution) -> {
            long inicio = System.nanoTime();
            String resultado = "error";
            try {
                ClientHttpResponse response = execution.execute(request, body);
                int status = response.getStatusCode().value();
                resultado = status >= 500 ? "http_5xx" : status >= 400 ? "http_4xx" : "ok";
                return response;
            } finally {
                String host = request.getURI().getHost();
                Metricas.tiempo("ofertar.outbound.requests", System.nanoTime() - inicio,
                        "host", host == null ? "desconocido" : host, "outcome", resultado);
            }
        };
    }
}
