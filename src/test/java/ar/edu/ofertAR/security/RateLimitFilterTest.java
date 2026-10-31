package ar.edu.ofertAR.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "authPorMinuto", 2);
        ReflectionTestUtils.setField(filter, "sepaPorMinuto", 3);
    }

    private int pedir(String method, String path, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        if (res.getStatus() == 429) {
            assertNotNull(res.getHeader("Retry-After"));
        }
        return res.getStatus();
    }

    @Test
    @DisplayName("POST /auth/** corta con 429 al pasar el tope, por IP")
    void auth() throws Exception {
        assertEquals(200, pedir("POST", "/auth/login", "1.1.1.1"));
        assertEquals(200, pedir("POST", "/auth/login", "1.1.1.1"));
        assertEquals(429, pedir("POST", "/auth/login", "1.1.1.1"));
        assertEquals(200, pedir("POST", "/auth/login", "2.2.2.2"));
    }

    @Test
    @DisplayName("GET /sepa/** tiene su propio tope")
    void sepa() throws Exception {
        for (int i = 0; i < 3; i++) assertEquals(200, pedir("GET", "/sepa/productos", "1.1.1.1"));
        assertEquals(429, pedir("GET", "/sepa/productos", "1.1.1.1"));
    }

    @Test
    @DisplayName("el resto de las rutas no se limita")
    void otras() throws Exception {
        for (int i = 0; i < 10; i++) assertEquals(200, pedir("GET", "/tickets", "1.1.1.1"));
    }
}
