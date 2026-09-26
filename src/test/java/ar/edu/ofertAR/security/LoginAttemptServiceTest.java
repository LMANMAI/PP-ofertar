package ar.edu.ofertAR.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptServiceTest {

    private LoginAttemptService svc;

    @BeforeEach
    void setUp() {
        svc = new LoginAttemptService();
        ReflectionTestUtils.setField(svc, "maxPorIp", 5);
        ReflectionTestUtils.setField(svc, "maxPorEmail", 3);
        ReflectionTestUtils.setField(svc, "ventanaMinutos", 15L);
    }

    @Test
    @DisplayName("tres fallos sobre un mismo email bloquean el cuarto intento, venga de la IP que venga")
    void bloqueaPorEmail() {
        for (int i = 0; i < 3; i++) svc.registrarFallo("1.1.1." + i, "Ana@Test.com");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> svc.verificarPermitido("9.9.9.9", "ana@test.com"));
        assertEquals(429, e.getStatusCode().value());
    }

    @Test
    @DisplayName("los fallos de una IP con distintos emails la bloquean")
    void bloqueaPorIp() {
        for (int i = 0; i < 5; i++) svc.registrarFallo("2.2.2.2", "u" + i + "@test.com");
        assertThrows(ResponseStatusException.class, () -> svc.verificarPermitido("2.2.2.2", "otro@test.com"));
        assertDoesNotThrow(() -> svc.verificarPermitido("3.3.3.3", "otro@test.com"));
    }

    @Test
    @DisplayName("un login correcto borra el contador del email")
    void exitoLimpia() {
        svc.registrarFallo("1.1.1.1", "ana@test.com");
        svc.registrarFallo("1.1.1.1", "ana@test.com");
        svc.registrarExito("ana@test.com");
        svc.registrarFallo("1.1.1.1", "ana@test.com");
        assertDoesNotThrow(() -> svc.verificarPermitido("4.4.4.4", "ana@test.com"));
    }

    @Test
    @DisplayName("la huella del email es estable, no lo revela y no distingue mayúsculas")
    void huella() {
        assertEquals(LoginAttemptService.huella("Ana@Test.com"), LoginAttemptService.huella(" ana@test.com "));
        assertNotEquals("ana@test.com", LoginAttemptService.huella("ana@test.com"));
        assertEquals(8, LoginAttemptService.huella("ana@test.com").length());
    }
}
