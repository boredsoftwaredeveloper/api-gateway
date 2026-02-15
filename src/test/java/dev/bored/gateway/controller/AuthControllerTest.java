package dev.bored.gateway.controller;

import dev.bored.gateway.dto.AuthResponse;
import dev.bored.gateway.dto.OAuthUrlResponse;
import dev.bored.gateway.service.SupabaseAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link AuthController}.
 * <p>
 * Uses a running application context with a mocked {@link SupabaseAuthService}
 * so we test the full HTTP layer (serialization, validation, status codes)
 * without hitting actual Supabase.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SupabaseAuthService authService;

    private static final AuthResponse MOCK_AUTH = new AuthResponse(
            "access-token", "refresh-token", "bearer", 3600, "user@test.com"
    );

    // ── signup ──────────────────────────────────────────────────────

    @Test
    void signup_validRequest_returns201() {
        when(authService.signUp("new@test.com", "password123"))
                .thenReturn(Mono.just(MOCK_AUTH));

        webTestClient.post().uri("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"new@test.com","password":"password123"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access-token")
                .jsonPath("$.refresh_token").isEqualTo("refresh-token")
                .jsonPath("$.token_type").isEqualTo("bearer")
                .jsonPath("$.expires_in").isEqualTo(3600)
                .jsonPath("$.email").isEqualTo("user@test.com");
    }

    @Test
    void signup_invalidEmail_returns400() {
        webTestClient.post().uri("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"not-an-email","password":"password123"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void signup_blankPassword_returns400() {
        webTestClient.post().uri("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"test@test.com","password":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── login ───────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200() {
        when(authService.signIn("user@test.com", "password123"))
                .thenReturn(Mono.just(MOCK_AUTH));

        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"user@test.com","password":"password123"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access-token")
                .jsonPath("$.email").isEqualTo("user@test.com");
    }

    @Test
    void login_blankEmail_returns400() {
        webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"","password":"password123"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── refresh ─────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returns200() {
        when(authService.refresh("old-refresh"))
                .thenReturn(Mono.just(MOCK_AUTH));

        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"refreshToken":"old-refresh"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access-token");
    }

    @Test
    void refresh_blankToken_returns400() {
        webTestClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"refreshToken":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── logout ──────────────────────────────────────────────────────

    @Test
    void logout_withBearerToken_returns204() {
        when(authService.signOut("my-token"))
                .thenReturn(Mono.empty());

        webTestClient.post().uri("/auth/logout")
                .header("Authorization", "Bearer my-token")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void logout_withoutBearerToken_extractsEmpty() {
        when(authService.signOut(""))
                .thenReturn(Mono.empty());

        webTestClient.post().uri("/auth/logout")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── OAuth URL ───────────────────────────────────────────────────

    @Test
    void oauthUrl_google_returnsUrl() {
        when(authService.getOAuthUrl("google", "http://localhost:4200/callback"))
                .thenReturn(Mono.just(new OAuthUrlResponse("https://supabase.co/auth?provider=google")));

        webTestClient.get().uri("/auth/oauth/google?redirect_to=http://localhost:4200/callback")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("https://supabase.co/auth?provider=google");
    }

    @Test
    void oauthUrl_github_returnsUrl() {
        when(authService.getOAuthUrl("github", "https://example.com/cb"))
                .thenReturn(Mono.just(new OAuthUrlResponse("https://supabase.co/auth?provider=github")));

        webTestClient.get().uri("/auth/oauth/github?redirect_to=https://example.com/cb")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.url").isEqualTo("https://supabase.co/auth?provider=github");
    }

    // ── callback ────────────────────────────────────────────────────

    @Test
    void callback_validCode_returnsTokens() {
        when(authService.exchangeCode("auth-code-xyz"))
                .thenReturn(Mono.just(MOCK_AUTH));

        webTestClient.post().uri("/auth/callback?code=auth-code-xyz")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access-token")
                .jsonPath("$.email").isEqualTo("user@test.com");
    }
}
