package dev.bored.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback controller invoked when a circuit breaker trips.
 * <p>
 * Returns a structured JSON error response so the client receives
 * a meaningful message instead of a raw connection timeout.
 * </p>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    /**
     * Fallback for the Profile Service circuit breaker.
     *
     * @param exchange the current server web exchange
     * @return a 503 JSON response indicating the profile service is unavailable
     */
    @GetMapping("/profile")
    public Mono<Map<String, Object>> profileFallback(ServerWebExchange exchange) {
        return buildFallbackResponse("Profile Service is currently unavailable. Please try again later.", exchange);
    }

    /**
     * Fallback for the Regret Stream Service circuit breaker.
     *
     * @param exchange the current server web exchange
     * @return a 503 JSON response indicating the regret stream service is unavailable
     */
    @GetMapping("/regret")
    public Mono<Map<String, Object>> regretFallback(ServerWebExchange exchange) {
        return buildFallbackResponse("Regret Stream Service is currently unavailable. Please try again later.", exchange);
    }

    private Mono<Map<String, Object>> buildFallbackResponse(String message, ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
                "status", 503,
                "error", "SERVICE_UNAVAILABLE",
                "message", message,
                "timestamp", Instant.now().toString(),
                "path", exchange.getRequest().getPath().value()
        ));
    }
}
