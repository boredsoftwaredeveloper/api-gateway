package dev.bored.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bored.gateway.dto.AuthResponse;
import dev.bored.gateway.dto.OAuthUrlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Server-side proxy for the Supabase GoTrue Auth REST API.
 * <p>
 * Keeps Supabase credentials (URL, anon key) on the server — the frontend
 * never communicates with Supabase directly. All auth operations flow through
 * the API Gateway: FE → Gateway → Supabase → Gateway → FE.
 * </p>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@Service
public class SupabaseAuthService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthService.class);

    private final WebClient webClient;
    private final String supabaseUrl;

    /**
     * Constructs the service with a pre-configured {@link WebClient}.
     *
     * @param supabaseUrl the Supabase project URL (e.g. {@code https://xyz.supabase.co})
     * @param anonKey     the Supabase anon/public key (safe for server-side use)
     */
    public SupabaseAuthService(
            @Value("${supabase.url}") String supabaseUrl,
            @Value("${supabase.anon-key}") String anonKey) {
        this.supabaseUrl = supabaseUrl;
        this.webClient = WebClient.builder()
                .baseUrl(supabaseUrl)
                .defaultHeader("apikey", anonKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Signs up a new user with email and password.
     *
     * @param email    user email
     * @param password user password
     * @return the auth response containing tokens
     */
    public Mono<AuthResponse> signUp(String email, String password) {
        log.info("Signup attempt for email={}", maskEmail(email));
        return webClient.post()
                .uri("/auth/v1/signup")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(JsonNode.class)
                                .flatMap(body -> {
                                    String msg = extractErrorMessage(body, "Signup failed");
                                    int code = response.statusCode().value();
                                    log.warn("Signup failed for email={}: {} ({})", maskEmail(email), msg, code);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.valueOf(code), msg));
                                }))
                .bodyToMono(JsonNode.class)
                .map(this::toAuthResponse);
    }

    /**
     * Authenticates an existing user with email and password.
     *
     * @param email    user email
     * @param password user password
     * @return the auth response containing tokens
     */
    public Mono<AuthResponse> signIn(String email, String password) {
        log.info("Login attempt for email={}", maskEmail(email));
        return webClient.post()
                .uri("/auth/v1/token?grant_type=password")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(JsonNode.class)
                                .flatMap(body -> {
                                    String msg = extractErrorMessage(body, "Login failed");
                                    int code = response.statusCode().value();
                                    log.warn("Login failed for email={}: {} ({})", maskEmail(email), msg, code);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.valueOf(code), msg));
                                }))
                .bodyToMono(JsonNode.class)
                .map(this::toAuthResponse);
    }

    /**
     * Refreshes an expired access token.
     *
     * @param refreshToken the refresh token
     * @return a new auth response with fresh tokens
     */
    public Mono<AuthResponse> refresh(String refreshToken) {
        log.debug("Token refresh requested");
        return webClient.post()
                .uri("/auth/v1/token?grant_type=refresh_token")
                .bodyValue(Map.of("refresh_token", refreshToken))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(JsonNode.class)
                                .flatMap(body -> {
                                    String msg = extractErrorMessage(body, "Token refresh failed");
                                    int code = response.statusCode().value();
                                    log.warn("Token refresh failed: {} ({})", msg, code);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.valueOf(code), msg));
                                }))
                .bodyToMono(JsonNode.class)
                .map(this::toAuthResponse);
    }

    /**
     * Builds the OAuth authorization URL for the given provider.
     * <p>
     * The frontend should open this URL in a popup or redirect. After the user
     * authenticates with the OAuth provider, Supabase redirects to the callback
     * URL with tokens appended as URL fragments.
     * </p>
     *
     * @param provider   the OAuth provider name (e.g. "google", "github")
     * @param redirectTo the URL to redirect back to after authentication
     * @return an object containing the full authorization URL
     */
    public Mono<OAuthUrlResponse> getOAuthUrl(String provider, String redirectTo) {
        log.info("OAuth URL requested for provider={}", provider);
        String url = supabaseUrl + "/auth/v1/authorize?provider=" + provider
                + "&redirect_to=" + redirectTo;
        return Mono.just(new OAuthUrlResponse(url));
    }

    /**
     * Logs out the user by invalidating their session on Supabase.
     *
     * @param accessToken the current access token
     * @return empty mono on success
     */
    public Mono<Void> signOut(String accessToken) {
        log.info("Logout requested");
        return webClient.post()
                .uri("/auth/v1/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> {
                            log.warn("Logout failed with status={}", response.statusCode().value());
                            return Mono.error(new ResponseStatusException(
                                    HttpStatus.valueOf(response.statusCode().value()), "Logout failed"));
                        })
                .bodyToMono(Void.class);
    }

    /**
     * Exchanges a one-time auth code (from OAuth callback) for session tokens.
     *
     * @param code the authorization code from the OAuth callback
     * @return the auth response containing tokens
     */
    public Mono<AuthResponse> exchangeCode(String code) {
        log.info("Exchanging auth code for tokens");
        return webClient.post()
                .uri("/auth/v1/token?grant_type=pkce")
                .bodyValue(Map.of("auth_code", code))
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(JsonNode.class)
                                .flatMap(body -> {
                                    String msg = extractErrorMessage(body, "Code exchange failed");
                                    int code2 = response.statusCode().value();
                                    log.warn("Code exchange failed: {} ({})", msg, code2);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.valueOf(code2), msg));
                                }))
                .bodyToMono(JsonNode.class)
                .map(this::toAuthResponse);
    }

    // ── Internal helpers ──────────────────────────────────────────────

    AuthResponse toAuthResponse(JsonNode body) {
        String accessToken = textOrNull(body, "access_token");
        String refreshToken = textOrNull(body, "refresh_token");
        long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 0;
        String email = "";
        if (body.has("user") && body.get("user").has("email")) {
            email = body.get("user").get("email").asText();
        }
        return new AuthResponse(accessToken, refreshToken, "bearer", expiresIn, email);
    }

    String extractErrorMessage(JsonNode body, String fallback) {
        if (body == null) return fallback;
        if (body.has("error_description")) return body.get("error_description").asText();
        if (body.has("msg")) return body.get("msg").asText();
        if (body.has("message")) return body.get("message").asText();
        if (body.has("error")) {
            JsonNode error = body.get("error");
            if (error.isTextual()) return error.asText();
        }
        return fallback;
    }

    private String textOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        String local = parts[0];
        String masked = local.length() > 2
                ? local.substring(0, 2) + "***"
                : "***";
        return masked + "@" + parts[1];
    }
}
