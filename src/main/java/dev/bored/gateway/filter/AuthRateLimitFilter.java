package dev.bored.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter for {@code /auth/**} endpoints.
 * <p>
 * Protects {@code /auth/login} and {@code /auth/signup} from credential-stuffing
 * and enumeration. Uses an in-memory sliding window keyed by client IP so it
 * works on a single Cloud Run instance without external state. When we move to
 * multiple instances, swap this for a Redis-backed implementation — the rate
 * contract stays the same.
 * </p>
 *
 * <p><strong>Limits:</strong> {@value #MAX_REQUESTS} requests per
 * {@value #WINDOW_SECONDS} seconds per client IP per path prefix.</p>
 *
 * @author Bored Software Developer
 * @since 2026-04-18
 */
@Component
public class AuthRateLimitFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Maximum requests allowed per window per IP. */
    static final int MAX_REQUESTS = 20;

    /** Sliding window duration in seconds. */
    static final long WINDOW_SECONDS = 60;

    /** Cap on the number of IPs we remember to stop unbounded memory growth. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/auth/")) {
            return chain.filter(exchange);
        }

        String clientIp = extractClientIp(exchange);
        if (hits.size() > MAX_TRACKED_CLIENTS) {
            // Coarse eviction — drop everything and let the window re-populate.
            hits.clear();
        }

        Deque<Instant> timestamps = hits.computeIfAbsent(clientIp, k -> new ConcurrentLinkedDeque<>());
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofSeconds(WINDOW_SECONDS));

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS) {
                log.warn("Rate limit exceeded for ip={} path={}", clientIp, path);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(WINDOW_SECONDS));
                return exchange.getResponse().setComplete();
            }
            timestamps.addLast(now);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Run before the Spring Security filter chain so we reject early.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    /**
     * Prefers the first address in {@code X-Forwarded-For} (set by Cloud Run's
     * load balancer) and falls back to the direct remote address.
     */
    private static String extractClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }
}
