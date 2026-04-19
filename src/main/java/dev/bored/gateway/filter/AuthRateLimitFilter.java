package dev.bored.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter for {@code /auth/**} endpoints.
 *
 * <p>Runs in one of two modes:</p>
 * <ul>
 *   <li><b>Redis-backed</b> (production): atomic Lua sliding-window over a
 *       {@code ZSET} keyed by client IP. Survives gateway restarts + scales
 *       across instances. Active when a {@link ReactiveStringRedisTemplate}
 *       bean exists (set {@code spring.data.redis.host}).</li>
 *   <li><b>In-memory fallback</b>: single-instance {@code ConcurrentHashMap}
 *       of timestamp deques. Used when Redis isn't wired — good enough for
 *       local dev + a single Cloud Run instance.</li>
 * </ul>
 *
 * <p><strong>Limits:</strong> {@value #MAX_REQUESTS} requests per
 * {@value #WINDOW_SECONDS} seconds per client IP.</p>
 *
 * <p>Redis errors are treated as fail-open — if the backend is unreachable
 * we serve the request rather than dropping legitimate traffic.</p>
 */
@Component
public class AuthRateLimitFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Maximum requests allowed per window per IP. */
    static final int MAX_REQUESTS = 20;

    /** Sliding window duration in seconds. */
    static final long WINDOW_SECONDS = 60;

    /** Cap on the number of IPs we remember in the in-memory fallback to stop unbounded memory growth. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    /** Lua: atomic sliding-window limiter over a sorted set. Returns 1 if allowed, 0 if blocked. */
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = RedisScript.of(
            "local key = KEYS[1]\n" +
            "local now = tonumber(ARGV[1])\n" +
            "local window_ms = tonumber(ARGV[2])\n" +
            "local max = tonumber(ARGV[3])\n" +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window_ms)\n" +
            "local count = redis.call('ZCARD', key)\n" +
            "if count >= max then return 0 end\n" +
            "redis.call('ZADD', key, now, tostring(now) .. ':' .. tostring(math.random(1, 1000000)))\n" +
            "redis.call('PEXPIRE', key, window_ms + 1000)\n" +
            "return 1\n",
            Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
        if (this.redis != null) {
            log.info("AuthRateLimitFilter: using Redis-backed sliding window.");
        } else {
            log.info("AuthRateLimitFilter: using in-memory sliding window (no Redis configured).");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/auth/")) {
            return chain.filter(exchange);
        }
        String clientIp = extractClientIp(exchange);
        return checkAllowed(clientIp, path)
                .flatMap(allowed -> allowed
                        ? chain.filter(exchange)
                        : reject(exchange, clientIp, path));
    }

    /** Returns {@code true} if the request is allowed, {@code false} if rate-limited. */
    Mono<Boolean> checkAllowed(String clientIp, String path) {
        if (redis != null) {
            return checkAllowedViaRedis(clientIp)
                    .onErrorResume(e -> {
                        log.warn("Redis rate-limit check failed for ip={} — allowing request (fail-open)", clientIp, e);
                        return Mono.just(true);
                    });
        }
        return Mono.fromSupplier(() -> checkAllowedInMemory(clientIp));
    }

    private Mono<Boolean> checkAllowedViaRedis(String clientIp) {
        String key = "ratelimit:auth:" + clientIp;
        long now = System.currentTimeMillis();
        long windowMs = WINDOW_SECONDS * 1000L;
        return redis.execute(SLIDING_WINDOW_SCRIPT,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(windowMs), String.valueOf(MAX_REQUESTS)))
                .next()
                .map(r -> r != null && r == 1L);
    }

    private boolean checkAllowedInMemory(String clientIp) {
        if (hits.size() > MAX_TRACKED_CLIENTS) {
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
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private static Mono<Void> reject(ServerWebExchange exchange, String clientIp, String path) {
        log.warn("Rate limit exceeded for ip={} path={}", clientIp, path);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(WINDOW_SECONDS));
        return exchange.getResponse().setComplete();
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
