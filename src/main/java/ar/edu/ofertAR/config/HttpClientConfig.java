package ar.edu.ofertAR.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .build();
    }
}
