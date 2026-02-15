package dev.bored.gateway.controller;

import dev.bored.gateway.dto.AuthResponse;
import dev.bored.gateway.dto.LoginRequest;
import dev.bored.gateway.dto.OAuthUrlResponse;
import dev.bored.gateway.dto.RefreshRequest;
import dev.bored.gateway.dto.SignupRequest;
import dev.bored.gateway.service.SupabaseAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Auth controller that proxies all authentication operations through the
 * API Gateway to the Supabase GoTrue REST API.
 * <p>
 * The frontend <strong>never</strong> talks to Supabase directly. Every
 * auth flow — signup, login, OAuth, refresh, logout — goes through this
 * controller so that credentials (Supabase URL, anon key) stay server-side.
 * </p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /auth/signup} — create account with email + password</li>
 *   <li>{@code POST /auth/login}  — authenticate with email + password</li>
 *   <li>{@code POST /auth/refresh} — exchange refresh token for new tokens</li>
 *   <li>{@code POST /auth/logout} — invalidate the session</li>
 *   <li>{@code GET  /auth/oauth/{provider}} — get OAuth provider redirect URL</li>
 *   <li>{@code POST /auth/callback} — exchange OAuth code for tokens</li>
 * </ul>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SupabaseAuthService authService;

    public AuthController(SupabaseAuthService authService) {
        this.authService = authService;
    }

    /**
     * Creates a new user account with email and password.
     *
     * @param request signup credentials
     * @return auth response with tokens
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return authService.signUp(request.email(), request.password());
    }

    /**
     * Authenticates a user with email and password.
     *
     * @param request login credentials
     * @return auth response with tokens
     */
    @PostMapping("/login")
    public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return authService.signIn(request.email(), request.password());
    }

    /**
     * Refreshes an expired access token.
     *
     * @param request the refresh token
     * @return a fresh auth response
     */
    @PostMapping("/refresh")
    public Mono<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * Invalidates the current session. Requires a valid access token
     * in the {@code Authorization} header.
     *
     * @param httpRequest the incoming HTTP request (to extract the Bearer token)
     * @return empty response on success
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerHttpRequest httpRequest) {
        String authHeader = httpRequest.getHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7)
                : "";
        return authService.signOut(token);
    }

    /**
     * Returns the OAuth authorization URL for a given provider.
     * <p>
     * The frontend opens this URL in a popup. After the user authenticates
     * with Google/GitHub, they are redirected back with an auth code.
     * </p>
     *
     * @param provider   OAuth provider name ({@code google} or {@code github})
     * @param redirectTo the URL to redirect to after successful authentication
     * @return an object containing the provider's authorization URL
     */
    @GetMapping("/oauth/{provider}")
    public Mono<OAuthUrlResponse> oauthUrl(
            @PathVariable String provider,
            @RequestParam("redirect_to") String redirectTo) {
        return authService.getOAuthUrl(provider, redirectTo);
    }

    /**
     * Exchanges an OAuth authorization code for access and refresh tokens.
     *
     * @param code the one-time auth code from the OAuth callback
     * @return auth response with tokens
     */
    @PostMapping("/callback")
    public Mono<AuthResponse> callback(@RequestParam String code) {
        return authService.exchangeCode(code);
    }
}
