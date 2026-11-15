package ar.edu.ofertAR.config;

import ar.edu.ofertAR.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
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

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;

<<<<<<< HEAD
=======
    /** Lista separada por comas. Vacía por defecto: la app móvil no usa CORS, solo un cliente web lo necesita. */
    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:}")
    private List<String> allowedOrigins;

    /**
     * Orígenes que pueden leer /telemetry/** desde un navegador (el tablero de métricas y la
     * app en Expo web). Son endpoints públicos y sin credenciales, por eso el valor por defecto es "*".
     */
    @org.springframework.beans.factory.annotation.Value("${metricas.cors-allowed-origins:*}")
    private List<String> metricasAllowedOrigins;

>>>>>>> 2b22bd1 (Merge pull request #33 from LMANMAI/feature/metricas)
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
                // Disparar jobs de notificaciones a mano (AdminPushController).
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/sepa/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/legal/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/r/*").permitAll()
                // Telemetría de la app (llega también sin sesión) y lectura de métricas del tablero.
                .requestMatchers("/telemetry/**").permitAll()
>>>>>>> 2b22bd1 (Merge pull request #33 from LMANMAI/feature/metricas)
                .anyRequest().authenticated()
            )
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
        CorsConfiguration metricas = new CorsConfiguration();
        metricas.setAllowedOriginPatterns(metricasAllowedOrigins.stream().filter(o -> !o.isBlank()).toList());
        metricas.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        metricas.setAllowedHeaders(List.of("Content-Type"));
        metricas.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Se resuelve por orden de registro: la regla específica va antes que la general.
        source.registerCorsConfiguration("/telemetry/**", metricas);
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
>>>>>>> 2b22bd1 (Merge pull request #33 from LMANMAI/feature/metricas)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
