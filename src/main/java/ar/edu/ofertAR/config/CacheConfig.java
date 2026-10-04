package ar.edu.ofertAR.config;

import ar.edu.ofertAR.service.SepaCatalogoService;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caché en memoria de las búsquedas del snapshot SEPA. El snapshot cambia una vez por
 * semana; el TTL corto acota además cuánto tarda en verse una imagen recién resuelta.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SepaCatalogoService.CACHE_BUSQUEDA);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofMinutes(10)));
        return manager;
    }
}
