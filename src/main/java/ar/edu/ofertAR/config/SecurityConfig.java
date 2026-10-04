package ar.edu.ofertAR.config;

import ar.edu.ofertAR.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;

    /** Lista separada por comas. Vacía por defecto: la app móvil no usa CORS, solo un cliente web lo necesita. */
    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
<<<<<<< HEAD
                .requestMatchers("/auth/**").permitAll()
=======
                .requestMatchers("/auth/**", "/actuator/health", "/actuator/health/**", "/docs", "/docs/**", "/api-docs", "/api-docs/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers("/sepa/precios", "/sepa/sync", "/sepa/sync/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/sepa/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/legal/**").permitAll()
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> {
                    log.warn("ACCESO 401 {} {} ip={}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                })
                .accessDeniedHandler((request, response, e) -> {
                    log.warn("ACCESO 403 {} {} ip={} userId={}", request.getMethod(), request.getRequestURI(),
                            request.getRemoteAddr(), org.slf4j.MDC.get("userId"));
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                }))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
<<<<<<< HEAD
=======
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins.stream().filter(o -> !o.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
>>>>>>> 37cf6df (Merge pull request #23 from LMANMAI/auditoria-tecnica)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
