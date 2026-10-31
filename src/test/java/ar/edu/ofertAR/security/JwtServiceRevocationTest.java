package ar.edu.ofertAR.security;

import ar.edu.ofertAR.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceRevocationTest {

    private JwtService jwt;
    private User user;

    @BeforeEach
    void setUp() {
        jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "clave-de-test-solo-para-pruebas-0123456789abcdef");
        ReflectionTestUtils.setField(jwt, "expirationMs", 3_600_000L);
        user = User.builder().name("Ana").email("ana@test.com").password("x").build();
    }

    @Test
    @DisplayName("sin corte, un token vigente es valido")
    void sinCorte() {
        assertTrue(jwt.isTokenValid(jwt.generateToken(user), user));
    }

    @Test
    @DisplayName("un token emitido antes del corte se rechaza")
    void anteriorAlCorte() {
        String viejo = jwt.generateToken(user);
        user.setTokenValidFrom(Instant.now().plusSeconds(5));
        assertFalse(jwt.isTokenValid(viejo, user));
    }

    @Test
    @DisplayName("un token emitido despues del corte es valido")
    void posteriorAlCorte() {
        user.setTokenValidFrom(Instant.now().minusSeconds(60));
        assertTrue(jwt.isTokenValid(jwt.generateToken(user), user));
    }

    @Test
    @DisplayName("el token emitido justo al cambiar la clave (mismo segundo) sigue valido")
    void mismoSegundo() {
        user.setTokenValidFrom(Instant.now());
        assertTrue(jwt.isTokenValid(jwt.generateToken(user), user));
    }
}
