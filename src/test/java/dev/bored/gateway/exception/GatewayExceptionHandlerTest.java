package dev.bored.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatewayExceptionHandler}.
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void handle_returns500WithStructuredJson() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/profiles/1").build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        RuntimeException ex = new RuntimeException("Something broke");

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        String body = response.getBodyAsString().block();
        assertThat(body).contains("\"status\":500");
        assertThat(body).contains("\"message\":\"Something broke\"");
        assertThat(body).contains("\"path\":\"/api/v1/profiles/1\"");
    }

    @Test
    void handle_escapesQuotesInMessage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        RuntimeException ex = new RuntimeException("bad \"input\"");

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        String body = response.getBodyAsString().block();
        assertThat(body).contains("bad \\\"input\\\"");
    }

    @Test
    void handle_handlesNullMessage() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/null-test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        RuntimeException ex = new RuntimeException((String) null);

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        String body = response.getBodyAsString().block();
        assertThat(body).contains("\"message\":\"Unknown error\"");
    }
}
