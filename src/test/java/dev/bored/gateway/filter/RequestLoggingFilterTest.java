package dev.bored.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequestLoggingFilter}.
 */
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void filter_logsRequestAndCompletesSuccessfully() {
        MockServerHttpRequest request = MockServerHttpRequest
                .method(HttpMethod.GET, "/api/v1/profiles/1")
                .build();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.setStatusCode(HttpStatus.OK);

        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void getOrder_returnsHighestPrecedence() {
        assert filter.getOrder() == Integer.MIN_VALUE;
    }
}
