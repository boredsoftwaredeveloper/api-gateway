package dev.bored.gateway.filter;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches a Google-signed OIDC ID token on every outbound proxy call to a
 * Cloud Run service. Required when the downstream service is configured with
 * {@code --no-allow-unauthenticated} — Cloud Run's ingress layer validates the
 * token against the calling identity's {@code roles/run.invoker} binding.
 *
 * <p>The filter is a no-op outside Cloud Run (detected via the {@code K_SERVICE}
 * environment variable) so local {@code bootRun} keeps working without any
 * Google credentials.</p>
 *
 * <p>Tokens are cached per audience (one audience per downstream service URL)
 * and refreshed a few minutes before expiry to avoid a refresh hop on every
 * request.</p>
 */
@Component
public class CloudRunAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CloudRunAuthFilter.class);

    /** Refresh buffer — refresh the token when fewer than this many seconds remain. */
    private static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);

    private final TokenFetcher tokenFetcher;
    private final boolean enabled;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public CloudRunAuthFilter(@Value("${cloud-run-auth.enabled:#{null}}") Boolean explicitToggle,
                              @Value("${K_SERVICE:#{null}}") String kService) {
        this(defaultFetcher(), resolveEnabled(explicitToggle, kService));
    }

    /** Constructor for tests: inject a custom fetcher + toggle. */
    CloudRunAuthFilter(TokenFetcher tokenFetcher, boolean enabled) {
        this.tokenFetcher = tokenFetcher;
        this.enabled = enabled;
        if (enabled) {
            log.info("CloudRunAuthFilter active — will attach ID tokens on *.run.app downstream calls.");
        } else {
            log.info("CloudRunAuthFilter disabled — not running on Cloud Run, skipping ID token injection.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        URI targetUri = route.getUri();
        String audience = audienceFor(targetUri);
        if (audience == null) {
            // Not a Cloud Run target — leave the request untouched.
            return chain.filter(exchange);
        }

        return fetchToken(audience)
                .map(token -> exchange.mutate()
                        .request(r -> r.headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                        .build())
                .flatMap(chain::filter)
                .onErrorResume(e -> {
                    log.error("Failed to attach Cloud Run ID token for audience {} — forwarding without auth", audience, e);
                    return chain.filter(exchange);
                });
    }

    /**
     * Returns the downstream audience (scheme + host, no trailing slash) if the
     * URI points at a Cloud Run service, otherwise {@code null}.
     */
    static String audienceFor(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost();
        if (!host.endsWith(".run.app")) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        return scheme + "://" + host;
    }

    @Override
    public int getOrder() {
        // Run before Netty's routing filter so the mutated headers reach the
        // downstream call. NettyRoutingFilter uses Ordered.LOWEST_PRECEDENCE.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    private Mono<String> fetchToken(String audience) {
        CachedToken cached = cache.get(audience);
        Instant now = Instant.now();
        if (cached != null && cached.expiry().isAfter(now.plus(REFRESH_BUFFER))) {
            return Mono.just(cached.token());
        }
        return tokenFetcher.fetch(audience)
                .doOnNext(fresh -> cache.put(audience, fresh))
                .map(CachedToken::token);
    }

    // ------------------------------------------------------------------ helpers

    static boolean resolveEnabled(Boolean explicit, String kService) {
        if (explicit != null) {
            return explicit;
        }
        return kService != null && !kService.isBlank();
    }

    private static TokenFetcher defaultFetcher() {
        return audience -> Mono.fromCallable(() -> {
                    GoogleCredentials base = GoogleCredentials.getApplicationDefault();
                    if (!(base instanceof IdTokenProvider provider)) {
                        throw new IllegalStateException(
                                "Application default credentials do not support ID token issuance: " + base.getClass());
                    }
                    IdTokenCredentials creds = IdTokenCredentials.newBuilder()
                            .setIdTokenProvider(provider)
                            .setTargetAudience(audience)
                            .setOptions(Collections.singletonList(IdTokenProvider.Option.FORMAT_FULL))
                            .build();
                    creds.refresh();
                    com.google.auth.oauth2.IdToken token = creds.getIdToken();
                    Date exp = token.getExpirationTime();
                    Instant expiry = exp != null ? exp.toInstant() : Instant.now().plus(Duration.ofMinutes(55));
                    return new CachedToken(token.getTokenValue(), expiry);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Abstraction to make token fetching testable without hitting the metadata server. */
    @FunctionalInterface
    interface TokenFetcher {
        Mono<CachedToken> fetch(String audience);
    }

    record CachedToken(String token, Instant expiry) { }
}
