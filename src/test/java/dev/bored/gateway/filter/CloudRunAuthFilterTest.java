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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** Plain GatewayFilterChain that records what it received (no Mockito). */
    static final class CapturingChain implements GatewayFilterChain {
        ServerWebExchange captured;
        int calls;
        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.captured = exchange;
            this.calls++;
            return Mono.empty();
        }
    }

    @Test
    void disabled_whenKServiceUnset_isNoOp() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> { throw new AssertionError("fetcher should not be called when disabled"); },
                false);
        MockServerWebExchange ex = exchangeWithRoute(PROFILE);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(chain.captured).isSameAs(ex);
        assertThat(chain.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void noRouteAttribute_isNoOp() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            throw new AssertionError("fetcher should not be called without a route");
        }, true);
        MockServerWebExchange ex = exchangeWithRoute(null);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(chain.captured).isSameAs(ex);
    }

    @Test
    void nonCloudRunTarget_leavesAuthorizationUntouched() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            throw new AssertionError("fetcher should not be called for non-run.app targets");
        }, true);
        MockServerWebExchange ex = exchangeWithRoute(LOCAL);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(ex, chain)).verifyComplete();

        assertThat(chain.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void cloudRunTarget_attachesBearerToken() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> Mono.just(new CloudRunAuthFilter.CachedToken(
                        "fake-token-for-" + audience, Instant.now().plus(Duration.ofMinutes(30)))),
                true);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), chain)).verifyComplete();

        assertThat(chain.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer fake-token-for-" + PROFILE);
    }

    @Test
    void cachedToken_reusedOnSecondCall() {
        AtomicInteger fetches = new AtomicInteger();
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            fetches.incrementAndGet();
            return Mono.just(new CloudRunAuthFilter.CachedToken(
                    "token-" + fetches.get(), Instant.now().plus(Duration.ofMinutes(30))));
        }, true);
        CapturingChain first = new CapturingChain();
        CapturingChain second = new CapturingChain();

        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), first)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), second)).verifyComplete();

        assertThat(fetches.get()).isEqualTo(1);
        assertThat(first.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo(second.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void expiredToken_triggersRefresh() {
        AtomicInteger fetches = new AtomicInteger();
        CloudRunAuthFilter filter = new CloudRunAuthFilter(audience -> {
            int n = fetches.incrementAndGet();
            // First token expires inside the 5-minute refresh buffer; second is fresh.
            Instant expiry = n == 1
                    ? Instant.now().plus(Duration.ofSeconds(30))
                    : Instant.now().plus(Duration.ofMinutes(30));
            return Mono.just(new CloudRunAuthFilter.CachedToken("token-" + n, expiry));
        }, true);
        CapturingChain first = new CapturingChain();
        CapturingChain second = new CapturingChain();

        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), first)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), second)).verifyComplete();

        assertThat(fetches.get()).isEqualTo(2);
        assertThat(first.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-1");
        assertThat(second.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-2");
    }

    @Test
    void fetcherFailure_forwardsWithoutAuthAndDoesNotBreakRequest() {
        CloudRunAuthFilter filter = new CloudRunAuthFilter(
                audience -> Mono.error(new RuntimeException("metadata server unreachable")),
                true);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchangeWithRoute(STREAM), chain)).verifyComplete();

        assertThat(chain.captured).isNotNull();
        assertThat(chain.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
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

    @Test
    void extractExpiry_readsExpClaimFromJwt() {
        // Hand-rolled minimal JWT: header.payload.sig — payload has exp=1_800_000_000.
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1800000000}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwt = "header." + payload + ".sig";

        assertThat(CloudRunAuthFilter.extractExpiry(jwt))
                .isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
    }

    @Test
    void extractExpiry_fallsBackTo55MinutesOnMalformedToken() {
        Instant now = Instant.now();
        Instant result = CloudRunAuthFilter.extractExpiry("not-a-jwt");
        // Should be roughly now + 55m.
        assertThat(result).isAfter(now.plus(Duration.ofMinutes(50)));
        assertThat(result).isBefore(now.plus(Duration.ofMinutes(60)));
    }

    @Test
    void extractExpiry_missingExpClaim_fallsBackTo55Minutes() {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwt = "h." + payload + ".s";
        Instant now = Instant.now();
        Instant result = CloudRunAuthFilter.extractExpiry(jwt);
        assertThat(result).isAfter(now.plus(Duration.ofMinutes(50)));
    }

    @Test
    void metadataFetcher_parsesTokenAndExpiryFromMetadataServer() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            // Build a minimal JWT whose exp is in the far future.
            String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"exp\":2147483647}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String jwt = "h." + payload + ".s";
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(jwt));

            String metadataUrl = server.url("/identity").toString();
            CloudRunAuthFilter.TokenFetcher fetcher = CloudRunAuthFilter.metadataFetcher(metadataUrl);

            CloudRunAuthFilter.CachedToken token = fetcher.fetch("https://profile.run.app").block();
            assertThat(token).isNotNull();
            assertThat(token.token()).isEqualTo(jwt);
            assertThat(token.expiry()).isEqualTo(Instant.ofEpochSecond(2_147_483_647L));

            // Verify the call shape: GET with Metadata-Flavor header + audience query param.
            okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getMethod()).isEqualTo("GET");
            assertThat(recorded.getHeader("Metadata-Flavor")).isEqualTo("Google");
            assertThat(recorded.getPath()).contains("audience=https://profile.run.app");
        }
    }

    @Test
    void productionConstructor_buildsWithoutCloudRunEnv() {
        // With K_SERVICE unset the filter should build fine, stay disabled, and be a pure no-op.
        CloudRunAuthFilter filter = new CloudRunAuthFilter((Boolean) null, null);
        CapturingChain chain = new CapturingChain();
        StepVerifier.create(filter.filter(exchangeWithRoute(PROFILE), chain)).verifyComplete();
        assertThat(chain.captured.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }
}
