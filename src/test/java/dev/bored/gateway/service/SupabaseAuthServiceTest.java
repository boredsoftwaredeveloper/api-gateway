package dev.bored.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bored.gateway.dto.AuthResponse;
import dev.bored.gateway.dto.OAuthUrlResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SupabaseAuthService}.
 * Uses OkHttp MockWebServer to simulate the Supabase GoTrue API.
 */
class SupabaseAuthServiceTest {

    private MockWebServer mockServer;
    private SupabaseAuthService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        service = new SupabaseAuthService(baseUrl, "test-anon-key");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    // ── signUp ──────────────────────────────────────────────────────

    @Test
    void signUp_success_returnsAuthResponse() throws Exception {
        String body = successBody("user@test.com");
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody(body));

        StepVerifier.create(service.signUp("user@test.com", "password123"))
                .assertNext(resp -> {
                    assertThat(resp.accessToken()).isEqualTo("access-token-123");
                    assertThat(resp.refreshToken()).isEqualTo("refresh-token-456");
                    assertThat(resp.tokenType()).isEqualTo("bearer");
                    assertThat(resp.expiresIn()).isEqualTo(3600);
                    assertThat(resp.email()).isEqualTo("user@test.com");
                })
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/auth/v1/signup");
        assertThat(request.getHeader("apikey")).isEqualTo("test-anon-key");
    }

    @Test
    void signUp_conflict_throwsResponseStatusException() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(422)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"error_description\":\"User already registered\"}"));

        StepVerifier.create(service.signUp("dup@test.com", "password123"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).isEqualTo("User already registered");
                })
                .verify();
    }

    // ── signIn ──────────────────────────────────────────────────────

    @Test
    void signIn_success_returnsAuthResponse() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody(successBody("login@test.com")));

        StepVerifier.create(service.signIn("login@test.com", "password123"))
                .assertNext(resp -> {
                    assertThat(resp.accessToken()).isEqualTo("access-token-123");
                    assertThat(resp.email()).isEqualTo("login@test.com");
                })
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/auth/v1/token?grant_type=password");
    }

    @Test
    void signIn_badCredentials_throwsUnauthorized() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"error_description\":\"Invalid login credentials\"}"));

        StepVerifier.create(service.signIn("bad@test.com", "wrong"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).isEqualTo("Invalid login credentials");
                })
                .verify();
    }

    // ── refresh ─────────────────────────────────────────────────────

    @Test
    void refresh_success_returnsNewTokens() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody(successBody("refresh@test.com")));

        StepVerifier.create(service.refresh("old-refresh-token"))
                .assertNext(resp -> {
                    assertThat(resp.accessToken()).isEqualTo("access-token-123");
                    assertThat(resp.refreshToken()).isEqualTo("refresh-token-456");
                })
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/auth/v1/token?grant_type=refresh_token");
    }

    @Test
    void refresh_expired_throwsUnauthorized() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"msg\":\"Token has expired\"}"));

        StepVerifier.create(service.refresh("expired-token"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("Token has expired");
                })
                .verify();
    }

    // ── getOAuthUrl ─────────────────────────────────────────────────

    @Test
    void getOAuthUrl_google_returnsCorrectUrl() {
        StepVerifier.create(service.getOAuthUrl("google", "http://localhost:4200/callback"))
                .assertNext(resp -> {
                    assertThat(resp.url()).contains("/auth/v1/authorize");
                    assertThat(resp.url()).contains("provider=google");
                    assertThat(resp.url()).contains("redirect_to=http://localhost:4200/callback");
                })
                .verifyComplete();
    }

    @Test
    void getOAuthUrl_github_returnsCorrectUrl() {
        StepVerifier.create(service.getOAuthUrl("github", "https://example.com/cb"))
                .assertNext(resp ->
                        assertThat(resp.url()).contains("provider=github"))
                .verifyComplete();
    }

    // ── signOut ─────────────────────────────────────────────────────

    @Test
    void signOut_success_completesEmpty() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(service.signOut("valid-token"))
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/auth/v1/logout");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer valid-token");
    }

    @Test
    void signOut_unauthorized_throwsException() {
        mockServer.enqueue(new MockResponse().setResponseCode(401));

        StepVerifier.create(service.signOut("invalid-token"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    // ── exchangeCode ────────────────────────────────────────────────

    @Test
    void exchangeCode_success_returnsTokens() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody(successBody("oauth@test.com")));

        StepVerifier.create(service.exchangeCode("auth-code-123"))
                .assertNext(resp -> {
                    assertThat(resp.accessToken()).isEqualTo("access-token-123");
                    assertThat(resp.email()).isEqualTo("oauth@test.com");
                })
                .verifyComplete();

        RecordedRequest request = mockServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/auth/v1/token?grant_type=pkce");
    }

    @Test
    void exchangeCode_invalid_throwsBadRequest() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"));

        StepVerifier.create(service.exchangeCode("bad-code"))
                .expectErrorSatisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).isEqualTo("invalid_grant");
                })
                .verify();
    }

    // ── toAuthResponse ──────────────────────────────────────────────

    @Test
    void toAuthResponse_missingFields_returnsDefaults() {
        ObjectNode node = mapper.createObjectNode();
        AuthResponse resp = service.toAuthResponse(node);
        assertThat(resp.accessToken()).isNull();
        assertThat(resp.refreshToken()).isNull();
        assertThat(resp.expiresIn()).isZero();
        assertThat(resp.email()).isEmpty();
    }

    // ── extractErrorMessage ─────────────────────────────────────────

    @Test
    void extractErrorMessage_nullBody_returnsFallback() {
        assertThat(service.extractErrorMessage(null, "fallback")).isEqualTo("fallback");
    }

    @Test
    void extractErrorMessage_errorDescriptionField_preferred() {
        ObjectNode node = mapper.createObjectNode()
                .put("error_description", "desc")
                .put("msg", "should not use");
        assertThat(service.extractErrorMessage(node, "fallback")).isEqualTo("desc");
    }

    @Test
    void extractErrorMessage_msgField_usedWhenNoDescription() {
        ObjectNode node = mapper.createObjectNode().put("msg", "the msg");
        assertThat(service.extractErrorMessage(node, "fallback")).isEqualTo("the msg");
    }

    @Test
    void extractErrorMessage_messageField_usedAsFallback() {
        ObjectNode node = mapper.createObjectNode().put("message", "the message");
        assertThat(service.extractErrorMessage(node, "fallback")).isEqualTo("the message");
    }

    @Test
    void extractErrorMessage_noKnownFields_returnsFallback() {
        ObjectNode node = mapper.createObjectNode().put("unknown", "value");
        assertThat(service.extractErrorMessage(node, "default msg")).isEqualTo("default msg");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private String successBody(String email) {
        return """
                {
                  "access_token": "access-token-123",
                  "refresh_token": "refresh-token-456",
                  "token_type": "bearer",
                  "expires_in": 3600,
                  "user": {
                    "id": "user-id-789",
                    "email": "%s"
                  }
                }
                """.formatted(email);
    }
}
