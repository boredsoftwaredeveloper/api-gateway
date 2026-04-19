package dev.bored.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthRateLimitFilter}.
 * <p>
 * Covers both modes: in-memory fallback (no Redis bean) and Redis-backed
 * sliding window (mocked {@link ReactiveStringRedisTemplate}).
 */
class AuthRateLimitFilterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> empty() {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> with(ReactiveStringRedisTemplate template) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(empty());

    private static WebFilterChain passThrough() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private static MockServerWebExchange authPostFrom(String ip) {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.POST, "/auth/login")
                .remoteAddress(new InetSocketAddress(ip, 0))
                .build();
        return MockServerWebExchange.from(req);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Deque<Instant>> hits() throws Exception {
        Field f = AuthRateLimitFilter.class.getDeclaredField("hits");
        f.setAccessible(true);
        return (Map<String, Deque<Instant>>) f.get(filter);
    }

    @Test
    void nonAuthPath_passesThroughWithoutTracking() throws Exception {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/profiles/1")
                .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        WebFilterChain chain = passThrough();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain, times(1)).filter(ex);
        assertThat(hits()).isEmpty();
    }

    @Test
    void authPath_underLimit_passesThrough() {
        MockServerWebExchange ex = authPostFrom("10.0.0.1");
        WebFilterChain chain = passThrough();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        verify(chain, times(1)).filter(ex);
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void authPath_overLimit_returns429WithRetryAfter() {
        WebFilterChain chain = passThrough();

        for (int i = 0; i < AuthRateLimitFilter.MAX_REQUESTS; i++) {
            StepVerifier.create(filter.filter(authPostFrom("9.9.9.9"), chain))
                    .verifyComplete();
        }

        MockServerWebExchange blocked = authPostFrom("9.9.9.9");
        StepVerifier.create(filter.filter(blocked, chain)).verifyComplete();

        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo(String.valueOf(AuthRateLimitFilter.WINDOW_SECONDS));
        // Chain called MAX_REQUESTS times (once per allowed request), not on the blocked one.
        verify(chain, times(AuthRateLimitFilter.MAX_REQUESTS)).filter(any());
    }

    @Test
    void slidingWindow_evictsExpiredTimestampsAndAllowsRequestAgain() throws Exception {
        String ip = "7.7.7.7";
        // Pre-seed the deque with MAX_REQUESTS stale timestamps that are well
        // outside the sliding window — the filter should evict them all and
        // admit the next request.
        Instant stale = Instant.now().minusSeconds(AuthRateLimitFilter.WINDOW_SECONDS + 60);
        Deque<Instant> deque = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < AuthRateLimitFilter.MAX_REQUESTS; i++) {
            deque.addLast(stale);
        }
        hits().put(ip, deque);

        MockServerWebExchange ex = authPostFrom(ip);
        WebFilterChain chain = passThrough();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isNull();
        assertThat(hits().get(ip)).hasSize(1);
        verify(chain, times(1)).filter(ex);
    }

    @Test
    void xForwardedFor_singleIp_isUsedAsClientKey() throws Exception {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.POST, "/auth/login")
                .header("X-Forwarded-For", "203.0.113.42")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 0))
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        WebFilterChain chain = passThrough();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(hits()).containsKey("203.0.113.42");
        assertThat(hits()).doesNotContainKey("10.0.0.1");
    }

    @Test
    void xForwardedFor_multipleIps_usesFirst() throws Exception {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.POST, "/auth/login")
                .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1, 10.0.0.2")
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        WebFilterChain chain = passThrough();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(hits()).containsKey("203.0.113.42");
    }

    @Test
    void xForwardedFor_blank_fallsBackToRemoteAddress() throws Exception {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.POST, "/auth/login")
                .header("X-Forwarded-For", "   ")
                .remoteAddress(new InetSocketAddress("5.5.5.5", 0))
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);

        StepVerifier.create(filter.filter(ex, passThrough())).verifyComplete();

        assertThat(hits()).containsKey("5.5.5.5");
    }

    @Test
    void noRemoteAddressAndNoForwarded_keysUnderUnknown() throws Exception {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.POST, "/auth/login")
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);

        StepVerifier.create(filter.filter(ex, passThrough())).verifyComplete();

        assertThat(hits()).containsKey("unknown");
    }

    @Test
    void trackedClientsOverLimit_triggerCoarseEviction() throws Exception {
        // Pre-populate the hits map beyond MAX_TRACKED_CLIENTS so the next
        // request triggers the guard branch that clears the map.
        Map<String, Deque<Instant>> hits = hits();
        for (int i = 0; i <= AuthRateLimitFilter.MAX_TRACKED_CLIENTS; i++) {
            Deque<Instant> d = new ConcurrentLinkedDeque<>();
            d.addLast(Instant.now());
            hits.put("client-" + i, d);
        }
        int sizeBefore = hits.size();
        assertThat(sizeBefore).isGreaterThan(AuthRateLimitFilter.MAX_TRACKED_CLIENTS);

        StepVerifier.create(filter.filter(authPostFrom("1.1.1.1"), passThrough()))
                .verifyComplete();

        // After the guard fires, the map holds just the single new client.
        assertThat(hits()).hasSize(1).containsKey("1.1.1.1");
    }

    @Test
    void getOrder_isHighestPrecedencePlusTen() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    // ── Redis-backed mode ────────────────────────────────────────────────

    @Test
    void redisMode_allowsRequestWhenScriptReturnsOne() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));
        AuthRateLimitFilter f = new AuthRateLimitFilter(with(redis));

        WebFilterChain chain = passThrough();
        StepVerifier.create(f.filter(authPostFrom("9.9.9.9"), chain)).verifyComplete();

        verify(chain, times(1)).filter(any());
    }

    @Test
    void redisMode_rejectsWithTooManyRequestsWhenScriptReturnsZero() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any(List.class)))
                .thenReturn(Flux.just(0L));
        AuthRateLimitFilter f = new AuthRateLimitFilter(with(redis));

        WebFilterChain chain = passThrough();
        MockServerWebExchange ex = authPostFrom("9.9.9.9");
        StepVerifier.create(f.filter(ex, chain)).verifyComplete();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo(String.valueOf(AuthRateLimitFilter.WINDOW_SECONDS));
        verify(chain, never()).filter(any());
    }

    @Test
    void redisMode_failsOpenOnRedisError() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any(List.class)))
                .thenReturn(Flux.error(new RuntimeException("redis unreachable")));
        AuthRateLimitFilter f = new AuthRateLimitFilter(with(redis));

        WebFilterChain chain = passThrough();
        StepVerifier.create(f.filter(authPostFrom("9.9.9.9"), chain)).verifyComplete();

        // Fail-open: the chain is still called despite the Redis error.
        verify(chain, times(1)).filter(any());
    }

    @Test
    void redisMode_skipsRedisForNonAuthPath() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        AuthRateLimitFilter f = new AuthRateLimitFilter(with(redis));

        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/profiles/1")
                .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);

        WebFilterChain chain = passThrough();
        StepVerifier.create(f.filter(ex, chain)).verifyComplete();

        // No Redis call for non-auth paths.
        verify(redis, never()).execute(any(RedisScript.class), any(List.class), any(List.class));
        verify(chain, times(1)).filter(any());
    }
}
