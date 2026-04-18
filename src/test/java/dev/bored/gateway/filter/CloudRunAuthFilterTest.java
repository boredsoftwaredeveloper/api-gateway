package dev.bored.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudRunAuthFilterTest {

    private static final URI PROFILE = URI.create("https://profile-service-lj2zcvqmua-uc.a.run.app");
    private static final URI STREAM = URI.create("https://stream-service-lj2zcvqmua-uc.a.run.app");
    private static final URI LOCAL = URI.create("http://localhost:8081");

    private static MockServerWebExchange exchangeWithRoute(URI target) {
        MockServerHttpRequest req = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/profiles/1")
                .build();
        MockServerWebExchange ex = MockServerWebExchange.from(req);
        if (target != null) {
            Route route = mock(Route.class);
            when(route.getUri()).thenReturn(target);
            ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        }
        return ex;
    }

    private static GatewayFilterChain capturingChain(AtomicReference<MockServerWebExchange> captured) {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return Mono.empty();
        });
        return chain;
    }

    @Test
    void disabled_whenKServiceUnset_isNoOp() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> { throw new AssertionError("fetcher should not be called when disabled"); },
                false);

        MockServerWebExchange ex = exchangeWithRoute(PROFILE);
        AtomicReference<MockServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(ex, capturingChain(captured))).verifyComplete();

        assertThat(captured.get()).isSameAs(ex);
        assertThat(ex.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void noRouteAttribute_isNoOp() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            throw new AssertionError("fetcher should not be called without a route");
        }, true);

        MockServerWebExchange ex = exchangeWithRoute(null);
        AtomicReference<MockServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(ex, capturingChain(captured))).verifyComplete();

        assertThat(captured.get()).isSameAs(ex);
    }

    @Test
    void nonCloudRunTarget_leavesAuthorizationUntouched() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            throw new AssertionError("fetcher should not be called for non-run.app targets");
        }, true);

        MockServerWebExchange ex = exchangeWithRoute(LOCAL);
        AtomicReference<MockServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(ex, capturingChain(captured))).verifyComplete();

        assertThat(captured.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void cloudRunTarget_attachesBearerToken() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> Mono.just(new CloudRunAuthFilter.CachedToken(
                        "fake-token-for-" + audience, Instant.now().plus(Duration.ofMinutes(30)))),
                true);

        MockServerWebExchange ex = exchangeWithRoute(PROFILE);
        AtomicReference<MockServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(ex, capturingChain(captured))).verifyComplete();

        String auth = captured.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(auth).isEqualTo("Bearer fake-token-for-" + PROFILE);
    }

    @Test
    void cachedToken_reusedOnSecondCall() {
        AtomicInteger fetches = new AtomicInteger();
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            fetches.incrementAndGet();
            return Mono.just(new CloudRunAuthFilter.CachedToken(
                    "token-" + fetches.get(), Instant.now().plus(Duration.ofMinutes(30))));
        }, true);

        AtomicReference<MockServerWebExchange> first = new AtomicReference<>();
        AtomicReference<MockServerWebExchange> second = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), capturingChain(first))).verifyComplete();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), capturingChain(second))).verifyComplete();

        assertThat(fetches.get()).isEqualTo(1);
        assertThat(first.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo(second.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void expiredToken_triggersRefresh() {
        AtomicInteger fetches = new AtomicInteger();
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            int n = fetches.incrementAndGet();
            // First token expires inside the 5-minute refresh buffer, second is fresh.
            Instant expiry = n == 1
                    ? Instant.now().plus(Duration.ofSeconds(30))
                    : Instant.now().plus(Duration.ofMinutes(30));
            return Mono.just(new CloudRunAuthFilter.CachedToken("token-" + n, expiry));
        }, true);

        AtomicReference<MockServerWebExchange> first = new AtomicReference<>();
        AtomicReference<MockServerWebExchange> second = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), capturingChain(first))).verifyComplete();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), capturingChain(second))).verifyComplete();

        assertThat(fetches.get()).isEqualTo(2);
        assertThat(first.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-1");
        assertThat(second.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-2");
    }

    @Test
    void fetcherFailure_forwardsWithoutAuthAndDoesNotBreakRequest() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> Mono.error(new RuntimeException("metadata server unreachable")),
                true);

        AtomicReference<MockServerWebExchange> captured = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchangeWithRoute(STREAM), capturingChain(captured)))
                .verifyComplete();

        // Filter fails open — chain is still called, Authorization header is absent.
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void audienceFor_extractsSchemeAndHostForRunApp() {
        assertThat(CloudRunAuthFilter.audienceFor(PROFILE))
                .isEqualTo("https://profile-service-lj2zcvqmua-uc.a.run.app");
        assertThat(CloudRunAuthFilter.audienceFor(URI.create("http://service.run.app/path")))
                .isEqualTo("http://service.run.app");
    }

    @Test
    void audienceFor_returnsNullForNonRunApp() {
        assertThat(CloudRunAuthFilter.audienceFor(URI.create("https://example.com"))).isNull();
        assertThat(CloudRunAuthFilter.audienceFor(URI.create("http://localhost:8081"))).isNull();
        assertThat(CloudRunAuthFilter.audienceFor(URI.create("lb:/profile-service"))).isNull();
    }

    @Test
    void resolveEnabled_honorsExplicitOverride() {
        assertThat(CloudRunAuthFilter.resolveEnabled(Boolean.TRUE, null)).isTrue();
        assertThat(CloudRunAuthFilter.resolveEnabled(Boolean.FALSE, "some-service")).isFalse();
        assertThat(CloudRunAuthFilter.resolveEnabled(null, "some-service")).isTrue();
        assertThat(CloudRunAuthFilter.resolveEnabled(null, null)).isFalse();
        assertThat(CloudRunAuthFilter.resolveEnabled(null, "")).isFalse();
    }

    @Test
    void getOrder_isBeforeNettyRoutingFilter() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(a -> Mono.empty(), true);
        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }
}
