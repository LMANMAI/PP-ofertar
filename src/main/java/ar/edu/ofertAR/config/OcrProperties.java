package ar.edu.ofertAR.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("ocr")
@Data
public class OcrProperties {
    private String serviceUrl = "http://localhost:8000";
    private String username = "admin";
    private String password = "changeme";
    private int timeout = 120;
}
