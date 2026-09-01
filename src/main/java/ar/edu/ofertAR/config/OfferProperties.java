package ar.edu.ofertAR.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Note: the shared RestClient bean (HttpClientConfig) applies OcrProperties'
// connect/read timeout to every client, this one included — there's no
// per-client timeout override today, so we don't declare an unused one here.
@Configuration
@ConfigurationProperties("offer")
@Data
public class OfferProperties {
    private String serviceUrl = "http://localhost:3000";
}
