package ar.edu.ofertAR.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class OcrExecutorConfig {

    /**
     * Pool used to OCR the pages of a single ticket concurrently. A long
     * receipt arrives as 4-5 photos and each page is an independent call to
     * the OCR service, so processing them one after another made the request
     * take the *sum* of every page instead of the slowest one — which is what
     * pushed long tickets past the HTTP timeout.
     *
     * Bounded on purpose: the OCR service is a single small deployment, and
     * firing unbounded requests at it would just move the bottleneck.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService ocrExecutor(@Value("${ocr.max-concurrent-pages:5}") int maxConcurrentPages) {
        return Executors.newFixedThreadPool(Math.max(1, maxConcurrentPages));
    }
}
