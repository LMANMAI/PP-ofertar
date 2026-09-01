package ar.edu.ofertAR.service.ocr;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OcrTokenCache {

    private volatile String token;
    private volatile Instant expiresAt;

    public synchronized String getToken() {
        if (token != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }
        return null;
    }

    public synchronized void setToken(String token, int expireMinutes) {
        this.token = token;
        this.expiresAt = Instant.now().plusSeconds(expireMinutes * 60L - 30L);
    }

    public synchronized void invalidate() {
        this.token = null;
        this.expiresAt = null;
    }
}
