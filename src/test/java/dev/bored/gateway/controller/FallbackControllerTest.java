package dev.bored.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Tests for {@link FallbackController}.
 * <p>
 * Verifies that circuit-breaker fallback endpoints return proper
 * 503 responses with structured JSON bodies.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class FallbackControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void profileFallback_returns503WithStructuredResponse() {
        webTestClient.get().uri("/fallback/profile")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Profile Service is currently unavailable. Please try again later.")
                .jsonPath("$.path").isEqualTo("/fallback/profile")
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void regretFallback_returns503WithStructuredResponse() {
        webTestClient.get().uri("/fallback/regret")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Regret Stream Service is currently unavailable. Please try again later.")
                .jsonPath("$.path").isEqualTo("/fallback/regret")
                .jsonPath("$.timestamp").isNotEmpty();
    }
}
