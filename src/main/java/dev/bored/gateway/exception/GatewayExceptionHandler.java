package dev.bored.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Global exception handler for the reactive gateway.
 * <p>
 * Catches all unhandled exceptions and returns a structured JSON error
 * response. Runs at high precedence ({@code -2}) to fire before Spring's
 * default error handler.
 * </p>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        log.error("Gateway error on {} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                ex.getMessage(), ex);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String path = exchange.getRequest().getURI().getPath();
        String body = String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"path\":\"%s\"}",
                status.value(),
                status.getReasonPhrase(),
                escapeJson(ex.getMessage()),
                Instant.now().toString(),
                escapeJson(path)
        );

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String escapeJson(String value) {
        if (value == null) return "Unknown error";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
