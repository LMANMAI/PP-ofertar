package ar.edu.ofertAR.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class PushExecutorConfig {

    /**
     * Sending a push is a network round trip to Expo that nothing in the
     * request/response cycle depends on — callers (a ticket finishing OCR, a
     * referral getting credited) fire and move on, same reasoning as
     * {@link OcrExecutorConfig#ticketProcessingExecutor}.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService pushNotificationExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
