package dev.bored.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reactive Spring Security configuration for the API Gateway.
 * <p>
 * Uses <strong>two filter chains</strong>:
 * <ol>
 *   <li>{@code /auth/**} — fully public, <em>no JWT processing</em> at all
 *       (so that Bearer tokens forwarded to Supabase don't get rejected).</li>
 *   <li>Everything else — validates Supabase-issued JWTs (HS256).
 *       GET requests to the portfolio API are public; write operations
 *       require a valid token.</li>
 * </ol>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${supabase.jwt.secret}")
    private String jwtSecret;

    /**
     * Filter chain for {@code /auth/**} — completely public, no JWT validation.
     * <p>
     * This must run first ({@code @Order(1)}) so that the auth controller
     * can accept requests that carry tokens destined for Supabase without
     * the gateway's JWT filter rejecting them.
     * </p>
     */
    @Bean
    @Order(1)
    public SecurityWebFilterChain authFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/auth/**"))
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    /**
     * Main filter chain for all non-auth routes.
     * <p>
     * GET requests to the portfolio API, actuator, and fallback endpoints
     * are public. Everything else (POST, PUT, DELETE on {@code /api/**})
     * requires a valid Supabase JWT.
     * </p>
     */
    @Bean
    @Order(2)
    public SecurityWebFilterChain apiFilterChain(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**", "/services/*/api-docs/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Reactive CORS configuration source applied to <strong>all</strong> endpoints
     * (including local controllers like {@code /auth/**}).
     * <p>
     * The gateway's {@code globalcors} YAML config only covers proxied routes;
     * this bean ensures local controller endpoints also return correct CORS headers.
     * </p>
     *
     * @return the configured {@link CorsConfigurationSource}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "https://boredsoftwaredeveloper.xyz",
                "https://www.boredsoftwaredeveloper.xyz"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Creates a reactive JWT decoder that validates Supabase tokens using HS256.
     *
     * @return the configured {@link ReactiveJwtDecoder}
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
