package ar.edu.ofertAR.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freno a la fuerza bruta sobre /auth/login: cuenta los fallos por IP y por email
 * dentro de una ventana y, superado el tope, responde 429 sin llegar a comprobar la
 * contraseña. Un login correcto borra el contador del email (no el de la IP).
 * En memoria: con varias réplicas cada una cuenta por separado.
 */
@Slf4j
@Service
public class LoginAttemptService {

    private record Contador(long inicio, int fallos) {}

    @Value("${login.max-fallos-por-ip:20}")
    private int maxPorIp;

    @Value("${login.max-fallos-por-email:10}")
    private int maxPorEmail;

    @Value("${login.ventana-minutos:15}")
    private long ventanaMinutos;

    private final ConcurrentHashMap<String, Contador> contadores = new ConcurrentHashMap<>();

    /** @throws ResponseStatusException 429 si la IP o el email ya superaron el tope. */
    public void verificarPermitido(String ip, String email) {
        if (bloqueado("ip|" + ip, maxPorIp) || bloqueado("email|" + normalizar(email), maxPorEmail)) {
            log.warn("AUTH login bloqueado por demasiados fallos ip={} email#={}", ip, huella(email));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Probá de nuevo en unos minutos.");
        }
    }

    public void registrarFallo(String ip, String email) {
        long ahora = System.currentTimeMillis();
        sumar("ip|" + ip, ahora);
        sumar("email|" + normalizar(email), ahora);
    }

    public void registrarExito(String email) {
        contadores.remove("email|" + normalizar(email));
    }

    /** Prefijo de un hash del email: permite correlacionar intentos en el log sin guardar el dato personal. */
    public static String huella(String email) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(normalizar(email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean bloqueado(String clave, int max) {
        Contador c = contadores.get(clave);
        if (c == null) {
            return false;
        }
        if (System.currentTimeMillis() - c.inicio() >= ventanaMinutos * 60_000L) {
            contadores.remove(clave, c);
            return false;
        }
        return c.fallos() >= max;
    }

    private void sumar(String clave, long ahora) {
        long ventanaMs = ventanaMinutos * 60_000L;
        contadores.merge(clave, new Contador(ahora, 1),
                (actual, nuevo) -> ahora - actual.inicio() >= ventanaMs
                        ? nuevo
                        : new Contador(actual.inicio(), actual.fallos() + 1));
    }

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
