package dev.bored.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the API Gateway service.
 * <p>
 * Runs on Spring Cloud Gateway (reactive / Netty) and acts as the single
 * entry point for all client traffic. Routes requests to backend services
 * using static Cloud Run URLs configured through environment variables.
 * </p>
 *
 * @author Bored Software Developer
 * @since 2026-02-15
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
