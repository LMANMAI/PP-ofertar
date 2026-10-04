package ar.edu.ofertAR.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import ar.edu.ofertAR.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    private static final int MIN_BYTES_CLAVE = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername())
                && !isTokenExpired(token)
                && !isIssuedBeforeCutoff(token, userDetails);
    }

    /** El claim iat tiene precisión de segundos, así que el corte se compara en segundos. */
    private boolean isIssuedBeforeCutoff(String token, UserDetails userDetails) {
        if (!(userDetails instanceof User user) || user.getTokenValidFrom() == null) {
            return false;
        }
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt == null
                || issuedAt.toInstant().getEpochSecond() < user.getTokenValidFrom().getEpochSecond();
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        final Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    private volatile SecretKey signingKey;

    /** Falla al arrancar si el secreto no sirve, en vez de en el primer login. */
    @jakarta.annotation.PostConstruct
    void validarSecreto() {
        getSigningKey();
    }

    private SecretKey getSigningKey() {
        SecretKey key = signingKey;
        if (key == null) {
            key = construirClave();
            signingKey = key;
        }
        return key;
    }

    /**
     * El secreto puede venir en base64 o como texto plano. Se prueba primero como base64
     * (compatibilidad con los ya desplegados); si no decodifica o queda de menos de 256 bits,
     * se usa el texto tal cual, y si tampoco alcanza (32 bytes) no se arranca.
     */
    private SecretKey construirClave() {
        try {
            byte[] decodificado = Decoders.BASE64.decode(secret);
            if (decodificado.length >= MIN_BYTES_CLAVE) {
                return Keys.hmacShaKeyFor(decodificado);
            }
        } catch (io.jsonwebtoken.io.DecodingException ignorar) {
            // no es base64: se usa como texto
        }
        byte[] plano = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (plano.length < MIN_BYTES_CLAVE) {
            throw new IllegalStateException("jwt.secret debe tener al menos " + MIN_BYTES_CLAVE + " caracteres");
        }
        return Keys.hmacShaKeyFor(plano);
    }
}
