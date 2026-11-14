package ar.edu.ofertAR.service.bank;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lee payment_issuers, la tabla de emisores que escribe el scraper (OFT-45).
 *
 * <p>Sólo SELECT y con SQL a mano, sin entidad JPA: la tabla es del scraper y
 * no la crea Flyway, así que una entidad haría que {@code ddl-auto=validate}
 * tumbe el arranque del backend si la tabla todavía no existe o si el scraper
 * le cambia un tipo. Igual que los clientes HTTP del scraper
 * ({@code StoreLocatorClient}), si no se puede leer devuelve vacío en vez de
 * romper la pantalla.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIssuerReader {

    /** Un emisor: banco, billetera, emisora de tarjeta o club. */
    public record Issuer(String slug, String name, String kind) {
    }

    private final JdbcTemplate jdbcTemplate;

    public List<Issuer> findAll() {
        try {
            return jdbcTemplate.query(
                    "SELECT slug, name, kind FROM payment_issuers ORDER BY name, slug",
                    (rs, i) -> new Issuer(rs.getString("slug"), rs.getString("name"), rs.getString("kind")));
        } catch (DataAccessException e) {
            log.warn("No se pudieron leer los emisores (payment_issuers): {}", e.getMessage());
            return List.of();
        }
    }
}
