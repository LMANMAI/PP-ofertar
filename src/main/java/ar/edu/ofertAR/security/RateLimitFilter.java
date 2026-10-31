package ar.edu.ofertAR.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tope de requests por IP en los endpoints que no exigen sesión: /auth/** (POST) y
 * GET /sepa/**. Ventana fija de un minuto, en memoria: alcanza para frenar un bucle
 * de curl desde una máquina; con varias réplicas cada una cuenta por separado.
 *
 * <p>La IP es {@code getRemoteAddr()}: detrás del proxy, Tomcat la reemplaza por la del
 * cliente real (server.forward-headers-strategy=native) solo si el proxy es de confianza.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long VENTANA_MS = 60_000L;
    private static final int PODAR_DESDE = 10_000;

    private record Ventana(long inicio, int cuenta) {}

    @Value("${rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.auth-por-minuto:30}")
    private int authPorMinuto;

    @Value("${rate-limit.sepa-por-minuto:120}")
    private int sepaPorMinuto;

    private final ConcurrentHashMap<String, Ventana> ventanas = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String regla = regla(request);
        if (!enabled || regla == null) {
            chain.doFilter(request, response);
            return;
        }
        int limite = regla.equals("auth") ? authPorMinuto : sepaPorMinuto;
        String ip = request.getRemoteAddr();
        long ahora = System.currentTimeMillis();

        Ventana v = ventanas.merge(regla + "|" + ip, new Ventana(ahora, 1),
                (actual, nueva) -> ahora - actual.inicio() >= VENTANA_MS
                        ? nueva
                        : new Ventana(actual.inicio(), actual.cuenta() + 1));

        if (v.cuenta() > limite) {
            if (v.cuenta() == limite + 1) {
                log.warn("RATE_LIMIT regla={} ip={} limite={}/min", regla, ip, limite);
            }
            long reintentarEn = Math.max(1, (VENTANA_MS - (ahora - v.inicio()) + 999) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(reintentarEn));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":429,\"message\":\"Demasiados pedidos. Probá de nuevo en "
                    + reintentarEn + " segundos.\"}");
            return;
        }
        if (ventanas.size() > PODAR_DESDE) {
            ventanas.entrySet().removeIf(e -> ahora - e.getValue().inicio() >= VENTANA_MS);
        }
        chain.doFilter(request, response);
    }

    private static String regla(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/auth/") && "POST".equals(method)) {
            return "auth";
        }
        if (path.startsWith("/sepa/") && "GET".equals(method)) {
            return "sepa";
        }
        return null;
    }
}
