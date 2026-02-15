package dev.bored.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.util.List;

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

    @Test
    void handle_responseStatusException_usesCorrectStatus() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String body = response.getBodyAsString().block();
        assertThat(body).contains("\"status\":404");
        assertThat(body).contains("\"message\":\"Not found\"");
    }

    @Test
    void handle_validationException_returns400() throws NoSuchMethodException {
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/signup").build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult("target", "loginRequest");
        bindingResult.addError(new FieldError("loginRequest", "email", "must not be blank"));

        MethodParameter param = new MethodParameter(
                GatewayExceptionHandlerTest.class.getDeclaredMethod("handle_validationException_returns400"), -1);
        WebExchangeBindException ex = new WebExchangeBindException(param, bindingResult);

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyComplete();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = response.getBodyAsString().block();
        assertThat(body).contains("\"status\":400");
        assertThat(body).contains("email: must not be blank");
    }
}
