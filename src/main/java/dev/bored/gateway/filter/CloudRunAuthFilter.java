package dev.bored.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches a Google-signed OIDC ID token on every outbound proxy call to a
 * Cloud Run service. Required when the downstream service is configured with
 * {@code --no-allow-unauthenticated} — Cloud Run's ingress layer validates the
 * token against the calling identity's {@code roles/run.invoker} binding.
 *
 * <p>On Cloud Run we get tokens by hitting the instance metadata server
 * ({@code http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity})
 * which returns a JWT signed by Google for the audience we specify.</p>
 *
 * <p>The filter is a no-op outside Cloud Run (detected via the {@code K_SERVICE}
 * environment variable) so local {@code bootRun} keeps working.</p>
 *
 * <p>Tokens are cached per audience and refreshed when fewer than 5 minutes
 * remain before the {@code exp} claim.</p>
 */
@Component
public class CloudRunAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CloudRunAuthFilter.class);

    /** Refresh buffer — refresh the token when fewer than this many minutes remain. */
    private static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);

    /** Metadata server URL template used to mint ID tokens. */
    private static final String METADATA_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenFetcher tokenFetcher;
    private final boolean enabled;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    @Autowired
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

        // Stash the caller's original Authorization (likely a Supabase JWT)
        // under X-Forwarded-Authorization BEFORE we overwrite Authorization
        // with the Google ID token. Downstream services read user identity
        // from the forwarded header; Authorization is consumed by Cloud Run.
        //
        // We ALWAYS set the forwarded header — even to empty — so the
        // downstream BearerTokenResolver knows the gateway has handled auth
        // and should ignore the Google token in Authorization. Anything
        // less means unauthenticated GETs get their Google token validated
        // against the Supabase JWKS and 401.
        String originalAuth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String forwardedValue = originalAuth != null ? originalAuth : "";

        return fetchToken(audience)
                .flatMap(token -> {
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header("X-Forwarded-Authorization", forwardedValue)
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
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

    /**
     * Pull an ID token straight from the Cloud Run metadata server. The
     * response body is the raw JWT (not JSON); we parse its {@code exp}
     * claim locally so we know when to refresh.
     */
    private static TokenFetcher defaultFetcher() {
        return metadataFetcher(METADATA_URL);
    }

    /** Package-private so tests can point the fetcher at a local MockWebServer. */
    static TokenFetcher metadataFetcher(String metadataUrl) {
        WebClient client = WebClient.builder().build();
        return audience -> client.get()
                .uri(uriBuilder -> URI.create(metadataUrl + "?audience=" + audience))
                .header("Metadata-Flavor", "Google")
                .retrieve()
                .bodyToMono(String.class)
                .map(rawJwt -> new CachedToken(rawJwt, extractExpiry(rawJwt)));
    }

    /** Decode a JWT's {@code exp} claim without verifying the signature. */
    static Instant extractExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return Instant.now().plus(Duration.ofMinutes(55));
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(payload);
            long exp = node.path("exp").asLong(0);
            if (exp == 0) {
                return Instant.now().plus(Duration.ofMinutes(55));
            }
            return Instant.ofEpochSecond(exp);
        } catch (Exception e) {
            log.warn("Could not parse exp from ID token, defaulting to 55-minute lifetime", e);
            return Instant.now().plus(Duration.ofMinutes(55));
        }
    }

    /** Abstraction to make token fetching testable without hitting the metadata server. */
    @FunctionalInterface
    interface TokenFetcher {
        Mono<CachedToken> fetch(String audience);
    }

    record CachedToken(String token, Instant expiry) { }
}
